package com.github.catatafishen.agentbridge.custommcp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ToolbarDecorator
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

class CustomMcpConfigurable(private val project: Project) :
    BoundConfigurable("Custom MCP Servers"),
    SearchableConfigurable {

    override fun getId(): String = "com.github.catatafishen.agentbridge.mcp.customServers"

    private val registrar = CustomMcpRegistrar.getInstance(project)
    private val tableModel = ServerTableModel()
    private val table = JBTable(tableModel).apply {
        rowHeight = JBUI.scale(24)
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        columnModel.getColumn(COL_ENABLED).maxWidth = JBUI.scale(65)
        columnModel.getColumn(COL_ENABLED).minWidth = JBUI.scale(65)
        columnModel.getColumn(COL_DEFAULT).maxWidth = JBUI.scale(65)
        columnModel.getColumn(COL_DEFAULT).minWidth = JBUI.scale(65)
        columnModel.getColumn(COL_NAME).preferredWidth = JBUI.scale(160)
        columnModel.getColumn(COL_TYPE).maxWidth = JBUI.scale(90)
        columnModel.getColumn(COL_TYPE).minWidth = JBUI.scale(90)
        columnModel.getColumn(COL_TARGET).preferredWidth = JBUI.scale(260)
        columnModel.getColumn(COL_STATUS).preferredWidth = JBUI.scale(320)
        emptyText.text = "No custom MCP servers configured"
    }
    private val statusListener = CustomMcpRegistrar.ServerStatusListener { _, _ ->
        ApplicationManager.getApplication().invokeLater { tableModel.fireTableDataChanged() }
    }

    init {
        registrar.addStatusListener(statusListener)
    }

    override fun createPanel() = panel {
        row {
            comment(
                "Add MCP servers through the dialog. Use http or sse for remote MCP endpoints, " +
                    "and stdio for local processes with executable + args. The table below is read-mostly and shows runtime status."
            )
        }
        row {
            val decorator = ToolbarDecorator.createDecorator(table)
                .setAddAction { openAddDialog() }
                .setEditAction { editSelectedServer() }
                .setRemoveAction {
                    val row = table.selectedRow
                    if (row >= 0) tableModel.removeServer(row)
                }
                .disableUpDownActions()
                .createPanel()
            cell(decorator).align(AlignX.FILL).align(AlignY.FILL).resizableColumn()
        }.resizableRow().layout(RowLayout.PARENT_GRID)
        onIsModified {
            tableModel.servers != CustomMcpSettings.getInstance(project).servers
        }
        onApply {
            val settings = CustomMcpSettings.getInstance(project)
            settings.servers = tableModel.servers.map(CustomMcpServerConfig::copy)
            ApplicationManager.getApplication().executeOnPooledThread {
                registrar.syncRegistrations()
                registrar.checkAllStatuses()
            }
        }
        onReset {
            tableModel.setServers(CustomMcpSettings.getInstance(project).servers.map(CustomMcpServerConfig::copy))
            refreshStatuses()
        }
    }

    override fun disposeUIResources() {
        registrar.removeStatusListener(statusListener)
        super<BoundConfigurable>.disposeUIResources()
    }

    override fun reset() {
        super<BoundConfigurable>.reset()
        tableModel.setServers(CustomMcpSettings.getInstance(project).servers.map(CustomMcpServerConfig::copy))
        refreshStatuses()
    }

    private fun openAddDialog() {
        val dialog = ServerDialog(project, null)
        if (dialog.showAndGet()) {
            tableModel.addServer(dialog.toServerConfig())
        }
    }

    private fun editSelectedServer() {
        val row = table.selectedRow.takeIf { it >= 0 } ?: return
        val dialog = ServerDialog(project, tableModel.getServer(row))
        if (dialog.showAndGet()) {
            tableModel.replaceServer(row, dialog.toServerConfig())
        }
    }

    private fun refreshStatuses() {
        ApplicationManager.getApplication().executeOnPooledThread {
            registrar.checkAllStatuses()
        }
    }

    private inner class ServerTableModel : AbstractTableModel() {
        var servers: MutableList<CustomMcpServerConfig> = mutableListOf()
            private set

        fun addServer(server: CustomMcpServerConfig) {
            servers.add(server)
            fireTableRowsInserted(servers.size - 1, servers.size - 1)
        }

        fun replaceServer(row: Int, server: CustomMcpServerConfig) {
            servers[row] = server
            fireTableRowsUpdated(row, row)
        }

        fun removeServer(row: Int) {
            servers.removeAt(row)
            fireTableRowsDeleted(row, row)
        }

        fun getServer(row: Int): CustomMcpServerConfig = servers[row]

        fun setServers(list: List<CustomMcpServerConfig>) {
            servers = list.toMutableList()
            fireTableDataChanged()
        }

        override fun getRowCount(): Int = servers.size

        override fun getColumnCount(): Int = COL_COUNT

        override fun getColumnName(column: Int): String = COLUMNS[column]

        override fun getColumnClass(columnIndex: Int): Class<*> =
            if (columnIndex == COL_ENABLED || columnIndex == COL_DEFAULT) Boolean::class.javaObjectType else String::class.java

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean =
            columnIndex == COL_ENABLED || columnIndex == COL_DEFAULT

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val server = servers[rowIndex]
            return when (columnIndex) {
                COL_ENABLED -> server.isEnabled
                COL_DEFAULT -> server.isDefaultEnabled
                COL_NAME -> server.name
                COL_TYPE -> server.type
                COL_TARGET -> if (server.isStdio) server.getCommandParts().joinToString(" ") else server.effectiveUrl
                COL_STATUS -> statusText(server)
                else -> ""
            }
        }

        override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
            when (columnIndex) {
                COL_ENABLED -> servers[rowIndex].isEnabled = value as Boolean
                COL_DEFAULT -> servers[rowIndex].isDefaultEnabled = value as Boolean
            }
            fireTableCellUpdated(rowIndex, columnIndex)
        }

        private fun statusText(server: CustomMcpServerConfig): String {
            val status = registrar.statusSnapshot[server.id] ?: CustomMcpRegistrar.ServerStatus.UNKNOWN
            return registrar.statusDetailSnapshot[server.id] ?: when {
                status == CustomMcpRegistrar.ServerStatus.UNKNOWN && server.isEnabled && server.isConfigured -> "Checking..."
                else -> when (status) {
                CustomMcpRegistrar.ServerStatus.CONNECTED -> "Running"
                CustomMcpRegistrar.ServerStatus.LOADING -> "Checking..."
                CustomMcpRegistrar.ServerStatus.ERROR -> "Connection failed"
                CustomMcpRegistrar.ServerStatus.DISABLED -> "Disabled"
                CustomMcpRegistrar.ServerStatus.UNKNOWN -> "Unknown"
                }
            }
        }
    }

    companion object {
        private const val COL_ENABLED = 0
        private const val COL_DEFAULT = 1
        private const val COL_NAME = 2
        private const val COL_TYPE = 3
        private const val COL_TARGET = 4
        private const val COL_STATUS = 5
        private const val COL_COUNT = 6
        private val COLUMNS = arrayOf("Enabled", "Default", "Name", "Type", "Target", "Status")
    }
}

