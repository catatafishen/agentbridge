package com.github.catatafishen.agentbridge.custommcp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import javax.swing.JButton
import javax.swing.ListSelectionModel
import javax.swing.event.ListSelectionEvent
import javax.swing.table.AbstractTableModel

class CustomMcpConfigurable(private val project: Project) :
    BoundConfigurable("Custom MCP Servers"),
    SearchableConfigurable {

    override fun getId(): String = "com.github.catatafishen.agentbridge.custom-mcp"

    private val tableModel = ServerTableModel()
    private val table = JBTable(tableModel).apply {
        rowHeight = JBUI.scale(24)
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        columnModel.getColumn(COL_ENABLED).maxWidth = JBUI.scale(65)
        columnModel.getColumn(COL_ENABLED).minWidth = JBUI.scale(65)
        columnModel.getColumn(COL_NAME).preferredWidth = JBUI.scale(160)
        columnModel.getColumn(COL_URL).preferredWidth = JBUI.scale(320)
    }
    private val instructionsArea = JBTextArea(5, 0).apply {
        lineWrap = true
        wrapStyleWord = true
        isEnabled = false
    }
    private val testButton = JButton("Test Connection").apply {
        isEnabled = false
        addActionListener { testSelectedConnection() }
    }
    private var lastSelectedRow = -1

    init {
        table.selectionModel.addListSelectionListener(::onSelectionChanged)
    }

    override fun createPanel() = panel {
        row {
            comment(
                "Configure external MCP servers (HTTP/SSE). Their tools will be discovered " +
                    "at startup and proxied into the agent's tool list. Use the Instructions " +
                    "field to tell the agent how to use each server's tools."
            )
        }
        row {
            val decorator = ToolbarDecorator.createDecorator(table)
                .setAddAction {
                    saveCurrentInstructions()
                    tableModel.addServer(CustomMcpServerConfig())
                    val newRow = tableModel.rowCount - 1
                    table.setRowSelectionInterval(newRow, newRow)
                    table.scrollRectToVisible(table.getCellRect(newRow, 0, true))
                }
                .setRemoveAction {
                    saveCurrentInstructions()
                    val r = table.selectedRow
                    if (r >= 0) {
                        tableModel.removeServer(r)
                        lastSelectedRow = -1
                        updateInstructionsPanel(-1)
                    }
                }
                .disableUpDownActions()
                .createPanel()
            cell(decorator).align(AlignX.FILL).align(AlignY.FILL).resizableColumn()
        }.resizableRow().layout(RowLayout.PARENT_GRID)
        group("Usage Instructions (appended to selected server's tool descriptions)") {
            row {
                cell(JBScrollPane(instructionsArea))
                    .align(AlignX.FILL).align(AlignY.FILL).resizableColumn()
            }.resizableRow().layout(RowLayout.PARENT_GRID)
            row { cell(testButton) }
        }
        onIsModified {
            saveCurrentInstructions()
            tableModel.servers != CustomMcpSettings.getInstance(project).servers
        }
        onApply {
            saveCurrentInstructions()
            val settings = CustomMcpSettings.getInstance(project)
            settings.servers = tableModel.servers.map(CustomMcpServerConfig::copy)
            ApplicationManager.getApplication().executeOnPooledThread {
                CustomMcpRegistrar.getInstance(project).syncRegistrations()
            }
        }
        onReset {
            saveCurrentInstructions()
            lastSelectedRow = -1
            tableModel.setServers(
                CustomMcpSettings.getInstance(project).servers.map(CustomMcpServerConfig::copy)
            )
            updateInstructionsPanel(-1)
        }
    }

    private fun onSelectionChanged(e: ListSelectionEvent) {
        if (e.valueIsAdjusting) return
        saveCurrentInstructions()
        val row = table.selectedRow
        lastSelectedRow = row
        updateInstructionsPanel(row)
    }

    private fun updateInstructionsPanel(row: Int) {
        val hasSelection = row >= 0 && row < tableModel.rowCount
        instructionsArea.isEnabled = hasSelection
        instructionsArea.text = if (hasSelection) tableModel.getServer(row).instructions else ""
        testButton.isEnabled = hasSelection
    }

    private fun saveCurrentInstructions() {
        if (lastSelectedRow in 0 until tableModel.rowCount) {
            tableModel.getServer(lastSelectedRow).instructions = instructionsArea.text
        }
    }

    private fun testSelectedConnection() {
        val row = table.selectedRow.takeIf { it >= 0 } ?: return
        saveCurrentInstructions()
        val server = tableModel.getServer(row).copy()
        if (server.url.isBlank()) {
            Messages.showErrorDialog(
                project, "Please enter a URL for this server.", "No URL Configured"
            )
            return
        }
        testButton.isEnabled = false
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                CustomMcpClient(server.url).use { client ->
                    client.initialize()
                    val tools = client.listTools()
                    val toolList = tools.joinToString("\n") { "• ${it.name()}" }
                    ApplicationManager.getApplication().invokeLater {
                        testButton.isEnabled = true
                        Messages.showInfoMessage(
                            project,
                            "Connected successfully. Found ${tools.size} tool(s):\n$toolList",
                            "Connection Successful"
                        )
                    }
                }
            } catch (ex: Exception) {
                val msg = ex.message
                ApplicationManager.getApplication().invokeLater {
                    testButton.isEnabled = true
                    Messages.showErrorDialog(
                        project, "Failed to connect:\n$msg", "Connection Failed"
                    )
                }
            }
        }
    }

    private class ServerTableModel : AbstractTableModel() {
        var servers: MutableList<CustomMcpServerConfig> = mutableListOf()
            private set

        fun addServer(s: CustomMcpServerConfig) {
            servers.add(s); fireTableRowsInserted(servers.size - 1, servers.size - 1)
        }

        fun removeServer(row: Int) {
            servers.removeAt(row); fireTableRowsDeleted(row, row)
        }

        fun getServer(row: Int): CustomMcpServerConfig = servers[row]

        fun setServers(list: List<CustomMcpServerConfig>) {
            servers = list.toMutableList(); fireTableDataChanged()
        }

        override fun getRowCount(): Int = servers.size
        override fun getColumnCount(): Int = COL_COUNT
        override fun getColumnName(col: Int): String = COLUMNS[col]
        override fun getColumnClass(col: Int): Class<*> =
            if (col == COL_ENABLED) java.lang.Boolean::class.java else String::class.java
        override fun isCellEditable(row: Int, col: Int): Boolean = true

        override fun getValueAt(row: Int, col: Int): Any? {
            val s = servers[row]
            return when (col) {
                COL_ENABLED -> s.isEnabled
                COL_NAME -> s.name
                COL_URL -> s.url
                else -> null
            }
        }

        override fun setValueAt(value: Any?, row: Int, col: Int) {
            val s = servers[row]
            when (col) {
                COL_ENABLED -> s.isEnabled = value as Boolean
                COL_NAME -> s.name = (value as String).trim()
                COL_URL -> s.url = (value as String).trim()
            }
            fireTableCellUpdated(row, col)
        }

        companion object {
            private val COLUMNS = arrayOf("Enabled", "Name", "URL")
        }
    }

    companion object {
        private const val COL_ENABLED = 0
        private const val COL_NAME = 1
        private const val COL_URL = 2
        private const val COL_COUNT = 3
    }
}
