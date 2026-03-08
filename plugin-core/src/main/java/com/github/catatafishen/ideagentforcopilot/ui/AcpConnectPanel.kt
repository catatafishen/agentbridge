package com.github.catatafishen.ideagentforcopilot.ui

import com.github.catatafishen.ideagentforcopilot.psi.PsiBridgeService
import com.github.catatafishen.ideagentforcopilot.services.ActiveAgentManager
import com.github.catatafishen.ideagentforcopilot.services.ActiveAgentManager.AgentType
import com.github.catatafishen.ideagentforcopilot.settings.McpServerSettings
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
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
 * Pre-connection landing panel with a step-by-step "getting started" layout:
 * Step 1 — MCP tool server (start/stop, port, status pill with tool call counter)
 * Step 2 — ACP agent connection (disabled until MCP is running)
 */
class AcpConnectPanel(
    private val project: Project,
    private val onConnect: (AgentType, String?) -> Unit
) : JBPanel<AcpConnectPanel>(BorderLayout()) {

    private val agentManager = ActiveAgentManager.getInstance(project)

    // MCP controls
    private val mcpStartButton = JButton("Start server")
    private val mcpDropdownButton = JButton(AllIcons.General.ArrowDown)
    private val mcpPortField = JBTextField(6)
    private val mcpStatusLabel = JBLabel("Stopped")
    private val toolCallLink = HyperlinkLabel("0 calls")
    private val toolCallEntries = mutableListOf<String>()
    private lateinit var statusPill: JBPanel<JBPanel<*>>

    // ACP controls
    private lateinit var acpSection: JComponent
    private lateinit var agentCombo: ComboBox<AgentType>
    private val customCommandField = JBTextField()
    private val connectButton = JButton("Connect")
    private val connectDropdownButton = JButton(AllIcons.General.ArrowDown)
    private val acpHintLabel = JBLabel("Start the tool server above first").apply {
        foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        font = JBUI.Fonts.smallFont()
        icon = AllIcons.General.Information
        alignmentX = LEFT_ALIGNMENT
        isVisible = false
    }
    private val statusBanner = StatusBanner(project)

    init {
        isOpaque = false

        val scrollContent = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(20, 24)

            add(createMcpSection())
            add(Box.createVerticalStrut(JBUI.scale(12)))
            add(createSeparator())
            add(Box.createVerticalStrut(JBUI.scale(16)))
            add(createAcpSection().also { acpSection = it })
            add(Box.createVerticalGlue())
        }

        val scrollPane = JBScrollPane(scrollContent).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }

        add(scrollPane, BorderLayout.CENTER)

        subscribeToBridgeEvents()
        refreshMcpState()
    }

    // ── Section builders ──

    private fun createMcpSection(): JComponent {
        val section = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
        }

        section.add(createSectionHeader(
            step = 1,
            title = "Start tool server",
            description = "MCP bridge \u2014 exposes IDE tools to agents"
        ))
        section.add(Box.createVerticalStrut(JBUI.scale(12)))

        // Status pill
        section.add(createStatusPill())
        section.add(Box.createVerticalStrut(JBUI.scale(12)))

        // Port label + field (label above)
        section.add(JBLabel("Port").apply {
            alignmentX = LEFT_ALIGNMENT
            foreground = UIUtil.getLabelDisabledForeground()
        })
        section.add(Box.createVerticalStrut(JBUI.scale(4)))

        val mcpSettings = McpServerSettings.getInstance(project)
        mcpPortField.text = formatPort(mcpSettings.bridgePort)
        mcpPortField.toolTipText = "0 or empty = auto-assign random port"
        mcpPortField.emptyText.text = "0 = auto"
        mcpPortField.alignmentX = LEFT_ALIGNMENT
        mcpPortField.maximumSize = Dimension(JBUI.scale(120), mcpPortField.preferredSize.height)
        section.add(mcpPortField)
        section.add(Box.createVerticalStrut(JBUI.scale(14)))

        // Start/Stop split button
        section.add(createMcpSplitButton())

        return section
    }

    private fun createStatusPill(): JComponent {
        val pill = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = true
            alignmentX = LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(32))
            background = JBColor(
                Color(0xF0, 0xF0, 0xF0),
                Color(0x3C, 0x3C, 0x3C)
            )
            border = CompoundBorder(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(4, 8)
            )
        }

        mcpStatusLabel.icon = AllIcons.General.InspectionsOKEmpty
        mcpStatusLabel.font = JBUI.Fonts.label()
        pill.add(mcpStatusLabel, BorderLayout.WEST)

        toolCallLink.font = JBUI.Fonts.smallFont()
        toolCallLink.setToolTipText("Click to view recent tool calls")
        toolCallLink.addHyperlinkListener { showToolCallPopup() }
        pill.add(toolCallLink, BorderLayout.EAST)

        statusPill = pill
        return pill
    }

    private fun createMcpSplitButton(): JComponent {
        val panel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(32))
        }

        mcpStartButton.icon = AllIcons.Actions.Execute
        mcpStartButton.addActionListener { toggleMcpServer() }
        panel.add(mcpStartButton, BorderLayout.CENTER)

        mcpDropdownButton.preferredSize = JBUI.size(28, 28)
        mcpDropdownButton.isFocusable = false
        mcpDropdownButton.addActionListener { showMcpDropdown() }
        panel.add(mcpDropdownButton, BorderLayout.EAST)

        return panel
    }

    private fun createAcpSection(): JComponent {
        val section = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
        }

        section.add(createSectionHeader(
            step = 2,
            title = "Connect agent",
            description = "ACP \u2014 launch and connect an AI coding agent"
        ))
        section.add(Box.createVerticalStrut(JBUI.scale(8)))

        // Hint shown when MCP is not running
        section.add(acpHintLabel)
        section.add(Box.createVerticalStrut(JBUI.scale(12)))

        // Agent label + combo (label above)
        section.add(JBLabel("Agent").apply {
            alignmentX = LEFT_ALIGNMENT
            foreground = UIUtil.getLabelDisabledForeground()
        })
        section.add(Box.createVerticalStrut(JBUI.scale(4)))

        agentCombo = ComboBox(DefaultComboBoxModel(AgentType.entries.toTypedArray()))
        agentCombo.renderer = SimpleListCellRenderer.create("") { it.displayName() }
        agentCombo.selectedItem = agentManager.activeType
        agentCombo.alignmentX = LEFT_ALIGNMENT
        agentCombo.maximumSize = Dimension(Int.MAX_VALUE, agentCombo.preferredSize.height)
        agentCombo.addActionListener { updateCustomCommandVisibility() }
        section.add(agentCombo)
        section.add(Box.createVerticalStrut(JBUI.scale(10)))

        // Start command label + field (label above, full width)
        section.add(JBLabel("Start command").apply {
            alignmentX = LEFT_ALIGNMENT
            foreground = UIUtil.getLabelDisabledForeground()
        })
        section.add(Box.createVerticalStrut(JBUI.scale(4)))

        customCommandField.text = agentManager.getCustomAcpCommandFor(agentManager.activeType)
        customCommandField.alignmentX = LEFT_ALIGNMENT
        customCommandField.maximumSize = Dimension(Int.MAX_VALUE, customCommandField.preferredSize.height)
        section.add(customCommandField)
        section.add(Box.createVerticalStrut(JBUI.scale(14)))

        // Connect split button
        section.add(createAcpSplitButton())
        section.add(Box.createVerticalStrut(JBUI.scale(8)))

        // Status banner
        statusBanner.alignmentX = LEFT_ALIGNMENT
        section.add(statusBanner)

        updateCustomCommandVisibility()
        return section
    }

    private fun createAcpSplitButton(): JComponent {
        val panel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(32))
        }

        connectButton.icon = AllIcons.Actions.Execute
        connectButton.addActionListener { doConnect() }
        panel.add(connectButton, BorderLayout.CENTER)

        connectDropdownButton.preferredSize = JBUI.size(28, 28)
        connectDropdownButton.isFocusable = false
        connectDropdownButton.addActionListener { showAcpDropdown() }
        panel.add(connectDropdownButton, BorderLayout.EAST)

        return panel
    }

    // ── Shared UI helpers ──

    private fun createSectionHeader(step: Int, title: String, description: String): JComponent {
        val panel = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
        }

        panel.add(JBLabel("\u2460\u2461"[step - 1].toString() + "  " + title).apply {
            font = JBUI.Fonts.label(14f).asBold()
            alignmentX = LEFT_ALIGNMENT
        })
        panel.add(Box.createVerticalStrut(JBUI.scale(2)))
        panel.add(JBLabel(description).apply {
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
            font = JBUI.Fonts.smallFont()
            alignmentX = LEFT_ALIGNMENT
        })

        return panel
    }

    private fun createSeparator(): JComponent {
        return JSeparator(SwingConstants.HORIZONTAL).apply {
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(1))
            alignmentX = LEFT_ALIGNMENT
        }
    }

    // ── Dropdown popups ──

    private fun showMcpDropdown() {
        val mcpSettings = McpServerSettings.getInstance(project)
        val checkbox = JCheckBox("Auto-start on IDE open").apply {
            isSelected = mcpSettings.isBridgeAutoStart
            border = JBUI.Borders.empty(6, 10)
            addActionListener { mcpSettings.setBridgeAutoStart(isSelected) }
        }
        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(checkbox, checkbox)
            .setFocusable(true)
            .setRequestFocus(true)
            .createPopup()
            .showUnderneathOf(mcpDropdownButton)
    }

    private fun showAcpDropdown() {
        val checkbox = JCheckBox("Auto-connect on startup").apply {
            isSelected = agentManager.isAutoConnect
            border = JBUI.Borders.empty(6, 10)
            addActionListener { agentManager.isAutoConnect = isSelected }
        }
        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(checkbox, checkbox)
            .setFocusable(true)
            .setRequestFocus(true)
            .createPopup()
            .showUnderneathOf(connectDropdownButton)
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

        mcpStartButton.text = if (running) "Stop server" else "Start server"
        mcpStartButton.icon = if (running) AllIcons.Actions.Suspend else AllIcons.Actions.Execute

        if (running && port > 0) {
            mcpPortField.text = port.toString()
            mcpPortField.isEnabled = false
            mcpStatusLabel.text = "Running on port $port"
            mcpStatusLabel.icon = AllIcons.General.InspectionsOK
            statusPill.background = JBColor(
                Color(0xE8, 0xF5, 0xE9),
                Color(0x2E, 0x3B, 0x2E)
            )
        } else {
            mcpPortField.isEnabled = true
            if (mcpPortField.text.isBlank() || mcpPortField.text == "0") {
                mcpPortField.text = formatPort(McpServerSettings.getInstance(project).bridgePort)
            }
            mcpStatusLabel.text = "Stopped"
            mcpStatusLabel.icon = AllIcons.General.InspectionsOKEmpty
            statusPill.background = JBColor(
                Color(0xF0, 0xF0, 0xF0),
                Color(0x3C, 0x3C, 0x3C)
            )
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
        setEnabled(acpSection, mcpRunning)
        acpSection.isVisible = true
        acpHintLabel.isVisible = !mcpRunning
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

        toolCallLink.setHyperlinkText("${toolCallEntries.size} calls")
    }

    private fun showToolCallPopup() {
        if (toolCallEntries.isEmpty()) return

        val listModel = DefaultListModel<String>()
        toolCallEntries.forEach { listModel.addElement(it) }

        val list = JList(listModel)
        list.font = JBUI.Fonts.create(Font.MONOSPACED, 11)
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
                font = JBUI.Fonts.create(Font.MONOSPACED, 11)
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
            .showUnderneathOf(toolCallLink)
    }

    private fun formatPort(port: Int): String = if (port == 0) "0" else port.toString()

    // ── ACP state management ──

    private fun updateCustomCommandVisibility() {
        val selectedType = agentCombo.selectedItem as? AgentType ?: return

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