private class ServerDialog(project: Project, initialServer: CustomMcpServerConfig?) : DialogWrapper(project, true) {
    private val initial = initialServer?.copy()
    private val environment = initial?.environment?.map { CustomMcpServerConfig.McpEnvVar(it.name, it.value) }?.toMutableList()
        ?: mutableListOf()
    private val nameField = JBTextField(initial?.name ?: "")
    private val typeCombo = ComboBox(arrayOf(
        CustomMcpServerConfig.TYPE_HTTP,
        CustomMcpServerConfig.TYPE_SSE,
        CustomMcpServerConfig.TYPE_STDIO
    )).apply {
        selectedItem = initial?.type ?: CustomMcpServerConfig.TYPE_HTTP
    }
    private val enabledBox = JCheckBox("Active in current session", initial?.isEnabled ?: true)
    private val defaultEnabledBox = JCheckBox("Default active state for new sessions", initial?.isDefaultEnabled ?: true)
    private val urlField = JBTextField(initial?.url ?: "")
    private val headersArea = JBTextArea(initial?.headers?.joinToString("\n") { "${it.name}: ${it.value}" } ?: "", 4, 40).apply {
        lineWrap = true
        wrapStyleWord = false
    }
    private val commandField = JBTextField(initial?.command ?: "")
    private val argsArea = JBTextArea(initial?.args?.joinToString("\n") ?: "", 4, 40).apply {
        lineWrap = true
        wrapStyleWord = false
    }
    private val instructionsArea = JBTextArea(initial?.instructions ?: "", 5, 40).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val cards = JPanel(CardLayout())
    private val envButton = JButton().apply {
        addActionListener {
            val dialog = EnvVarEditorDialog(nameField.text.ifBlank { "MCP Server" }, environment)
            if (dialog.showAndGet()) {
                environment.clear()
                environment.addAll(dialog.result)
                updateEnvButtonLabel()
            }
        }
    }

