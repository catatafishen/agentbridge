package com.github.catatafishen.ideagentforcopilot.ui

import com.github.catatafishen.ideagentforcopilot.psi.PsiBridgeService
import com.github.catatafishen.ideagentforcopilot.services.ActiveAgentManager
import com.github.catatafishen.ideagentforcopilot.services.ActiveAgentManager.AgentType
import com.github.catatafishen.ideagentforcopilot.settings.McpServerSettings
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.text.SimpleDateFormat
import java.util.*
import javax.swing.*
import javax.swing.border.CompoundBorder

/**
 * Pre-connection landing panel with two centered, framed sections:
 * 1. MCP — tool server (start/stop, port, auto-start, tool call counter)
 * 2. ACP — agent connection (disabled until MCP is running)
 */
class AcpConnectPanel(
    private val project: Project,
    private val onConnect: (AgentType, String?) -> Unit
) : JBPanel<AcpConnectPanel>(GridBagLayout()) {

    private val agentManager = ActiveAgentManager.getInstance(project)

    // MCP controls
    private val mcpStartStopButton = JButton("Start")
    private val mcpPortField = JBTextField(6)
    private val mcpAutoStartCheckbox = JCheckBox("Auto-start on IDE open")
    private val mcpStatusLabel = JBLabel()
    private val toolCallCounter = JButton("0 calls")
    private val toolCallEntries = mutableListOf<String>()

    // ACP controls
    private lateinit var acpBox: JComponent
    private lateinit var agentCombo: JComboBox<AgentType>
    private val customCommandField = JBTextField()
    private val customCommandLabel = JBLabel("Start command:")
    private val connectButton = JButton("Connect")
    private val autoConnectCheckbox = JCheckBox("Auto-connect on startup")
    private val statusBanner = StatusBanner(project)

    init {
        isOpaque = false
        border = JBUI.Borders.empty(24)

        val mcpBox = createMcpBox()
        acpBox = createAcpBox()

        val gbc = GridBagConstraints()
        gbc.gridx = 0
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        gbc.anchor = GridBagConstraints.NORTH
        gbc.insets = JBUI.insets(0, 40, 12, 40)

        gbc.gridy = 0
        add(mcpBox, gbc)

        gbc.gridy = 1
        gbc.weighty = 1.0
        add(acpBox, gbc)

        subscribeToBridgeEvents()
        refreshMcpState()
    }

    private fun createMcpBox(): JComponent {
        val box = createFramedBox("MCP — Tool Server")

        val content = JBPanel<JBPanel<*>>()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.isOpaque = false

        // Status row
        val statusRow = createRow()
        mcpStatusLabel.icon = AllIcons.General.InspectionsOKEmpty
        mcpStatusLabel.text = "Stopped"
        statusRow.add(mcpStatusLabel)
        statusRow.add(Box.createHorizontalStrut(12))
        toolCallCounter.isBorderPainted = false
        toolCallCounter.isContentAreaFilled = false
        toolCallCounter.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolCallCounter.foreground = UIUtil.getLabelInfoForeground()
        toolCallCounter.font = toolCallCounter.font.deriveFont(Font.PLAIN, 11f)
        toolCallCounter.toolTipText = "Click to view recent tool calls"
        toolCallCounter.addActionListener { showToolCallPopup() }
        statusRow.add(toolCallCounter)
        content.add(statusRow)
        content.add(Box.createVerticalStrut(8))

        // Port + Start/Stop row
        val controlRow = createRow()
        controlRow.add(JBLabel("Port:"))
        controlRow.add(Box.createHorizontalStrut(4))
        val mcpSettings = McpServerSettings.getInstance(project)
        mcpPortField.text = formatPort(mcpSettings.bridgePort)
        mcpPortField.toolTipText = "0 or empty = auto-assign random port"
        mcpPortField.maximumSize = Dimension(80, mcpPortField.preferredSize.height)
        controlRow.add(mcpPortField)
        controlRow.add(Box.createHorizontalStrut(8))
        mcpStartStopButton.addActionListener { toggleMcpServer() }
        controlRow.add(mcpStartStopButton)
        content.add(controlRow)
        content.add(Box.createVerticalStrut(6))

        // Auto-start checkbox
        mcpAutoStartCheckbox.isSelected = mcpSettings.isBridgeAutoStart
        mcpAutoStartCheckbox.alignmentX = LEFT_ALIGNMENT
        mcpAutoStartCheckbox.isOpaque = false
        mcpAutoStartCheckbox.addActionListener {
            mcpSettings.setBridgeAutoStart(mcpAutoStartCheckbox.isSelected)
        }
        content.add(mcpAutoStartCheckbox)

        box.add(content, BorderLayout.CENTER)
        return box
    }

    private fun createAcpBox(): JComponent {
        val box = createFramedBox("ACP — Agent Connection")

        val content = JBPanel<JBPanel<*>>()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.isOpaque = false

        // Agent selector row
        val agentRow = createRow()
        agentRow.add(JBLabel("Agent:"))
        agentRow.add(Box.createHorizontalStrut(6))

        agentCombo = JComboBox(AgentType.entries.toTypedArray())
        agentCombo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
            ): Component {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                text = (value as? AgentType)?.displayName() ?: ""
                return this
            }
        }
        agentCombo.selectedItem = agentManager.activeType
        agentCombo.maximumSize = Dimension(250, agentCombo.preferredSize.height)
        agentCombo.addActionListener { updateCustomCommandVisibility() }
        agentRow.add(agentCombo)
        content.add(agentRow)
        content.add(Box.createVerticalStrut(6))

        // Custom command row
        val cmdRow = createRow()
        customCommandLabel.isVisible = true
        cmdRow.add(customCommandLabel)
        cmdRow.add(Box.createHorizontalStrut(6))
        customCommandField.text = agentManager.getCustomAcpCommandFor(agentManager.activeType)
        customCommandField.maximumSize = Dimension(400, customCommandField.preferredSize.height)
        cmdRow.add(customCommandField)
        content.add(cmdRow)
        content.add(Box.createVerticalStrut(10))

        // Connect button + auto-connect row
        val actionRow = createRow()
        connectButton.addActionListener { doConnect() }
        actionRow.add(connectButton)
        actionRow.add(Box.createHorizontalStrut(12))
        autoConnectCheckbox.isSelected = agentManager.isAutoConnect
        autoConnectCheckbox.isOpaque = false
        autoConnectCheckbox.addActionListener {
            agentManager.isAutoConnect = autoConnectCheckbox.isSelected
        }
        actionRow.add(autoConnectCheckbox)
        content.add(actionRow)
        content.add(Box.createVerticalStrut(6))

        // Status banner
        statusBanner.alignmentX = LEFT_ALIGNMENT
        content.add(statusBanner)

        box.add(content, BorderLayout.CENTER)

        updateCustomCommandVisibility()
        return box
    }

    /** Creates a row panel with left-aligned FlowLayout. */
    private fun createRow(): JBPanel<JBPanel<*>> {
        val row = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 0, 0))
        row.isOpaque = false
        row.alignmentX = LEFT_ALIGNMENT
        return row
    }

    /** Creates a bordered, titled box with consistent styling. */
    private fun createFramedBox(title: String): JBPanel<JBPanel<*>> {
        val box = JBPanel<JBPanel<*>>(BorderLayout())
        box.isOpaque = false
        box.alignmentX = LEFT_ALIGNMENT

        val titleBorder = BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(JBColor.border(), 1, true),
            title
        )
        titleBorder.titleFont = UIUtil.getLabelFont().deriveFont(Font.BOLD, 13f)
        box.border = CompoundBorder(titleBorder, JBUI.Borders.empty(8, 12))

        return box
    }

    // ── MCP state management ──

    private fun subscribeToBridgeEvents() {
        val connection = project.messageBus.connect()

        connection.subscribe(
            PsiBridgeService.STATUS_TOPIC,
            PsiBridgeService.StatusListener { _ ->
                SwingUtilities.invokeLater { refreshMcpState() }
            })

        connection.subscribe(
            PsiBridgeService.TOOL_CALL_TOPIC,
            PsiBridgeService.ToolCallListener { toolName, durationMs, success ->
                SwingUtilities.invokeLater { addToolCallEntry(toolName, durationMs, success) }
            })
    }

    private fun refreshMcpState() {
        val bridge = PsiBridgeService.getInstance(project)
        val running = bridge.isRunning
        val port = bridge.port

        mcpStartStopButton.text = if (running) "Stop" else "Start"
        if (running && port > 0) {
            mcpPortField.text = port.toString()
            mcpPortField.isEnabled = false
            mcpStatusLabel.text = "Running on port $port"
            mcpStatusLabel.icon = AllIcons.General.InspectionsOK
        } else {
            mcpPortField.isEnabled = true
            if (mcpPortField.text.isBlank() || mcpPortField.text == "0") {
                mcpPortField.text = formatPort(McpServerSettings.getInstance(project).bridgePort)
            }
            mcpStatusLabel.text = "Stopped"
            mcpStatusLabel.icon = AllIcons.General.InspectionsOKEmpty
        }

        updateAcpEnabled(running)
    }

    private fun updateAcpEnabled(mcpRunning: Boolean) {
        fun setEnabled(component: Component, enabled: Boolean) {
            component.isEnabled = enabled
            if (component is Container) {
                for (child in component.components) {
                    setEnabled(child, enabled)
                }
            }
        }
        setEnabled(acpBox, mcpRunning)
        // Keep the box border visible even when disabled
        acpBox.isVisible = true
    }

    private fun toggleMcpServer() {
        val bridge = PsiBridgeService.getInstance(project)
        if (bridge.isRunning) {
            bridge.stop()
        } else {
            val portText = mcpPortField.text.trim()
            val port = portText.toIntOrNull() ?: 0
            McpServerSettings.getInstance(project).setBridgePort(port)
            bridge.start(port)
        }
        refreshMcpState()
    }

    private fun addToolCallEntry(toolName: String, durationMs: Long, success: Boolean) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val status = if (success) "\u2713" else "\u2717"
        val entry = "$time  $status  $toolName  (${durationMs}ms)"

        toolCallEntries.add(entry)
        while (toolCallEntries.size > 200) {
            toolCallEntries.removeAt(0)
        }

        toolCallCounter.text = "${toolCallEntries.size} calls"
    }

    private fun showToolCallPopup() {
        if (toolCallEntries.isEmpty()) return

        val listModel = DefaultListModel<String>()
        toolCallEntries.forEach { listModel.addElement(it) }

        val list = JList(listModel)
        list.font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        list.visibleRowCount = minOf(toolCallEntries.size, 15)
        list.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                jList: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
            ): Component {
                super.getListCellRendererComponent(jList, value, index, isSelected, cellHasFocus)
                val text = value?.toString() ?: ""
                if (!isSelected && text.contains("  \u2717  ")) {
                    foreground = JBColor.RED
                }
                font = Font(Font.MONOSPACED, Font.PLAIN, 11)
                return this
            }
        }

        val scrollPane = JBScrollPane(list)
        scrollPane.preferredSize = Dimension(JBUI.scale(420), JBUI.scale(250))

        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(scrollPane, list)
            .setTitle("Recent Tool Calls")
            .setResizable(true)
            .setMovable(true)
            .setFocusable(true)
            .createPopup()
            .showUnderneathOf(toolCallCounter)
    }

    private fun formatPort(port: Int): String = if (port == 0) "0" else port.toString()

    // ── ACP state management ──

    private fun updateCustomCommandVisibility() {
        val selectedType = agentCombo.selectedItem as? AgentType ?: return
        customCommandLabel.isVisible = true
        customCommandField.isVisible = true

        if (!customCommandField.hasFocus()) {
            val stored = agentManager.getCustomAcpCommandFor(selectedType)
            customCommandField.text = stored
        }

        customCommandField.emptyText.text = if (selectedType == AgentType.GENERIC) {
            "e.g., my-agent --acp --stdio"
        } else {
            selectedType.defaultStartCommand()
        }
    }

    private fun doConnect() {
        val selectedType = agentCombo.selectedItem as AgentType
        val cmd = customCommandField.text.trim()

        if (cmd.isEmpty()) {
            statusBanner.showError("Enter a start command for the agent.")
            return
        }

        agentManager.setCustomAcpCommandFor(selectedType, cmd)

        val customCommand = if (selectedType == AgentType.GENERIC || cmd != selectedType.defaultStartCommand()) {
            cmd
        } else {
            null
        }

        statusBanner.dismissCurrent()
        connectButton.isEnabled = false
        connectButton.text = "Connecting\u2026"
        onConnect(selectedType, customCommand)
    }

    // ── Public API for AgenticCopilotToolWindowContent ──

    fun showError(message: String) {
        SwingUtilities.invokeLater {
            connectButton.isEnabled = true
            connectButton.text = "Connect"
            statusBanner.showError(message)
        }
    }

    fun resetConnectButton() {
        SwingUtilities.invokeLater {
            connectButton.isEnabled = true
            connectButton.text = "Connect"
        }
    }

    fun refreshMcpStatus() {
        refreshMcpState()
    }
}
