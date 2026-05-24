package com.github.catatafishen.agentbridge.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import javax.swing.table.AbstractTableModel

/**
 * Settings page (under MCP) for controlling which diagnostics are surfaced to agents.
 *
 * These settings act as the default filter applied by all diagnostic-emitting tools.
 * Checkboxes control each severity level; a table manages inspection IDs whose
 * diagnostics are always suppressed regardless of severity (e.g. spell checking).
 */
class DiagnosticFilterConfigurable(private val project: Project) :
    BoundConfigurable("Diagnostic Filters"),
    SearchableConfigurable {

    override fun getId(): String = "com.github.catatafishen.agentbridge.mcp.diagnosticFilters"

    private var showErrors = true
    private var showWarnings = true
    private var showWeakWarnings = true
    private var showInformation = true

    private val tableModel = InspectionIdsTableModel()
    private val table = JBTable(tableModel).apply {
        columnModel.getColumn(0).preferredWidth = JBUI.scale(400)
    }

    override fun createPanel() = panel {
        loadFromSettings()

        group("Severities shown to agent") {
            row {
                checkBox("Errors")
                    .bindSelected({ showErrors }, { showErrors = it })
            }
            row {
                checkBox("Warnings")
                    .bindSelected({ showWarnings }, { showWarnings = it })
            }
            row {
                checkBox("Weak warnings")
                    .bindSelected({ showWeakWarnings }, { showWeakWarnings = it })
            }
            row {
                checkBox("Information")
                    .bindSelected({ showInformation }, { showInformation = it })
            }
            row {
                comment(
                    "These settings apply by default to all diagnostics returned by agent tools. " +
                        "Individual tool parameters (e.g. explicit severity arguments) may override them."
                )
            }
        }

        group("Suppressed inspections") {
            row {
                comment(
                    "Inspection IDs listed here are hidden from the agent regardless of severity. " +
                        "<b>SpellCheckingInspection</b> is suppressed by default — spell corrections " +
                        "are one example of what often creates noise in agent diagnostics. " +
                        "The right set depends on your use case; the human in the loop should " +
                        "decide what to include. To find the ID of an inspection, check " +
                        "Settings → Editor → Inspections and hover the inspection name."
                )
            }
            row {
                val decorated = ToolbarDecorator.createDecorator(table)
                    .setAddAction {
                        tableModel.addRow("")
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
            val s = DiagnosticFilterSettings.getInstance(project)
            showErrors != s.isShowErrors
                || showWarnings != s.isShowWarnings
                || showWeakWarnings != s.isShowWeakWarnings
                || showInformation != s.isShowInformation
                || tableModel.toList() != s.suppressedInspectionIds
        }
        onApply {
            val s = DiagnosticFilterSettings.getInstance(project)
            s.setShowErrors(showErrors)
            s.setShowWarnings(showWarnings)
            s.setShowWeakWarnings(showWeakWarnings)
            s.setShowInformation(showInformation)
            s.setSuppressedInspectionIds(tableModel.toList())
        }
        onReset { loadFromSettings() }
    }

    private fun loadFromSettings() {
        val s = DiagnosticFilterSettings.getInstance(project)
        showErrors = s.isShowErrors
        showWarnings = s.isShowWarnings
        showWeakWarnings = s.isShowWeakWarnings
        showInformation = s.isShowInformation
        tableModel.clear()
        s.suppressedInspectionIds.forEach { tableModel.addRow(it) }
    }

    private class InspectionIdsTableModel : AbstractTableModel() {
        private val rows = mutableListOf<String>()

        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = 1
        override fun getColumnName(column: Int): String = "Inspection ID"
        override fun getColumnClass(column: Int): Class<*> = String::class.java
        override fun getValueAt(row: Int, column: Int): Any = rows[row]
        override fun isCellEditable(row: Int, column: Int): Boolean = true

        override fun setValueAt(value: Any, row: Int, column: Int) {
            rows[row] = value.toString().trim()
            fireTableCellUpdated(row, column)
        }

        fun addRow(id: String) {
            rows += id
            fireTableRowsInserted(rows.size - 1, rows.size - 1)
        }

        fun removeRow(row: Int) {
            rows.removeAt(row)
            fireTableRowsDeleted(row, row)
        }

        fun clear() {
            val n = rows.size
            if (n > 0) {
                rows.clear()
                fireTableRowsDeleted(0, n - 1)
            }
        }

        fun toList(): List<String> = rows.filter { it.isNotBlank() }
    }
}
