package com.github.catatafishen.agentbridge.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import javax.swing.table.AbstractTableModel

class ProjectFilesConfigurable :
    BoundConfigurable("Project Files"),
    SearchableConfigurable {

    override fun getId(): String = "com.github.catatafishen.agentbridge.project-files"

    private val tableModel = EntriesTableModel()
    private val table = JBTable(tableModel).apply {
        columnModel.getColumn(0).preferredWidth = JBUI.scale(150)
        columnModel.getColumn(1).preferredWidth = JBUI.scale(300)
        columnModel.getColumn(2).preferredWidth = JBUI.scale(60)
        columnModel.getColumn(3).preferredWidth = JBUI.scale(100)
    }

    override fun createPanel() = panel {
        loadFromSettings()
        row {
            comment(
                "Configure file shortcuts shown in the Project Files toolbar menu. " +
                    "Paths are relative to the project root. Enable <b>Glob</b> to list matching " +
                    "files individually (e.g., <code>.github/agents/*.md</code>)."
            )
        }
        row {
            val decorated = ToolbarDecorator.createDecorator(table)
                .setAddAction {
                    tableModel.addRow("", "", false, "")
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
        onIsModified {
            val current = ProjectFilesSettings.getInstance().entries
            val edited = tableModel.toEntries()
            if (current.size != edited.size) return@onIsModified true
            current.zip(edited).any { (a, b) ->
                a.label != b.label || a.path != b.path || a.isGlob != b.isGlob || a.group != b.group
            }
        }
        onApply { ProjectFilesSettings.getInstance().entries = tableModel.toEntries() }
        onReset { loadFromSettings() }
    }

    private fun loadFromSettings() {
        tableModel.clear()
        ProjectFilesSettings.getInstance().entries.forEach {
            tableModel.addRow(it.label, it.path, it.isGlob, it.group)
        }
    }

    private class EntriesTableModel : AbstractTableModel() {
        private val rows = mutableListOf<Array<Any>>()
        override fun getRowCount() = rows.size
        override fun getColumnCount() = 4
        override fun getColumnName(column: Int) = COLUMNS[column]
        override fun getColumnClass(column: Int): Class<*> =
            if (column == 2) java.lang.Boolean::class.java else String::class.java
        override fun getValueAt(row: Int, column: Int): Any = rows[row][column]
        override fun isCellEditable(row: Int, column: Int) = true
        override fun setValueAt(value: Any, row: Int, column: Int) {
            rows[row][column] = if (column == 2) value else value.toString().trim()
            fireTableCellUpdated(row, column)
        }
        fun addRow(label: String, path: String, isGlob: Boolean, group: String) {
            rows += arrayOf<Any>(label, path, isGlob, group)
            fireTableRowsInserted(rows.size - 1, rows.size - 1)
        }
        fun removeRow(row: Int) { rows.removeAt(row); fireTableRowsDeleted(row, row) }
        fun clear() {
            val n = rows.size
            if (n > 0) { rows.clear(); fireTableRowsDeleted(0, n - 1) }
        }
        fun toEntries(): List<ProjectFilesSettings.FileEntry> = rows.mapNotNull {
            val label = (it[0] as String).trim()
            val path = (it[1] as String).trim()
            val isGlob = it[2] as Boolean
            val group = (it[3] as String).trim()
            if (label.isEmpty() || path.isEmpty()) null
            else ProjectFilesSettings.FileEntry(label, path, isGlob, group)
        }
        companion object { private val COLUMNS = arrayOf("Label", "Path", "Glob", "Group") }
    }
}
