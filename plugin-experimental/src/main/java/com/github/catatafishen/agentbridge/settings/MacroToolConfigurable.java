package com.github.catatafishen.agentbridge.settings;

import com.github.catatafishen.agentbridge.services.MacroToolRegistrar;
import com.github.catatafishen.agentbridge.services.MacroToolSettings;
import com.github.catatafishen.agentbridge.services.MacroToolSettings.MacroRegistration;
import com.intellij.ide.actionMacro.ActionMacro;
import com.intellij.ide.actionMacro.ActionMacroManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.io.Serial;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Settings panel at Settings > Tools > Macro Tools.
 * Auto-discovers all recorded macros from {@link ActionMacroManager} and lets users
 * enable them as MCP tools with custom names and descriptions.
 */
public final class MacroToolConfigurable implements Configurable {

    private static final int COL_ENABLED = 0;
    private static final int COL_MACRO = 1;
    private static final int COL_TOOL_NAME = 2;
    private static final int COL_DESCRIPTION = 3;
    private static final int COL_COUNT = 4;

    private final Project project;
    private MacroTableModel tableModel;

    public MacroToolConfigurable(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Macro Tools";
    }

    @Override
    public @NotNull JComponent createComponent() {
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(8)));

        JBLabel info = new JBLabel(
            "<html>Recorded macros are auto-discovered. Enable a macro to expose it as an MCP tool. "
                + "Record new macros via <b>Edit → Macros → Start Macro Recording</b>.</html>");
        info.setBorder(JBUI.Borders.emptyBottom(4));
        panel.add(info, BorderLayout.NORTH);

        tableModel = new MacroTableModel();
        JBTable table = new JBTable(tableModel);
        table.getColumnModel().getColumn(COL_ENABLED).setMaxWidth(JBUI.scale(60));
        table.getColumnModel().getColumn(COL_ENABLED).setMinWidth(JBUI.scale(60));
        table.getColumnModel().getColumn(COL_MACRO).setPreferredWidth(JBUI.scale(150));
        table.getColumnModel().getColumn(COL_TOOL_NAME).setPreferredWidth(JBUI.scale(150));
        table.getColumnModel().getColumn(COL_DESCRIPTION).setPreferredWidth(JBUI.scale(300));
        table.setRowHeight(JBUI.scale(24));

        panel.add(new JBScrollPane(table), BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        JButton refreshButton = new JButton("Refresh");
        refreshButton.setToolTipText("Re-scan for newly recorded macros");
        refreshButton.addActionListener(e -> refreshMacroList());
        buttonPanel.add(refreshButton);
        panel.add(buttonPanel, BorderLayout.SOUTH);

        reset();
        return panel;
    }

    /**
     * Builds the table rows by merging persisted registrations with all currently
     * recorded macros from {@link ActionMacroManager}. New macros appear as disabled.
     */
    private List<MacroRegistration> buildMergedRows() {
        MacroToolSettings settings = MacroToolSettings.getInstance(project);
        List<MacroRegistration> persisted = settings.getRegistrations();

        Map<String, MacroRegistration> byName = new LinkedHashMap<>();
        for (MacroRegistration reg : persisted) {
            byName.put(reg.getMacroName(), reg.copy());
        }

        for (ActionMacro macro : ActionMacroManager.getInstance().getAllMacros()) {
            byName.computeIfAbsent(macro.getName(), name -> {
                String toolName = MacroToolRegistrar.sanitizeToolName(name);
                return new MacroRegistration(name, toolName, "", false);
            });
        }

        return new ArrayList<>(byName.values());
    }

    /**
     * Refreshes the table by re-scanning for newly recorded macros while preserving
     * the user's in-progress edits for already-listed macros.
     */
    private void refreshMacroList() {
        if (tableModel == null) return;

        Map<String, MacroRegistration> currentEdits = new LinkedHashMap<>();
        for (MacroRegistration row : tableModel.rows) {
            currentEdits.put(row.getMacroName(), row);
        }

        Set<String> discoveredNames = new LinkedHashSet<>();
        for (ActionMacro macro : ActionMacroManager.getInstance().getAllMacros()) {
            discoveredNames.add(macro.getName());
        }

        List<MacroRegistration> merged = new ArrayList<>();
        for (Map.Entry<String, MacroRegistration> entry : currentEdits.entrySet()) {
            if (discoveredNames.remove(entry.getKey())) {
                merged.add(entry.getValue());
            }
        }
        for (String newName : discoveredNames) {
            String toolName = MacroToolRegistrar.sanitizeToolName(newName);
            merged.add(new MacroRegistration(newName, toolName, "", false));
        }

        tableModel.rows = merged;
        tableModel.fireTableDataChanged();
    }

    @Override
    public boolean isModified() {
        return !tableModel.rows.equals(buildMergedRows());
    }

    @Override
    public void apply() throws ConfigurationException {
        Set<String> names = new HashSet<>();
        for (MacroRegistration reg : tableModel.rows) {
            if (reg.isEnabled() && !reg.getToolName().isEmpty() && !names.add(reg.getToolName())) {
                throw new ConfigurationException(
                    "Duplicate tool name: " + reg.getToolName());
            }
        }

        MacroToolSettings settings = MacroToolSettings.getInstance(project);
        settings.setRegistrations(tableModel.rows.stream()
            .map(MacroRegistration::copy)
            .toList());

        MacroToolRegistrar.getInstance(project).syncRegistrations();
    }

    @Override
    public void reset() {
        if (tableModel == null) return;
        tableModel.rows = buildMergedRows();
        tableModel.fireTableDataChanged();
    }

    private static final class MacroTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Enabled", "Macro", "Tool Name", "Description"};
        @Serial
        private static final long serialVersionUID = 1L;
        transient List<MacroRegistration> rows = new ArrayList<>();

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COL_COUNT;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == COL_ENABLED ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex != COL_MACRO;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            MacroRegistration reg = rows.get(rowIndex);
            return switch (columnIndex) {
                case COL_ENABLED -> reg.isEnabled();
                case COL_MACRO -> reg.getMacroName();
                case COL_TOOL_NAME -> reg.getToolName();
                case COL_DESCRIPTION -> reg.getDescription();
                default -> null;
            };
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            MacroRegistration reg = rows.get(rowIndex);
            switch (columnIndex) {
                case COL_ENABLED -> reg.setEnabled((Boolean) aValue);
                case COL_TOOL_NAME -> reg.setToolName(((String) aValue).trim());
                case COL_DESCRIPTION -> reg.setDescription(((String) aValue).trim());
                default -> { /* macro name is read-only */ }
            }
            fireTableCellUpdated(rowIndex, columnIndex);
        }
    }
}
