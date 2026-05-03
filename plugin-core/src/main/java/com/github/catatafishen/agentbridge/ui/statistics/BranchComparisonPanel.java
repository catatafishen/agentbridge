package com.github.catatafishen.agentbridge.ui.statistics;

import com.github.catatafishen.agentbridge.ui.util.VerticalScrollablePanel;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;
import java.util.EnumMap;
import java.util.Map;

/**
 * Dedicated panel for branch-comparison charts. Each metric chart is rendered
 * at full container width and expands vertically to fit all branch bars.
 * The entire view scrolls vertically when the charts exceed the viewport.
 *
 * <p>This panel lives in its own tab (separate from the agent time-series
 * charts) so that long branch lists and long branch names are fully visible.
 */
class BranchComparisonPanel extends JBPanel<BranchComparisonPanel> {

    private static final Logger LOG = Logger.getInstance(BranchComparisonPanel.class);

    private final transient Project project;
    private final ComboBox<UsageStatisticsData.TimeRange> rangeCombo;
    private final ComboBox<UsageStatisticsData.BranchSort> sortCombo;
    private final Map<UsageStatisticsData.Metric, BranchComparisonChart> charts =
        new EnumMap<>(UsageStatisticsData.Metric.class);
    private final JBLabel hintLabel;
    private transient UsageStatisticsData.BranchSnapshot currentSnapshot;

    BranchComparisonPanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        setBorder(JBUI.Borders.empty(12));

        JPanel toolbar = new JPanel();
        toolbar.setLayout(new BoxLayout(toolbar, BoxLayout.X_AXIS));
        toolbar.add(new JBLabel("Period:"));
        toolbar.add(Box.createHorizontalStrut(JBUI.scale(6)));
        rangeCombo = StatisticsComboFactory.createLabeledCombo(
            UsageStatisticsData.TimeRange.values(),
            UsageStatisticsData.TimeRange.MONTH_30,
            UsageStatisticsData.TimeRange::label);
        rangeCombo.addActionListener(e -> reload());
        toolbar.add(rangeCombo);
        toolbar.add(Box.createHorizontalStrut(JBUI.scale(16)));
        toolbar.add(new JBLabel("Order by:"));
        toolbar.add(Box.createHorizontalStrut(JBUI.scale(6)));
        sortCombo = StatisticsComboFactory.createLabeledCombo(
            UsageStatisticsData.BranchSort.values(),
            UsageStatisticsData.BranchSort.BAR_VALUE,
            UsageStatisticsData.BranchSort::label);
        sortCombo.addActionListener(e -> {
            if (currentSnapshot != null) {
                updateCharts(currentSnapshot);
            }
        });
        toolbar.add(sortCombo);
        add(toolbar, BorderLayout.NORTH);

        VerticalScrollablePanel chartsPanel = new VerticalScrollablePanel();
        chartsPanel.setLayout(new BoxLayout(chartsPanel, BoxLayout.Y_AXIS));

        for (UsageStatisticsData.Metric metric : UsageStatisticsData.Metric.values()) {
            BranchComparisonChart chart = new BranchComparisonChart(metric.displayName(), metric);
            chart.setPreferredSize(null);
            chart.setAlignmentX(Component.LEFT_ALIGNMENT);
            charts.put(metric, chart);
            if (chartsPanel.getComponentCount() > 0) {
                chartsPanel.add(Box.createVerticalStrut(JBUI.scale(16)));
            }
            chartsPanel.add(chart);
        }

        hintLabel = new JBLabel(" ");
        hintLabel.setBorder(JBUI.Borders.emptyTop(8));
        hintLabel.setForeground(JBColor.GRAY);
        hintLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        chartsPanel.add(Box.createVerticalStrut(JBUI.scale(8)));
        chartsPanel.add(hintLabel);

        JBScrollPane scrollPane = new JBScrollPane(chartsPanel);
        scrollPane.setBorder(JBUI.Borders.empty());
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.getVerticalScrollBar().setUnitIncrement(JBUI.scale(16));
        add(scrollPane, BorderLayout.CENTER);

        reload();
    }

    private void reload() {
        UsageStatisticsData.TimeRange range =
            (UsageStatisticsData.TimeRange) rangeCombo.getSelectedItem();
        if (range == null) return;

        ModalityState modality = ModalityState.any();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                UsageStatisticsData.BranchSnapshot snapshot =
                    UsageStatisticsLoader.loadBranches(project, range);
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (project.isDisposed()) return;
                    updateCharts(snapshot);
                }, modality);
            } catch (Exception e) {
                LOG.error("Branch comparison panel: failed to load data for range " + range, e);
            }
        });
    }

    private void updateCharts(UsageStatisticsData.BranchSnapshot snapshot) {
        currentSnapshot = snapshot;
        UsageStatisticsData.BranchSort sort =
            (UsageStatisticsData.BranchSort) sortCombo.getSelectedItem();
        if (sort == null) return;

        for (BranchComparisonChart chart : charts.values()) {
            chart.update(snapshot, sort);
        }

        if (snapshot.unattributed() > 0) {
            hintLabel.setText(snapshot.unattributed()
                + " turn(s) in this period have no branch attribution"
                + " (recorded before per-branch tracking, or git was unavailable).");
        } else if (snapshot.branches().isEmpty()) {
            hintLabel.setText("No branch data yet. Submit a prompt while on a feature"
                + " branch to start tracking per-branch usage.");
        } else {
            hintLabel.setText(" ");
        }
    }

}
