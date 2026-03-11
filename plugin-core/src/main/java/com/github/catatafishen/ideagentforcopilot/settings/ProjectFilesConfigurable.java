package com.github.catatafishen.ideagentforcopilot.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Settings page for configuring project file shortcuts in the toolbar dropdown.
 * Appears under Settings > Tools > IDE Agent for Copilot > Project Files.
 */
public final class ProjectFilesConfigurable implements Configurable {

    private JBTable table;
    private EntriesTableModel tableModel;

    @Nls
    @Override
    public String getDisplayName() {
        return "Project Files";
    }

    @NotNull
    @Override
    public JComponent createComponent() {
        tableModel = new EntriesTableModel();
        loadFromSettings();

        table = new JBTable(tableModel);
        table.getColumnModel().getColumn(0).setPreferredWidth(JBUI.scale(150));
        table.getColumnModel().getColumn(1).setPreferredWidth(JBUI.scale(300));
        table.getColumnModel().getColumn(2).setPreferredWidth(JBUI.scale(60));

        JPanel decorated = ToolbarDecorator.createDecorator(table)
            .setAddAction(b -> {
                tableModel.addRow("", "", false);
                int row = tableModel.getRowCount() - 1;
                table.editCellAt(row, 0);
                table.getSelectionModel().setSelectionInterval(row, row);
            })
            .setRemoveAction(b -> {
                int row = table.getSelectedRow();
                if (row >= 0) tableModel.removeRow(row);
            })
            .createPanel();

        JBPanel<?> panel = new JBPanel<>(new BorderLayout());
        JBLabel hint = new JBLabel(
            "<html>Configure file shortcuts shown in the Project Files toolbar menu.<br>"
                + "Paths are relative to the project root. Enable <b>Glob</b> to list matching files "
                + "individually (e.g., <code>.github/agents/*.md</code>).</html>");
        hint.setBorder(JBUI.Borders.empty(0, 0, 8, 0));
        panel.add(hint, BorderLayout.NORTH);
        panel.add(decorated, BorderLayout.CENTER);

        return panel;
    }

    @Override
    public boolean isModified() {
        List<ProjectFilesSettings.FileEntry> current = ProjectFilesSettings.getInstance().getEntries();
        List<ProjectFilesSettings.FileEntry> edited = tableModel.toEntries();
        if (current.size() != edited.size()) return true;
        for (int i = 0; i < current.size(); i++) {
            ProjectFilesSettings.FileEntry a = current.get(i);
            ProjectFilesSettings.FileEntry b = edited.get(i);
            if (!Objects.equals(a.label, b.label)
                || !Objects.equals(a.path, b.path)
                || a.isGlob != b.isGlob) return true;
        }
        return false;
    }

    @Override
    public void apply() {
        ProjectFilesSettings.getInstance().setEntries(tableModel.toEntries());
    }

    @Override
    public void reset() {
        loadFromSettings();
    }

    @Override
    public void disposeUIResources() {
        table = null;
        tableModel = null;
    }

    private void loadFromSettings() {
        if (tableModel == null) return;
        tableModel.clear();
        for (ProjectFilesSettings.FileEntry entry : ProjectFilesSettings.getInstance().getEntries()) {
            tableModel.addRow(entry.label, entry.path, entry.isGlob);
        }
    }

    private static final class EntriesTableModel extends AbstractTableModel {
        private final List<Object[]> rows = new ArrayList<>();
        private static final String[] COLUMNS = {"Label", "Path", "Glob"};

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == 2 ? Boolean.class : String.class;
        }

        @Override
        public Object getValueAt(int row, int column) {
            return rows.get(row)[column];
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return true;
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            rows.get(row)[column] = column == 2 ? value : value.toString().trim();
            fireTableCellUpdated(row, column);
        }

        void addRow(String label, String path, boolean isGlob) {
            rows.add(new Object[]{label, path, isGlob});
            fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
        }

        void removeRow(int row) {
            rows.remove(row);
            fireTableRowsDeleted(row, row);
        }

        void clear() {
            int size = rows.size();
            if (size > 0) {
                rows.clear();
                fireTableRowsDeleted(0, size - 1);
            }
        }

        List<ProjectFilesSettings.FileEntry> toEntries() {
            List<ProjectFilesSettings.FileEntry> entries = new ArrayList<>();
            for (Object[] row : rows) {
                String label = ((String) row[0]).trim();
                String path = ((String) row[1]).trim();
                boolean isGlob = (Boolean) row[2];
                if (!label.isEmpty() && !path.isEmpty()) {
                    entries.add(new ProjectFilesSettings.FileEntry(label, path, isGlob));
                }
            }
            return entries;
        }
    }
}
