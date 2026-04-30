package com.github.catatafishen.agentbridge.settings

import com.intellij.icons.AllIcons
import com.intellij.lang.Language
import com.intellij.lang.LanguageUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.ui.CheckBoxList
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.table.AbstractTableModel

class ScratchTypesConfigurable :
    BoundConfigurable("Scratch File Types"),
    SearchableConfigurable {

    override fun getId(): String = "com.github.catatafishen.agentbridge.scratch-types"

    private val languageList = object : CheckBoxList<Language>() {
        override fun adjustRendering(
            rootComponent: JComponent, checkBox: JCheckBox, index: Int,
            selected: Boolean, hasFocus: Boolean
        ): JComponent? {
            val lang = getItemAt(index)
            if (lang?.associatedFileType != null) {
                checkBox.icon = lang.associatedFileType!!.icon
            }
            return super.adjustRendering(rootComponent, checkBox, index, selected, hasFocus)
        }
    }
    private val tableModel = MappingsTableModel()
    private val table = JBTable(tableModel).apply {
        columnModel.getColumn(0).preferredWidth = JBUI.scale(200)
        columnModel.getColumn(1).preferredWidth = JBUI.scale(100)
    }

    override fun createPanel() = panel {
        loadLanguageList()
        loadAliasTable()

        group("Languages in \"New Scratch File\" Dropdown") {
            row {
                val decorated = ToolbarDecorator.createDecorator(languageList)
                    .disableAddAction()
                    .disableRemoveAction()
                    .disableUpDownActions()
                    .addExtraAction(selectAllAction())
                    .addExtraAction(deselectAllAction())
                    .addExtraAction(resetDefaultsAction())
                    .createPanel()
                cell(decorated).align(AlignX.FILL).align(AlignY.FILL).resizableColumn()
            }.resizableRow().layout(RowLayout.PARENT_GRID)
        }
        @Suppress("DialogTitleCapitalization") // "Code-fence" is a single hyphenated noun.
        group("Code-fence Aliases") {
            row {
                comment(
                    "Extra aliases for the \"Open in Scratch\" button in chat code blocks. " +
                        "Languages recognized by IntelliJ are resolved automatically — only add " +
                        "aliases for code-fence labels that aren't matched (e.g. <code>bash</code> → <code>sh</code>)."
                )
            }
            row {
                val decorated = ToolbarDecorator.createDecorator(table)
                    .setAddAction {
                        tableModel.addRow("", "")
                        val r = tableModel.rowCount - 1
                        table.editCellAt(r, 0)
                        table.selectionModel.setSelectionInterval(r, r)
                    }
                    .setRemoveAction {
                        val r = table.selectedRow
                        if (r >= 0) tableModel.removeRow(r)
                    }
                    .createPanel()
                cell(decorated).align(AlignX.FILL).align(AlignY.FILL).resizableColumn()
            }.resizableRow().layout(RowLayout.PARENT_GRID)
        }
        onIsModified {
            ScratchTypeSettings.getInstance().enabledLanguageIds != getCheckedLanguageIds() ||
                ScratchTypeSettings.getInstance().mappings != tableModel.toMap()
        }
        onApply {
            val s = ScratchTypeSettings.getInstance()
            s.enabledLanguageIds = getCheckedLanguageIds()
            s.mappings = tableModel.toMap()
        }
        onReset {
            languageList.clear()
            loadLanguageList()
            loadAliasTable()
        }
    }

    private fun selectAllAction(): AnAction =
        object : AnAction("Select All", "Select all languages", AllIcons.Actions.Selectall) {
            override fun actionPerformed(e: AnActionEvent) = setAllSelected(true)
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

    private fun deselectAllAction(): AnAction =
        object : AnAction("Deselect All", "Deselect all languages", AllIcons.Actions.Unselectall) {
            override fun actionPerformed(e: AnActionEvent) = setAllSelected(false)
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

    private fun resetDefaultsAction(): AnAction =
        object : AnAction(
            "Reset to Defaults", "Reset language selection to defaults", AllIcons.Actions.Rollback
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                val defaults: Set<String> = ScratchTypeSettings.getDefaultEnabledIds()
                LanguageUtil.getFileLanguages().forEach {
                    languageList.setItemSelected(it, defaults.contains(it.id))
                }
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

    private fun setAllSelected(selected: Boolean) {
        LanguageUtil.getFileLanguages().forEach { languageList.setItemSelected(it, selected) }
    }

    private fun loadLanguageList() {
        languageList.clear()
        val enabled: Set<String> = ScratchTypeSettings.getInstance().enabledLanguageIds
        LanguageUtil.getFileLanguages().forEach {
            languageList.addItem(it, it.displayName, enabled.contains(it.id))
        }
    }

    private fun loadAliasTable() {
        tableModel.clear()
        ScratchTypeSettings.getInstance().mappings.forEach { (k, v) -> tableModel.addRow(k, v) }
    }

    private fun getCheckedLanguageIds(): Set<String> =
        languageList.checkedItems.mapTo(linkedSetOf()) { it.id }

    private class MappingsTableModel : AbstractTableModel() {
        private val rows = mutableListOf<Array<String>>()
        override fun getRowCount() = rows.size
        override fun getColumnCount() = 2
        override fun getColumnName(column: Int) = if (column == 0) "Language Alias" else "Extension"
        override fun getValueAt(row: Int, column: Int): Any = rows[row][column]
        override fun isCellEditable(row: Int, column: Int) = true
        override fun setValueAt(value: Any, row: Int, column: Int) {
            rows[row][column] = value.toString().trim().lowercase()
            fireTableCellUpdated(row, column)
        }
        fun addRow(alias: String, extension: String) {
            rows += arrayOf(alias, extension)
            fireTableRowsInserted(rows.size - 1, rows.size - 1)
        }
        fun removeRow(row: Int) {
            rows.removeAt(row); fireTableRowsDeleted(row, row)
        }
        fun clear() {
            val n = rows.size
            if (n > 0) { rows.clear(); fireTableRowsDeleted(0, n - 1) }
        }
        fun toMap(): Map<String, String> =
            rows.mapNotNull {
                val a = it[0].trim().lowercase()
                val e = it[1].trim().lowercase()
                if (a.isEmpty() || e.isEmpty()) null else a to e
            }.toMap(LinkedHashMap())
    }
}