    init {
        title = if (initial == null) "New MCP Server" else "Edit MCP Server"
        updateEnvButtonLabel()
        cards.add(createRemotePanel(), CARD_REMOTE)
        cards.add(createLocalPanel(), CARD_LOCAL)
        typeCombo.addActionListener { updateTransportCard() }
        updateTransportCard()
        init()
    }

    fun toServerConfig(): CustomMcpServerConfig {
        val server = initialCopy()
        server.name = nameField.text.trim()
        server.isEnabled = enabledBox.isSelected
        server.isDefaultEnabled = defaultEnabledBox.isSelected
        server.instructions = instructionsArea.text
        if (isStdio()) {
            server.type = CustomMcpServerConfig.TYPE_STDIO
            server.setCommandParts(listOf(commandField.text.trim()) + parseLines(argsArea.text))
            server.url = ""
            server.headers = emptyList()
            server.environment = environment.map { CustomMcpServerConfig.McpEnvVar(it.name, it.value) }
        } else {
            server.type = typeCombo.selectedItem as String
            server.url = urlField.text.trim()
            server.setCommandParts(emptyList())
            server.headers = parseHeaders(headersArea.text)
            server.environment = emptyList()
        }
        return server
    }

    override fun doValidate(): ValidationInfo? {
        if (nameField.text.isBlank()) return ValidationInfo("Name is required.", nameField)
        if (isStdio() && commandField.text.isBlank()) return ValidationInfo("Executable is required.", commandField)
        if (!isStdio() && urlField.text.isBlank()) return ValidationInfo("URL is required.", urlField)
        return null
    }

    override fun createCenterPanel(): JComponent {
        val top = JPanel(GridLayout(4, 2, JBUI.scale(8), JBUI.scale(8))).apply {
            add(JLabel("Name"))
            add(nameField)
            add(JLabel("Type"))
            add(typeCombo)
            add(JLabel("Current State"))
            add(enabledBox)
            add(JLabel("Default State"))
            add(defaultEnabledBox)
        }

        val instructionsPanel = JPanel(BorderLayout(JBUI.scale(0), JBUI.scale(4))).apply {
            add(JLabel("Usage Instructions (appended to each tool's description so the AI sees them)"), BorderLayout.NORTH)
            add(JBScrollPane(instructionsArea).apply {
                preferredSize = Dimension(preferredSize.width, JBUI.scale(110))
            }, BorderLayout.CENTER)
        }

        val content = JPanel(BorderLayout(JBUI.scale(0), JBUI.scale(10))).apply {
            border = JBUI.Borders.empty(8)
            add(top, BorderLayout.NORTH)
            add(
                JPanel(BorderLayout(JBUI.scale(0), JBUI.scale(10))).apply {
                    add(cards, BorderLayout.NORTH)
                    add(instructionsPanel, BorderLayout.CENTER)
                },
                BorderLayout.CENTER
            )
        }
        return JBScrollPane(content).apply {
            border = JBUI.Borders.empty()
            preferredSize = JBUI.size(620, 420)
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }
    }

