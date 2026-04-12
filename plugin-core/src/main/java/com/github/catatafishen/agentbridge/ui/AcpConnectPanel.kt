package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.BuildInfo
import com.github.catatafishen.agentbridge.psi.PsiBridgeService
import com.github.catatafishen.agentbridge.services.*
import com.github.catatafishen.agentbridge.session.SessionSwitchService
import com.github.catatafishen.agentbridge.session.v2.SessionStoreV2
import com.github.catatafishen.agentbridge.settings.McpServerSettings
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.*
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.swing.*

/**
 * Pre-connection landing panel with a step-by-step "getting started" layout:
 * Step 1 — MCP tool server (start/stop, port, status pill with tool call counter)
 * Step 2 — ACP agent connection (disabled until MCP is running)
 */
class AcpConnectPanel(
    private val project: Project,
    private val onConnect: (String, String?) -> Unit
) : JBPanel<AcpConnectPanel>(BorderLayout()) {

    companion object {
        private const val START_SERVER = "Start server"
        private const val STOP_SERVER = "Stop server"
        private val SESSION_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    }

    /** Item model for the session resume dropdown. */
    sealed class SessionChoice(val displayText: String) {
        /** Resume the most recent session (default). */
        class Latest(val record: SessionStoreV2.SessionRecord) :
            SessionChoice(formatSession(record))

        /** Start a fresh session without resuming. */
        data object None : SessionChoice("None (fresh session)")

        /** Resume a specific older session. */
        class Older(val record: SessionStoreV2.SessionRecord) :
            SessionChoice(formatSession(record))

        override fun toString(): String = displayText

        companion object {
            private fun formatSession(record: SessionStoreV2.SessionRecord): String {
                val date = SESSION_DATE_FORMAT.format(Date(record.updatedAt))
                val label = record.name.ifEmpty { record.agent }
                val base = "$date — $label"
                return if (record.turnCount > 0) "$base (${record.turnCount} turns)" else base
            }
        }
    }

    private val agentManager = ActiveAgentManager.getInstance(project)
    private val authService = AuthLoginService(project)
    private var inlineAuthProcess: Process? = null

    // MCP controls
    private val mcpStartButton = JButton(START_SERVER)
    private val mcpSpinner = AsyncProcessIcon("mcp-toggle").apply {
        isVisible = false
        toolTipText = "Working…"
    }
    private val mcpAutoStartCheckbox = JBCheckBox("Auto-start on IDE open")
    private val mcpStatusLabel = JBLabel("Stopped")
    private val mcpUrlCopyButton = InplaceButton("Copy MCP URL", AllIcons.Actions.Copy) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(mcpRunningUrl), null)
    }.apply {
        isVisible = false
        accessibleContext.accessibleName = "Copy MCP URL"
    }
    private var mcpRunningUrl = ""
    private val toolCallLink = HyperlinkLabel("0 calls")
    private val toolCallEntries = mutableListOf<String>()
    private lateinit var statusPill: JBPanel<JBPanel<*>>

    // ACP controls
    private var acpSection: JComponent = JBPanel<JBPanel<*>>()
    private val profileCombo = ComboBox<AgentProfile>()
    private val sessionCombo = ComboBox<SessionChoice>()
    private val connectButton = JButton("Connect")
    private val acpAutoConnectCheckbox = JBCheckBox("Auto-connect on startup")
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

        val versionLabel = JBLabel(
            "AgentBridge ${BuildInfo.getVersion()}"
        ).apply {
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
            font = JBUI.Fonts.smallFont()
            horizontalAlignment = SwingConstants.CENTER
            border = JBUI.Borders.empty(4, 0, 8, 0)
        }
        add(versionLabel, BorderLayout.SOUTH)

        subscribeToBridgeEvents()
        refreshMcpState()

        // If autostart is enabled and the server hasn't started yet, show a loading indicator
        // so the user can't click "Start server" while it's already being auto-started.
        val mcpSettings = McpServerSettings.getInstance(project)
        val mcpServerControl = McpServerControl.getInstance(project)
        if (mcpSettings.isAutoStart && mcpServerControl != null && !mcpServerControl.isRunning) {
            showAutoStartLoading()
        }
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

        // Start/Stop button
        section.add(createMcpButton())
        section.add(Box.createVerticalStrut(JBUI.scale(6)))

        // Auto-start option
        mcpAutoStartCheckbox.apply {
            isOpaque = false
            font = JBUI.Fonts.smallFont()
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
            alignmentX = LEFT_ALIGNMENT
            isSelected = McpServerSettings.getInstance(project).isAutoStart
            addActionListener { McpServerSettings.getInstance(project).isAutoStart = isSelected }
        }
        section.add(mcpAutoStartCheckbox)

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
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(4, 8)
            )
        }

        mcpStatusLabel.icon = AllIcons.General.InspectionsOKEmpty
        mcpStatusLabel.font = JBUI.Fonts.label()
        pill.add(mcpStatusLabel, BorderLayout.WEST)

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

    private fun createMcpButton(): JComponent {
        val panel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(32))
        }

        mcpStartButton.icon = AllIcons.Actions.Execute
        mcpStartButton.addActionListener { toggleMcpServer() }
        panel.add(mcpStartButton, BorderLayout.CENTER)

        val eastPanel = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(Box.createHorizontalStrut(JBUI.scale(4)))
            add(mcpSpinner)
        }
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

        // Agent profile selector
        section.add(createProfileSelector())
        section.add(Box.createVerticalStrut(JBUI.scale(8)))

        // Session resume selector
        section.add(createSessionSelector())
        section.add(Box.createVerticalStrut(JBUI.scale(8)))

        // Hint shown when MCP is not running
        section.add(acpHintLabel)
        section.add(Box.createVerticalStrut(JBUI.scale(12)))

        // Connect split button
        section.add(createAcpButton())
        section.add(Box.createVerticalStrut(JBUI.scale(6)))

        // Auto-connect option
        acpAutoConnectCheckbox.apply {
            isOpaque = false
            font = JBUI.Fonts.smallFont()
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
            alignmentX = LEFT_ALIGNMENT
            isSelected = agentManager.isAutoConnect
            addActionListener { agentManager.isAutoConnect = isSelected }
        }
        section.add(acpAutoConnectCheckbox)
        section.add(Box.createVerticalStrut(JBUI.scale(8)))

        // Status banner
        statusBanner.alignmentX = LEFT_ALIGNMENT
        section.add(statusBanner)

        return section
    }

    private fun createAcpButton(): JComponent {
        val panel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(32))
        }

        connectButton.icon = AllIcons.Actions.Execute
        connectButton.addActionListener { doConnect() }
        panel.add(connectButton, BorderLayout.CENTER)

        return panel
    }

    private fun createProfileSelector(): JComponent {
        refreshProfileCombo()
        profileCombo.renderer = SimpleListCellRenderer.create { label, value, _ ->
            val name = value?.displayName ?: ""
            label.text = if (value?.isExperimental == true) "$name (experimental)" else name
        }
        profileCombo.alignmentX = LEFT_ALIGNMENT
        profileCombo.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(32))
        return profileCombo
    }

    private fun refreshProfileCombo() {
        val profiles = agentManager.availableProfiles.toList()
        val activeId = agentManager.activeProfileId
        profileCombo.removeAllItems()
        for (p in profiles) {
            profileCombo.addItem(p)
        }
        val active = profiles.find { it.id == activeId }
        if (active != null) {
            profileCombo.selectedItem = active
        }
    }

    private fun createSessionSelector(): JComponent {
        val panel = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
        }
        panel.add(JBLabel("Resume session").apply {
            font = JBUI.Fonts.smallFont()
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
            alignmentX = LEFT_ALIGNMENT
        })
        panel.add(Box.createVerticalStrut(JBUI.scale(4)))

        refreshSessionCombo()
        sessionCombo.renderer = SimpleListCellRenderer.create { label, value, _ ->
            label.text = value?.displayText ?: ""
        }
        sessionCombo.alignmentX = LEFT_ALIGNMENT
        sessionCombo.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(32))
        panel.add(sessionCombo)
        return panel
    }

    private fun refreshSessionCombo() {
        sessionCombo.removeAllItems()
        val sessionStore = SessionStoreV2.getInstance(project)
        val sessions = sessionStore.listSessions(project.basePath)

        if (sessions.isNotEmpty()) {
            sessionCombo.addItem(SessionChoice.Latest(sessions.first()))
        }
        sessionCombo.addItem(SessionChoice.None)
        for (i in 1 until sessions.size) {
            sessionCombo.addItem(SessionChoice.Older(sessions[i]))
        }
    }

    // ── Shared UI helpers ──

    private fun createSectionHeader(step: Int, title: String, description: String): JComponent {
        val panel = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
        }

        panel.add(JBLabel("$step. $title").apply {
            font = JBUI.Fonts.label().deriveFont(JBUI.Fonts.label().size2D * 1.25f).asBold()
            alignmentX = LEFT_ALIGNMENT
            border = JBUI.Borders.empty(12, 0, 4, 0)
        })
        panel.add(Box.createVerticalStrut(JBUI.scale(4)))
        panel.add(JBLabel(description).apply {
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
            font = JBUI.Fonts.label()
            alignmentX = LEFT_ALIGNMENT
        })

        return panel
    }

    // ── MCP state management ──

    private fun subscribeToBridgeEvents() {
        val connection = project.messageBus.connect()

        connection.subscribe(
            McpHttpServer.STATUS_TOPIC,
            McpHttpServer.StatusListener {
                ApplicationManager.getApplication().invokeLater { refreshMcpState() }
            })

        connection.subscribe(
            PsiBridgeService.TOOL_CALL_TOPIC,
            PsiBridgeService.ToolCallListener { toolName, durationMs, success, _, _, _, _ ->
                ApplicationManager.getApplication().invokeLater { addToolCallEntry(toolName, durationMs, success) }
            })
    }

    private fun refreshMcpState() {
        // Always stop the spinner — we're reflecting a settled state
        mcpSpinner.suspend()
        mcpSpinner.isVisible = false

        val mcpServer = McpServerControl.getInstance(project)
        if (mcpServer == null) {
            mcpStartButton.isEnabled = false
            mcpStartButton.text = START_SERVER
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
        mcpStartButton.text = if (running) STOP_SERVER else START_SERVER
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
                ApplicationManager.getApplication().invokeLater { showError("MCP server error: ${e.message}") }
            } finally {
                ApplicationManager.getApplication().invokeLater {
                    refreshMcpState()
                }
            }
        }
    }

    /**
     * Enters a loading state when auto-start is in progress (server not yet running).
     * A 5-second timeout resets the button if STATUS_TOPIC never fires (e.g., startup failed).
     */
    private fun showAutoStartLoading() {
        mcpStartButton.isEnabled = false
        mcpStartButton.text = "Starting\u2026"
        mcpStartButton.icon = null
        mcpSpinner.isVisible = true
        mcpSpinner.resume()
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            ApplicationManager.getApplication().invokeLater {
                if (mcpSpinner.isVisible) {
                    refreshMcpState()
                }
            }
        }, 5, TimeUnit.SECONDS)
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

        val list = JBList(listModel).apply {
            emptyText.text = "No tool calls recorded"
        }
        list.font = JBUI.Fonts.create(Font.MONOSPACED, UIUtil.getLabelFont().size)
        list.visibleRowCount = minOf(toolCallEntries.size, 15)
        list.cellRenderer = SimpleListCellRenderer.create { label, value, _ ->
            label.text = value ?: ""
            if (label.text.contains("  \u2717  ")) {
                label.foreground = JBUI.CurrentTheme.Label.errorForeground()
            }
            label.font = JBUI.Fonts.create(Font.MONOSPACED, UIUtil.getLabelFont().size)
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
        val selectedProfile = profileCombo.selectedItem as? AgentProfile
        if (selectedProfile == null) {
            statusBanner.showError("No agent profile selected — configure one in Settings.")
            return
        }

        val profileId = selectedProfile.id

        val cmd = agentManager.getCustomAcpCommandFor(profileId)
        if (cmd.isBlank()) {
            statusBanner.showError("No start command configured for ${selectedProfile.displayName} — check Settings.")
            return
        }

        val customCommand = if (cmd.isNotBlank() && cmd != selectedProfile.defaultStartCommand) cmd else null

        // Apply session selection: clear or set the resume session ID
        applySessionChoice(profileId)

        statusBanner.dismissCurrent()
        connectButton.isEnabled = false
        connectButton.text = "Connecting\u2026"
        onConnect(profileId, customCommand)
    }

    private fun applySessionChoice(profileId: String) {
        val settings = GenericSettings(profileId, project)
        val sameAgent = agentManager.activeProfileId == profileId
        val sessionSwitch = SessionSwitchService.getInstance(project)

        when (val choice = sessionCombo.selectedItem as? SessionChoice) {
            is SessionChoice.None -> {
                settings.resumeSessionId = null
                // Clear Claude CLI resume state so it starts fresh (no --resume).
                if (sameAgent) sessionSwitch.clearClaudeResumeState()
            }

            is SessionChoice.Latest -> {
                // Re-export the current session so the JSONL has the correct last-prompt.
                // Without this, a stale export (e.g. from a previous IDE session) would be
                // reused and Claude CLI could branch from the wrong message.
                if (sameAgent) sessionSwitch.exportForRestart(profileId)
            }

            is SessionChoice.Older -> {
                switchCurrentSession(choice.record.id)
                settings.resumeSessionId = null
                // Export the older session to Claude CLI format. switchCurrentSession()
                // already updated .current-session-id, so exportForRestart() will pick
                // up the correct session. For agent switches, onAgentSwitch() handles this.
                if (sameAgent) sessionSwitch.exportForRestart(profileId)
            }

            null -> { /* no selection — keep defaults */
            }
        }
    }

    private fun switchCurrentSession(sessionId: String) {
        val basePath = project.basePath ?: return
        val sessionsDir = java.io.File(basePath, ".agent-work/sessions")
        val currentIdFile = java.io.File(sessionsDir, ".current-session-id")
        try {
            sessionsDir.mkdirs()
            currentIdFile.writeText(sessionId)
        } catch (e: Exception) {
            statusBanner.showError("Failed to switch session: ${e.message}")
        }
    }

    // ── Public API for AgenticCopilotToolWindowContent ──

    fun showError(message: String) {
        ApplicationManager.getApplication().invokeLater {
            connectButton.isEnabled = true
            connectButton.text = "Connect"
            val profile = agentManager.activeProfile
            if (authService.isAuthenticationError(message) && profile.isSupportsOAuthSignIn) {
                // Show auth-specific error with Sign In action
                statusBanner.showAuthError(
                    "Not signed in to ${profile.displayName} — click Sign In below.",
                    onSignIn = { startInlineAuth() },
                    onRetry = { doConnect() }
                )
            } else if (authService.isAuthenticationError(message) && profile.terminalSignInCommand != null) {
                // Agent with terminal-based sign-in (e.g. codex login --device-auth)
                val signInCmd = profile.terminalSignInCommand!!
                statusBanner.showAuthError(
                    "Not signed in — click Sign In to run '$signInCmd' in a terminal.",
                    onSignIn = { authService.startTerminalSignIn(signInCmd) },
                    onRetry = { doConnect() }
                )
            } else if (authService.isAuthenticationError(message)) {
                // Non-OAuth agent — show retry hint
                statusBanner.showError("$message — check your credentials and click Connect to retry.")
            } else {
                statusBanner.showError(message)
            }
        }
    }

    private fun startInlineAuth() {
        connectButton.isEnabled = false
        connectButton.text = "Signing in…"
        inlineAuthProcess?.destroy()
        inlineAuthProcess = authService.startInlineAuth(
            onDeviceCode = { info: AuthLoginService.DeviceCodeInfo ->
                statusBanner.showDeviceCode(info.code, info.url)
            },
            onAuthComplete = {
                statusBanner.hideDeviceCode()
                inlineAuthProcess = null
                authService.clearPendingAuthError()
                connectButton.isEnabled = true
                connectButton.text = "Connect"
                statusBanner.showInfo("Signed in — click Connect to continue.")
            },
            onFallback = {
                statusBanner.hideDeviceCode()
                inlineAuthProcess = null
                connectButton.isEnabled = true
                connectButton.text = "Connect"
                authService.startCopilotLogin()
            },
        )
    }

    fun resetConnectButton() {
        ApplicationManager.getApplication().invokeLater {
            connectButton.isEnabled = true
            connectButton.text = "Connect"
            acpAutoConnectCheckbox.isSelected = agentManager.isAutoConnect
            refreshProfileCombo()
            refreshSessionCombo()
        }
    }

    /** Shows "Connecting…" state for auto-connect scenarios. */
    fun showConnecting() {
        ApplicationManager.getApplication().invokeLater {
            statusBanner.dismissCurrent()
            connectButton.isEnabled = false
            connectButton.text = "Connecting\u2026"
        }
    }

    fun refreshMcpStatus() {
        refreshMcpState()
    }
}
