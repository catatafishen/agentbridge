package com.github.catatafishen.agentbridge.ui.statistics;

import com.github.catatafishen.agentbridge.services.ToolCallStatisticsService;
import com.github.catatafishen.agentbridge.services.ToolCallStatisticsService.ToolAggregate;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Panel displaying per-tool MCP call statistics in a filterable table with summary labels.
 */
public class ToolStatisticsPanel extends JPanel {

    private static final String ALL_CLIENTS = "All clients";

    private final Project project;
    private final ToolStatisticsTableModel tableModel = new ToolStatisticsTableModel();
    private final JComboBox<String> rangeCombo = new JComboBox<>(new String[]{
        "Last hour", "Last 24 hours", "Last 7 days", "All time"
    });
    private final JComboBox<String> clientCombo = new JComboBox<>();
    private final JBLabel summaryLabel = new JBLabel();

    public ToolStatisticsPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;
        setBorder(JBUI.Borders.empty(8));

        add(buildToolbar(), BorderLayout.NORTH);
        add(buildTable(), BorderLayout.CENTER);
        add(summaryLabel, BorderLayout.SOUTH);

        refreshClients();
        loadData();
    }

    private JPanel buildToolbar() {
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        toolbar.add(new JBLabel("Time range:"));
        rangeCombo.setSelectedIndex(3);
        rangeCombo.addActionListener(e -> loadData());
        toolbar.add(rangeCombo);

        toolbar.add(new JBLabel("Client:"));
        clientCombo.addItem(ALL_CLIENTS);
        clientCombo.addActionListener(e -> loadData());
        toolbar.add(clientCombo);

        JButton refreshButton = new JButton("Refresh");
        refreshButton.addActionListener(e -> {
            refreshClients();
            loadData();
        });
        toolbar.add(refreshButton);
        return toolbar;
    }

    private JComponent buildTable() {
        JBTable table = new JBTable(tableModel);
        table.setAutoCreateRowSorter(true);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);

        table.getColumnModel().getColumn(0).setPreferredWidth(200); // Tool
        table.getColumnModel().getColumn(1).setPreferredWidth(120); // Category
        table.getColumnModel().getColumn(2).setPreferredWidth(60);  // Calls
        table.getColumnModel().getColumn(3).setPreferredWidth(120); // Avg Duration
        table.getColumnModel().getColumn(4).setPreferredWidth(90);  // Avg Data
        table.getColumnModel().getColumn(5).setPreferredWidth(90);  // Total Input
        table.getColumnModel().getColumn(6).setPreferredWidth(90);  // Total Output
        table.getColumnModel().getColumn(7).setPreferredWidth(80);  // Error Rate

        return new JBScrollPane(table);
    }

    private void loadData() {
        ToolCallStatisticsService service = ToolCallStatisticsService.getInstance(project);
        if (service == null) return;

        String since = computeSince(rangeCombo.getSelectedIndex(), Instant.now());
        String clientId = ALL_CLIENTS.equals(clientCombo.getSelectedItem())
            ? null : (String) clientCombo.getSelectedItem();

        List<ToolAggregate> aggregates = service.queryAggregates(since, clientId);
        tableModel.setData(aggregates);

        Map<String, Long> summary = service.querySummary(since, clientId);
        updateSummary(summary);
    }

    private void refreshClients() {
        ToolCallStatisticsService service = ToolCallStatisticsService.getInstance(project);
        if (service == null) return;
        Object selected = clientCombo.getSelectedItem();
        clientCombo.removeAllItems();
        clientCombo.addItem(ALL_CLIENTS);
        for (String client : service.getDistinctClients()) {
            clientCombo.addItem(client);
        }
        clientCombo.setSelectedItem(selected);
    }

    /**
     * Computes the ISO-8601 timestamp threshold for the given range combo index.
     * Package-private for testing.
     */
    static String computeSince(int rangeIndex, Instant now) {
        return switch (rangeIndex) {
            case 0 -> now.minus(1, ChronoUnit.HOURS).toString();
            case 1 -> now.minus(24, ChronoUnit.HOURS).toString();
            case 2 -> now.minus(7, ChronoUnit.DAYS).toString();
            default -> null;
        };
    }

    private void updateSummary(Map<String, Long> summary) {
        summaryLabel.setText(formatSummary(summary));
    }

    /**
     * Formats a summary map into a human-readable string. Package-private for testing.
     */
    static String formatSummary(Map<String, Long> summary) {
        if (summary.isEmpty()) {
            return "No data";
        }
        long calls = summary.getOrDefault("totalCalls", 0L);
        long errors = summary.getOrDefault("totalErrors", 0L);
        String totalInput = ToolStatisticsTableModel.formatBytes(
            summary.getOrDefault("totalInputBytes", 0L));
        String totalOutput = ToolStatisticsTableModel.formatBytes(
            summary.getOrDefault("totalOutputBytes", 0L));
        return String.format(
            "Total: %d calls  |  %d errors  |  Input: %s  |  Output: %s",
            calls, errors, totalInput, totalOutput);
    }
}
