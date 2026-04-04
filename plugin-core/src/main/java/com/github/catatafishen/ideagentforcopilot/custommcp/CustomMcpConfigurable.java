package com.github.catatafishen.ideagentforcopilot.custommcp;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.io.Serial;
import java.util.ArrayList;
import java.util.List;

/**
 * Settings panel at Settings → Tools → AgentBridge → MCP → Custom MCP Servers.
 * Manages a list of external HTTP MCP servers; each is proxied into the tool registry.
 */
public final class CustomMcpConfigurable implements Configurable {

    private static final int COL_ENABLED = 0;
    private static final int COL_NAME = 1;
    private static final int COL_URL = 2;
    private static final int COL_COUNT = 3;

    private final Project project;
    private ServerTableModel tableModel;
    private JBTable table;
    private JBTextArea instructionsArea;
    private JButton testButton;
    private int lastSelectedRow = -1;

    public CustomMcpConfigurable(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Custom MCP Servers";
    }

    @Override
    public @NotNull JComponent createComponent() {
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(8)));

        JBLabel info = new JBLabel(
            "<html>Configure external MCP servers (HTTP/SSE). Their tools will be discovered at startup "
                + "and proxied into the agent's tool list. Use the Instructions field to tell the agent "
                + "how to use each server's tools.</html>");
        info.setBorder(JBUI.Borders.emptyBottom(4));
        panel.add(info, BorderLayout.NORTH);

        tableModel = new ServerTableModel();
        table = new JBTable(tableModel);
        table.setRowHeight(JBUI.scale(24));
        table.getColumnModel().getColumn(COL_ENABLED).setMaxWidth(JBUI.scale(65));
        table.getColumnModel().getColumn(COL_ENABLED).setMinWidth(JBUI.scale(65));
        table.getColumnModel().getColumn(COL_NAME).setPreferredWidth(JBUI.scale(160));
        table.getColumnModel().getColumn(COL_URL).setPreferredWidth(JBUI.scale(320));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getSelectionModel().addListSelectionListener(this::onSelectionChanged);

        ToolbarDecorator decorator = ToolbarDecorator.createDecorator(table)
            .setAddAction(button -> {
                saveCurrentInstructions();
                tableModel.addServer(new CustomMcpServerConfig());
                int newRow = tableModel.getRowCount() - 1;
                table.setRowSelectionInterval(newRow, newRow);
                table.scrollRectToVisible(table.getCellRect(newRow, 0, true));
            })
            .setRemoveAction(button -> {
                saveCurrentInstructions();
                int row = table.getSelectedRow();
                if (row >= 0) {
                    tableModel.removeServer(row);
                    lastSelectedRow = -1;
                    updateInstructionsPanel(-1);
                }
            })
            .disableUpDownActions();

        // Instructions panel (for selected server)
        instructionsArea = new JBTextArea(5, 0);
        instructionsArea.setLineWrap(true);
        instructionsArea.setWrapStyleWord(true);
        instructionsArea.setEnabled(false);

        JPanel instrPanel = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        instrPanel.setBorder(JBUI.Borders.emptyTop(8));
        instrPanel.add(new JBLabel("Usage Instructions (appended to this server's tool descriptions):"),
            BorderLayout.NORTH);
        instrPanel.add(new JBScrollPane(instructionsArea), BorderLayout.CENTER);

        testButton = new JButton("Test Connection");
        testButton.setEnabled(false);
        testButton.addActionListener(e -> testSelectedConnection());
        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, JBUI.scale(4)));
        buttonRow.add(testButton);
        instrPanel.add(buttonRow, BorderLayout.SOUTH);

        JPanel center = new JPanel(new BorderLayout());
        center.add(decorator.createPanel(), BorderLayout.CENTER);
        center.add(instrPanel, BorderLayout.SOUTH);

        panel.add(center, BorderLayout.CENTER);

        reset();
        return panel;
    }

    private void onSelectionChanged(ListSelectionEvent e) {
        if (e.getValueIsAdjusting()) return;
        saveCurrentInstructions();
        int row = table.getSelectedRow();
        lastSelectedRow = row;
        updateInstructionsPanel(row);
    }

    private void updateInstructionsPanel(int row) {
        if (instructionsArea == null) return;
        boolean hasSelection = row >= 0 && row < tableModel.getRowCount();
        instructionsArea.setEnabled(hasSelection);
        instructionsArea.setText(hasSelection ? tableModel.getServer(row).getInstructions() : "");
        if (testButton != null) testButton.setEnabled(hasSelection);
    }

    private void saveCurrentInstructions() {
        if (instructionsArea == null) return;
        if (lastSelectedRow >= 0 && lastSelectedRow < tableModel.getRowCount()) {
            tableModel.getServer(lastSelectedRow).setInstructions(instructionsArea.getText());
        }
    }

    private void testSelectedConnection() {
        int row = table.getSelectedRow();
        if (row < 0) return;
        saveCurrentInstructions();
        CustomMcpServerConfig server = tableModel.getServer(row).copy();
        if (server.getUrl().isBlank()) {
            Messages.showErrorDialog(project, "Please enter a URL for this server.", "No URL Configured");
            return;
        }
        testButton.setEnabled(false);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                CustomMcpClient client = new CustomMcpClient(server.getUrl());
                client.initialize();
                List<CustomMcpClient.ToolInfo> tools = client.listTools();
                String toolList = tools.stream()
                    .map(t -> "• " + t.name())
                    .reduce("", (a, b) -> a + "\n" + b);
                ApplicationManager.getApplication().invokeLater(() -> {
                    testButton.setEnabled(true);
                    Messages.showInfoMessage(project,
                        "Connected successfully. Found " + tools.size() + " tool(s):" + toolList,
                        "Connection Successful");
                });
            } catch (Exception ex) {
                String msg = ex.getMessage();
                ApplicationManager.getApplication().invokeLater(() -> {
                    testButton.setEnabled(true);
                    Messages.showErrorDialog(project,
                        "Failed to connect:\n" + msg, "Connection Failed");
                });
            }
        });
    }

    @Override
    public boolean isModified() {
        if (tableModel == null) return false;
        saveCurrentInstructions();
        return !tableModel.getServers().equals(CustomMcpSettings.getInstance(project).getServers());
    }

    @Override
    public void apply() throws ConfigurationException {
        if (tableModel == null) return;
        saveCurrentInstructions();
        CustomMcpSettings settings = CustomMcpSettings.getInstance(project);
        settings.setServers(tableModel.getServers().stream().map(CustomMcpServerConfig::copy).toList());
        ApplicationManager.getApplication().executeOnPooledThread(
            () -> CustomMcpRegistrar.getInstance(project).syncRegistrations()
        );
    }

    @Override
    public void reset() {
        if (tableModel == null) return;
        saveCurrentInstructions();
        lastSelectedRow = -1;
        tableModel.setServers(
            CustomMcpSettings.getInstance(project).getServers().stream()
                .map(CustomMcpServerConfig::copy)
                .toList()
        );
        updateInstructionsPanel(-1);
    }

    // ── Table model ───────────────────────────────────────────────────────

    private static final class ServerTableModel extends AbstractTableModel {

        private static final String[] COLUMNS = {"Enabled", "Name", "URL"};
        @Serial
        private static final long serialVersionUID = 1L;

        transient List<CustomMcpServerConfig> rows = new ArrayList<>();

        void addServer(CustomMcpServerConfig server) {
            rows.add(server);
            fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
        }

        void removeServer(int row) {
            rows.remove(row);
            fireTableRowsDeleted(row, row);
        }

        @NotNull
        CustomMcpServerConfig getServer(int row) {
            return rows.get(row);
        }

        @NotNull
        List<CustomMcpServerConfig> getServers() {
            return rows;
        }

        void setServers(@NotNull List<CustomMcpServerConfig> servers) {
            rows = new ArrayList<>(servers);
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COL_COUNT;
        }

        @Override
        public String getColumnName(int col) {
            return COLUMNS[col];
        }

        @Override
        public Class<?> getColumnClass(int col) {
            return col == COL_ENABLED ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int row, int col) {
            return true;
        }

        @Override
        @Nullable
        public Object getValueAt(int row, int col) {
            CustomMcpServerConfig s = rows.get(row);
            return switch (col) {
                case COL_ENABLED -> s.isEnabled();
                case COL_NAME -> s.getName();
                case COL_URL -> s.getUrl();
                default -> null;
            };
        }

        @Override
        public void setValueAt(Object value, int row, int col) {
            CustomMcpServerConfig s = rows.get(row);
            switch (col) {
                case COL_ENABLED -> s.setEnabled((Boolean) value);
                case COL_NAME -> s.setName(((String) value).trim());
                case COL_URL -> s.setUrl(((String) value).trim());
                default -> { /* read-only */ }
            }
            fireTableCellUpdated(row, col);
        }
    }
}