    private fun createRemotePanel(): JPanel = JPanel(BorderLayout(JBUI.scale(0), JBUI.scale(4))).apply {
        val fields = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            gridx = 0
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insetsBottom(8)
        }
        fields.add(JPanel(BorderLayout(JBUI.scale(0), JBUI.scale(4))).apply {
            add(JLabel("URL"), BorderLayout.NORTH)
            add(urlField, BorderLayout.CENTER)
        }, gbc)
        gbc.gridy = 1
        gbc.weighty = 1.0
        gbc.fill = GridBagConstraints.BOTH
        gbc.insets = JBUI.emptyInsets()
        fields.add(JPanel(BorderLayout(JBUI.scale(0), JBUI.scale(4))).apply {
            add(JLabel("Headers"), BorderLayout.NORTH)
            add(JBScrollPane(headersArea).apply {
                preferredSize = Dimension(preferredSize.width, JBUI.scale(90))
            }, BorderLayout.CENTER)
        }, gbc)
        add(fields, BorderLayout.NORTH)
    }

    private fun createLocalPanel(): JPanel = JPanel(BorderLayout(JBUI.scale(0), JBUI.scale(8))).apply {
        val fields = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            gridx = 0
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insetsBottom(8)
        }
        fields.add(JPanel(BorderLayout(JBUI.scale(0), JBUI.scale(4))).apply {
            add(JLabel("Executable"), BorderLayout.NORTH)
            add(commandField, BorderLayout.CENTER)
        }, gbc)
        gbc.gridy = 1
        gbc.weighty = 1.0
        gbc.fill = GridBagConstraints.BOTH
        gbc.insets = JBUI.emptyInsets()
        fields.add(JPanel(BorderLayout(JBUI.scale(0), JBUI.scale(4))).apply {
            add(JLabel("Arguments (one token per line)"), BorderLayout.NORTH)
            add(JBScrollPane(argsArea).apply {
                preferredSize = Dimension(preferredSize.width, JBUI.scale(90))
            }, BorderLayout.CENTER)
        }, gbc)
        add(fields, BorderLayout.NORTH)
        add(envButton, BorderLayout.SOUTH)
    }

    private fun initialCopy(): CustomMcpServerConfig = initial?.copy() ?: CustomMcpServerConfig()

    private fun isStdio(): Boolean = typeCombo.selectedItem == CustomMcpServerConfig.TYPE_STDIO

    private fun updateTransportCard() {
        (cards.layout as CardLayout).show(cards, if (isStdio()) CARD_LOCAL else CARD_REMOTE)
    }

    private fun updateEnvButtonLabel() {
        envButton.text = "Environment Variables... (${environment.size})"
    }

    private fun parseLines(raw: String): List<String> =
        raw.lines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun parseHeaders(raw: String): List<CustomMcpServerConfig.McpHeader> =
        raw.lines().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            val colon = trimmed.indexOf(':')
            if (colon <= 0) return@mapNotNull null
            CustomMcpServerConfig.McpHeader(trimmed.substring(0, colon).trim(), trimmed.substring(colon + 1).trim())
        }

    companion object {
        private const val CARD_REMOTE = "remote"
        private const val CARD_LOCAL = "local"
    }
}

private class EnvVarEditorDialog(
    serverName: String,
    initialVars: List<CustomMcpServerConfig.McpEnvVar>
) : DialogWrapper(true) {
    private val tableModel = EnvVarTableModel()
    private val table = JBTable(tableModel).apply {
        rowHeight = JBUI.scale(24)
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        columnModel.getColumn(0).preferredWidth = JBUI.scale(200)
        columnModel.getColumn(1).preferredWidth = JBUI.scale(300)
    }

    init {
        title = "$serverName - Environment Variables"
        tableModel.setVars(initialVars)
        init()
    }

    val result: List<CustomMcpServerConfig.McpEnvVar>
        get() = tableModel.vars.toList()

    override fun createCenterPanel(): JComponent {
        val decorator = ToolbarDecorator.createDecorator(table)
            .setAddAction { tableModel.addVar() }
            .setRemoveAction {
                val row = table.selectedRow
                if (row >= 0) tableModel.removeVar(row)
            }
            .disableUpDownActions()
            .createPanel()
        decorator.preferredSize = JBUI.size(550, 300)
        return decorator
    }

    private class EnvVarTableModel : AbstractTableModel() {
        val vars: MutableList<CustomMcpServerConfig.McpEnvVar> = mutableListOf()

        fun addVar() {
            vars.add(CustomMcpServerConfig.McpEnvVar("", ""))
            fireTableRowsInserted(vars.size - 1, vars.size - 1)
        }

        fun removeVar(row: Int) {
            vars.removeAt(row)
            fireTableRowsDeleted(row, row)
        }

        fun setVars(list: List<CustomMcpServerConfig.McpEnvVar>) {
            vars.clear()
            vars.addAll(list.map { CustomMcpServerConfig.McpEnvVar(it.name, it.value) })
            fireTableDataChanged()
        }

        override fun getRowCount(): Int = vars.size
        override fun getColumnCount(): Int = 2
        override fun getColumnName(column: Int): String = if (column == 0) "Name" else "Value"
        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = true

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val variable = vars[rowIndex]
            return if (columnIndex == 0) variable.name else variable.value
        }

        override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
            val variable = vars[rowIndex]
            val text = (value as? String) ?: ""
            if (columnIndex == 0) variable.name = text else variable.value = text
            fireTableCellUpdated(rowIndex, columnIndex)
        }
    }
}
