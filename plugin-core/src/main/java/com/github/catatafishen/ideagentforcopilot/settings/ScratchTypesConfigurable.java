package com.github.catatafishen.ideagentforcopilot.settings;

import com.intellij.icons.AllIcons;
import com.intellij.lang.Language;
import com.intellij.lang.LanguageUtil;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.options.Configurable;
import com.intellij.ui.CheckBoxList;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Settings page with two sections:
 * <ol>
 *   <li>Language checklist — which languages appear in the "New Scratch File" dropdown</li>
 *   <li>Alias table — extra code-fence label→extension mappings for "Open in Scratch"</li>
 * </ol>
 */
public final class ScratchTypesConfigurable implements Configurable {

    private CheckBoxList<Language> languageList;
    private JBTable table;
    private MappingsTableModel tableModel;

    @Nls
    @Override
    public String getDisplayName() {
        return "Scratch File Types";
    }

    @NotNull
    @Override
    public JComponent createComponent() {
        JBPanel<?> languagePanel = createLanguagePanel();
        JBPanel<?> aliasPanel = createAliasPanel();

        JBSplitter splitter = new JBSplitter(true, 0.5f);
        splitter.setFirstComponent(languagePanel);
        splitter.setSecondComponent(aliasPanel);

        return splitter;
    }

    private JBPanel<?> createLanguagePanel() {
        languageList = new CheckBoxList<>() {
            @Override
            protected @Nullable JComponent adjustRendering(
                JComponent rootComponent, JCheckBox checkBox, int index,
                boolean selected, boolean hasFocus) {
                Language lang = getItemAt(index);
                if (lang != null && lang.getAssociatedFileType() != null) {
                    checkBox.setIcon(lang.getAssociatedFileType().getIcon());
                }
                return super.adjustRendering(rootComponent, checkBox, index, selected, hasFocus);
            }
        };
        loadLanguageList();

        JPanel decorated = ToolbarDecorator.createDecorator(languageList)
            .disableAddAction()
            .disableRemoveAction()
            .disableUpDownActions()
            .addExtraAction(selectAllAction())
            .addExtraAction(deselectAllAction())
            .addExtraAction(resetDefaultsAction())
            .createPanel();

        JBPanel<?> panel = new JBPanel<>(new BorderLayout());
        JBLabel hint = new JBLabel("Languages shown in the \"New Scratch File\" dropdown:");
        hint.setBorder(JBUI.Borders.empty(0, 0, 4, 0));
        panel.add(hint, BorderLayout.NORTH);
        panel.add(decorated, BorderLayout.CENTER);
        return panel;
    }

    private JBPanel<?> createAliasPanel() {
        tableModel = new MappingsTableModel();
        loadAliasTable();

        table = new JBTable(tableModel);
        table.getColumnModel().getColumn(0).setPreferredWidth(JBUI.scale(200));
        table.getColumnModel().getColumn(1).setPreferredWidth(JBUI.scale(100));

        JPanel decorated = ToolbarDecorator.createDecorator(table)
            .setAddAction(b -> {
                tableModel.addRow("", "");
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
            "<html>Extra aliases for the \"Open in Scratch\" button in chat code blocks.<br>"
                + "Languages recognized by IntelliJ are resolved automatically — only add aliases "
                + "for code-fence labels that aren't matched (e.g. <code>bash</code> → <code>sh</code>).</html>");
        hint.setBorder(JBUI.Borders.empty(4, 0, 4, 0));
        panel.add(hint, BorderLayout.NORTH);
        panel.add(decorated, BorderLayout.CENTER);
        return panel;
    }

    // ── Toolbar actions ──

    private AnAction selectAllAction() {
        return new AnAction("Select All", "Select all languages", AllIcons.Actions.Selectall) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                setAllSelected(true);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        };
    }

    private AnAction deselectAllAction() {
        return new AnAction("Deselect All", "Deselect all languages", AllIcons.Actions.Unselectall) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                setAllSelected(false);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        };
    }

    private AnAction resetDefaultsAction() {
        return new AnAction("Reset to Defaults", "Reset language selection to defaults", AllIcons.Actions.Rollback) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                Set<String> defaults = ScratchTypeSettings.getDefaultEnabledIds();
                List<Language> all = LanguageUtil.getFileLanguages();
                for (Language lang : all) {
                    languageList.setItemSelected(lang, defaults.contains(lang.getID()));
                }
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        };
    }

    private void setAllSelected(boolean selected) {
        for (Language lang : LanguageUtil.getFileLanguages()) {
            languageList.setItemSelected(lang, selected);
        }
    }

    // ── Load / save ──

    private void loadLanguageList() {
        if (languageList == null) return;
        Set<String> enabled = ScratchTypeSettings.getInstance().getEnabledLanguageIds();
        List<Language> allLanguages = LanguageUtil.getFileLanguages();
        for (Language lang : allLanguages) {
            languageList.addItem(lang, lang.getDisplayName(), enabled.contains(lang.getID()));
        }
    }

    private void loadAliasTable() {
        if (tableModel == null) return;
        tableModel.clear();
        Map<String, String> mappings = ScratchTypeSettings.getInstance().getMappings();
        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            tableModel.addRow(entry.getKey(), entry.getValue());
        }
    }

    private Set<String> getCheckedLanguageIds() {
        Set<String> ids = new LinkedHashSet<>();
        List<Language> checked = languageList.getCheckedItems();
        for (Language lang : checked) {
            ids.add(lang.getID());
        }
        return ids;
    }

    @Override
    public boolean isModified() {
        ScratchTypeSettings settings = ScratchTypeSettings.getInstance();
        if (!settings.getEnabledLanguageIds().equals(getCheckedLanguageIds())) return true;
        return !settings.getMappings().equals(tableModel.toMap());
    }

    @Override
    public void apply() {
        ScratchTypeSettings settings = ScratchTypeSettings.getInstance();
        settings.setEnabledLanguageIds(getCheckedLanguageIds());
        settings.setMappings(tableModel.toMap());
    }

    @Override
    public void reset() {
        languageList.clear();
        loadLanguageList();
        loadAliasTable();
    }

    @Override
    public void disposeUIResources() {
        languageList = null;
        table = null;
        tableModel = null;
    }

    // ── Alias table model ──

    private static final class MappingsTableModel extends AbstractTableModel {
        private final List<String[]> rows = new ArrayList<>();

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(int column) {
            return column == 0 ? "Language Alias" : "Extension";
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
            rows.get(row)[column] = value.toString().trim().toLowerCase();
            fireTableCellUpdated(row, column);
        }

        void addRow(String alias, String extension) {
            rows.add(new String[]{alias, extension});
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

        Map<String, String> toMap() {
            Map<String, String> map = new LinkedHashMap<>();
            for (String[] row : rows) {
                String alias = row[0].trim().toLowerCase();
                String ext = row[1].trim().toLowerCase();
                if (!alias.isEmpty() && !ext.isEmpty()) {
                    map.put(alias, ext);
                }
            }
            return map;
        }
    }
}
