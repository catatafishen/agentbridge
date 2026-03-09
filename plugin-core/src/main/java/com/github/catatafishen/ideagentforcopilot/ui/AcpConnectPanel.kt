package com.github.catatafishen.ideagentforcopilot.ui

import com.github.catatafishen.ideagentforcopilot.psi.PsiBridgeService
import com.github.catatafishen.ideagentforcopilot.services.ActiveAgentManager
import com.github.catatafishen.ideagentforcopilot.services.McpServerControl
import com.github.catatafishen.ideagentforcopilot.settings.McpServerSettings
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
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
    private val onConnect: (String, String?) -> Unit
) : JBPanel<AcpConnectPanel>(BorderLayout()) {

    private val agentManager = ActiveAgentManager.getInstance(project)

    // MCP controls
    private val mcpStartButton = JButton("Start server")
    private val mcpSpinner = AsyncProcessIcon("mcp-toggle").apply {
        isVisible = false
        toolTipText = "Working…"
    }
    private val mcpDropdownButton = JButton(AllIcons.General.ArrowDown)
    private val mcpStatusLabel = JBLabel("Stopped")
    private val mcpUrlCopyButton = JButton(AllIcons.Actions.Copy).apply {
        toolTipText = "Copy MCP URL"
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusable = false
        isVisible = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        preferredSize = JBUI.size(22, 22)
        maximumSize = JBUI.size(22, 22)
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseEntered(e: java.awt.event.MouseEvent) {
                isContentAreaFilled = true
                isBorderPainted = true
            }

            override fun mouseExited(e: java.awt.event.MouseEvent) {
                isContentAreaFilled = false
                isBorderPainted = false
            }
        })
    }
    private var mcpRunningUrl = ""
    private val toolCallLink = HyperlinkLabel("0 calls")
    private val toolCallEntries = mutableListOf<String>()
    private lateinit var statusPill: JBPanel<JBPanel<*>>

    // ACP controls
    private var acpSection: JComponent = JBPanel<JBPanel<*>>()
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

        val maxContentWidth = JBUI.scale(480)

        val innerContent = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(20, 24)
            maximumSize = Dimension(maxContentWidth, Int.MAX_VALUE)

            add(Box.createVerticalGlue())
            add(createMcpSection())
            add(Box.createVerticalGlue())
            add(createSeparator())
            add(Box.createVerticalGlue())
            add(createAcpSection().also { acpSection = it })
            add(Box.createVerticalGlue())
        }

        // Center the inner content horizontally with a max width
        val scrollContent = JBPanel<JBPanel<*>>(GridBagLayout()).apply {
            isOpaque = false
            add(innerContent, GridBagConstraints().apply {
                anchor = GridBagConstraints.NORTH
                fill = GridBagConstraints.VERTICAL
                weightx = 1.0
                weighty = 1.0
            })
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

        section.add(
            createSectionHeader(
                step = 1,
                title = "Start tool server",
                description = "MCP server \u2014 tool server agents and clients can connect to"
            )
        )
        section.add(Box.createVerticalStrut(JBUI.scale(12)))

        // Status pill
        section.add(createStatusPill())
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

        mcpUrlCopyButton.addActionListener {
            val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(java.awt.datatransfer.StringSelection(mcpRunningUrl), null)
        }

        val eastPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
        }
        eastPanel.add(mcpUrlCopyButton, BorderLayout.WEST)
        eastPanel.add(toolCallLink, BorderLayout.EAST)

        toolCallLink.font = JBUI.Fonts.smallFont()
        toolCallLink.setToolTipText("Click to view recent tool calls")
        toolCallLink.addHyperlinkListener { showToolCallPopup() }
        pill.add(eastPanel, BorderLayout.EAST)

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

        val eastPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
            isOpaque = false
            add(mcpSpinner)
            add(mcpDropdownButton)
        }
        mcpDropdownButton.preferredSize = JBUI.size(28, 28)
        mcpDropdownButton.isFocusable = false
        mcpDropdownButton.addActionListener { showMcpDropdown() }
        panel.add(eastPanel, BorderLayout.EAST)

        return panel
    }

    private fun createAcpSection(): JComponent {
        val section = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
        }

        section.add(
            createSectionHeader(
                step = 2,
                title = "Connect agent",
                description = "ACP \u2014 launch and connect an AI coding agent"
            )
        )
        section.add(Box.createVerticalStrut(JBUI.scale(8)))

        // Hint shown when MCP is not running
        section.add(acpHintLabel)
        section.add(Box.createVerticalStrut(JBUI.scale(12)))

        // Connect split button
        section.add(createAcpSplitButton())
        section.add(Box.createVerticalStrut(JBUI.scale(8)))

        // Status banner
        statusBanner.alignmentX = LEFT_ALIGNMENT
        section.add(statusBanner)

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
            font = JBUI.Fonts.label(16f).asBold()
            alignmentX = LEFT_ALIGNMENT
            border = JBUI.Borders.empty(4, 0)
        })
        panel.add(Box.createVerticalStrut(JBUI.scale(4)))
        panel.add(JBLabel(description).apply {
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
            font = JBUI.Fonts.label()
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
            isSelected = mcpSettings.isAutoStart
            border = JBUI.Borders.empty(6, 10)
            addActionListener { mcpSettings.isAutoStart = isSelected }
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
        val mcpServer = McpServerControl.getInstance(project)
        if (mcpServer == null) {
            mcpStartButton.isEnabled = false
            mcpStartButton.text = "Start server"
            mcpStartButton.icon = AllIcons.Actions.Execute
            mcpStatusLabel.text = "Error — McpServerControl service not registered"
            mcpStatusLabel.icon = AllIcons.General.Error
            statusPill.background = JBColor(
                Color(0xFD, 0xE0, 0xE0),
                Color(0x3B, 0x2E, 0x2E)
            )
            updateAcpEnabled(false)
            return
        }

        val running = mcpServer.isRunning
        val port = mcpServer.port

        mcpStartButton.isEnabled = true
        mcpStartButton.text = if (running) "Stop server" else "Start server"
        mcpStartButton.icon = if (running) AllIcons.Actions.Suspend else AllIcons.Actions.Execute

        if (running && port > 0) {
            mcpRunningUrl = "http://127.0.0.1:$port/mcp"
            mcpStatusLabel.text = "Running \u2014 $mcpRunningUrl"
            mcpStatusLabel.icon = AllIcons.General.InspectionsOK
            mcpUrlCopyButton.isVisible = true
            statusPill.background = JBColor(
                Color(0xE8, 0xF5, 0xE9),
                Color(0x2E, 0x3B, 0x2E)
            )
        } else {
            mcpRunningUrl = ""
            mcpStatusLabel.text = "Stopped"
            mcpStatusLabel.icon = AllIcons.General.InspectionsOKEmpty
            mcpUrlCopyButton.isVisible = false
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
        val mcpServer = McpServerControl.getInstance(project)
        if (mcpServer == null) {
            showError("MCP server service is not available — check plugin installation")
            return
        }

        val stopping = mcpServer.isRunning
        mcpStartButton.isEnabled = false
        mcpStartButton.text = if (stopping) "Stopping…" else "Starting…"
        mcpStartButton.icon = null
        mcpDropdownButton.isEnabled = false
        mcpSpinner.isVisible = true
        mcpSpinner.resume()

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                if (stopping) {
                    mcpServer.stop()
                } else {
                    val port = McpServerSettings.getInstance(project).port
                    mcpServer.start(port)
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater { showError("MCP server error: ${e.message}") }
            } finally {
                SwingUtilities.invokeLater {
                    mcpSpinner.suspend()
                    mcpSpinner.isVisible = false
                    mcpDropdownButton.isEnabled = true
                    refreshMcpState()
                }
            }
        }
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

    private fun doConnect() {
        val profileId = agentManager.activeProfileId
        val profile = agentManager.activeProfile
        if (profile == null) {
            statusBanner.showError("No agent profile selected — configure one in Settings.")
            return
        }

        val cmd = agentManager.getCustomAcpCommandFor(profileId)
        if (cmd.isNullOrBlank()) {
            statusBanner.showError("No start command configured for ${profile.displayName} — check Settings.")
            return
        }

        val customCommand = if (cmd != profile.defaultStartCommand) cmd else null

        statusBanner.dismissCurrent()
        connectButton.isEnabled = false
        connectButton.text = "Connecting\u2026"
        onConnect(profileId, customCommand)
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
