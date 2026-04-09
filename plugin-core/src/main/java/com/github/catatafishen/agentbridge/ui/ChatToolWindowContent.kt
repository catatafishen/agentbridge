package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.acp.model.Model
import com.github.catatafishen.agentbridge.acp.model.SessionUpdate
import com.github.catatafishen.agentbridge.agent.AgentException
import com.github.catatafishen.agentbridge.services.ActiveAgentManager
import com.github.catatafishen.agentbridge.services.ChatWebServer
import com.github.catatafishen.agentbridge.session.SessionSwitchService
import com.github.catatafishen.agentbridge.session.migration.V1ToV2Migrator
import com.github.catatafishen.agentbridge.session.v2.SessionStoreV2
import com.github.catatafishen.agentbridge.settings.ChatHistorySettings
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*

/**
 * Main content for the AgentBridge tool window.
 */
class ChatToolWindowContent(
    private val project: Project,
    private val toolWindow: com.intellij.openapi.wm.ToolWindow
) {

    companion object {
        private val LOG =
            com.intellij.openapi.diagnostic.Logger.getInstance(ChatToolWindowContent::class.java)
        const val MSG_LOADING = "Loading..."
        const val MSG_UNKNOWN_ERROR = "Unknown error"
        const val AGENT_WORK_DIR = ".agent-work"
        const val CARD_CONNECT = "connect"
        const val CARD_CHAT = "chat"

        private val instances = java.util.concurrent.ConcurrentHashMap<Project, ChatToolWindowContent>()

        fun getInstance(project: Project): ChatToolWindowContent? = instances[project]
    }

    private val cardLayout = CardLayout()
    private val mainPanel = JBPanel<JBPanel<*>>(cardLayout)
    private val agentManager = ActiveAgentManager.getInstance(project)
    private lateinit var connectPanel: AcpConnectPanel
    private var chatPanel: JComponent? = null

    // Shared model list (populated from ACP)
    @Volatile
    private var loadedModels: List<Model> = emptyList()
    private var modelLoadGeneration = 0

    // Prompt tab fields
    @Volatile
    private var selectedModelIndex = -1

    @Volatile
    private var modelsStatusText: String? = MSG_LOADING
    private lateinit var controlsToolbar: ActionToolbar
    private var restartSessionGroup: RestartSessionGroup? = null
    private lateinit var promptTextArea: EditorTextField
    private lateinit var shortcutHintPanel: PromptShortcutHintPanel
    private var isSending = false

    @Volatile
    private var pendingNudgeId: String? = null

    @Volatile
    private var pendingNudgeText: String? = null
    private lateinit var processingTimerPanel: ProcessingTimerPanel
    private lateinit var promptOrchestrator: PromptOrchestrator
    private lateinit var pasteToScratchHandler: PasteToScratchHandler

    // Plans tree (populated from ACP plan updates)
    private lateinit var planTreeModel: javax.swing.tree.DefaultTreeModel
    private lateinit var planRoot: javax.swing.tree.DefaultMutableTreeNode
    private lateinit var planDetailsArea: JBTextArea
    private lateinit var sessionInfoLabel: JBLabel

    // Billing/usage management
    private val billing = BillingManager()
    private val authService = AuthLoginService(project)
    private lateinit var consolePanel: ChatPanelApi
    private lateinit var chatConsolePanel: ChatConsolePanel
    private lateinit var responsePanelContainer: JBPanel<JBPanel<*>>
    private var copilotBanner: AuthSetupBanner? = null
    private var statusBanner: StatusBanner? = null
    private var inlineAuthProcess: Process? = null

    private val conversationStore = SessionStoreV2.getInstance(project)
    private val conversationReplayer = ConversationReplayer()

    // Throttled incremental save during streaming (avoid data loss on crash)
    private val saveIntervalMs = 30_000L

    @Volatile
    private var lastIncrementalSaveMs = 0L

    /** Number of entries already persisted to disk for the current session (deferred + panel). */
    @Volatile
    private var persistedEntryCount = 0

    private lateinit var contextManager: PromptContextManager

    init {
        instances[project] = this
        setupUI()
        subscribeToFocusRestoreEvents()
        // Initialise the session store's agent name from the currently active profile.
        conversationStore.setCurrentAgent(agentManager.activeProfile.displayName)
    }

    /**
     * Subscribes to focus restore events published by PsiBridgeService after tool calls complete.
     * Restores keyboard focus to the chat input after files are opened in follow mode.
     */
    private fun subscribeToFocusRestoreEvents() {
        val connection = project.messageBus.connect()
        connection.subscribe(
            com.github.catatafishen.agentbridge.psi.PsiBridgeService.FOCUS_RESTORE_TOPIC,
            com.github.catatafishen.agentbridge.psi.PsiBridgeService.FocusRestoreListener {
                // Only triggered when chat was active before tool call, so just request focus
                // Must run on EDT since EditorTextField.requestFocusInWindow() requires it
                if (::promptTextArea.isInitialized) {
                    ApplicationManager.getApplication().invokeLater {
                        promptTextArea.requestFocusInWindow()
                    }
                }
            }
        )
    }

    /**
     * Wire up the web server callbacks that don't depend on the chat panel being created.
     * Other callbacks (onSendPrompt, onNudge, etc.) are wired in createResponsePanel.
     */
    private fun wireUpWebServerCallbacks() {
        ChatWebServer.getInstance(project)?.also { ws ->
            ws.onConnect = java.util.function.Consumer { profileId ->
                ApplicationManager.getApplication().invokeLater { connectToAgent(profileId, null) }
            }
            ws.onDisconnect = Runnable {
                ApplicationManager.getApplication().invokeLater { disconnectFromAgent() }
            }
            ws.setProfilesJson(buildProfilesJson())
        }
    }

    private fun setupUI() {
        setupTitleBarActions()
        wireUpWebServerCallbacks()

        connectPanel = AcpConnectPanel(project) { profileId, customCommand ->
            connectToAgent(profileId, customCommand)
        }
        mainPanel.add(connectPanel, CARD_CONNECT)

        // Always start on connect panel; auto-connect will proceed automatically
        cardLayout.show(mainPanel, CARD_CONNECT)
        if (agentManager.isAutoConnect) {
            // Show "Connecting…" state and trigger auto-connect flow
            connectPanel.showConnecting()
            loadModelsAsync(
                onSuccess = { models ->
                    loadedModels = models
                    buildAndShowChatPanel()
                    restoreModelSelection(models)
                    statusBanner?.showInfo("Connected to ${agentManager.activeProfile.displayName}")
                },
                onFailure = { error ->
                    connectPanel.showError(error.message ?: "Auto-connect failed")
                }
            )
        }
    }

    private fun setupTitleBarActions() {
        val actions = listOf(
            AutoScrollToggleAction(),
            FollowAgentFilesToggleAction(),
            Separator.create(),
            ProjectFilesDropdownAction(),
            Separator.create(),
            SettingsAction()
        )
        toolWindow.setTitleActions(actions)
    }

    private fun buildAndShowChatPanel() {
        val addSeparatorNow = {
            val ts = java.time.Instant.now().toString()
            consolePanel.setCurrentAgent(
                agentManager.activeProfile.displayName,
                agentManager.activeProfile.id,
                agentManager.activeProfile.clientCssClass
            )
            consolePanel.addSessionSeparator(ts, agentManager.activeProfile.displayName)
            appendNewEntries()
        }
        if (chatPanel == null) {
            val panel = createPromptTab()
            chatPanel = panel
            mainPanel.add(panel, CARD_CHAT)
            archiveConversation()
            // Set agent color immediately so it is queued in pendingJs before the browser loads.
            // Without this there is a race: the browser becomes ready (pendingJs flushed empty) before
            // addSeparatorNow runs, so a message sent in that window shows the default color.
            consolePanel.setCurrentAgent(
                agentManager.activeProfile.displayName,
                agentManager.activeProfile.id,
                agentManager.activeProfile.clientCssClass
            )
            restoreConversation(onComplete = addSeparatorNow)
        } else {
            addSeparatorNow()
        }
        cardLayout.show(mainPanel, CARD_CHAT)
        agentManager.isConnected = true
        restartSessionGroup?.updateIconForActiveAgent()
        updatePromptPlaceholder()
        authService.clearPendingAuthError()  // Clear any auth error from a previous agent
        setSendingState(false)  // Ensure send button is enabled
        notifyWebServerConnected()
    }

    /**
     * Called from AcpConnectPanel when the user clicks Connect.
     * Keeps showing the connect panel spinner until session is fully established,
     * then switches to the chat view.
     */
    private fun connectToAgent(profileId: String, customCommand: String?) {
        if (customCommand != null) {
            agentManager.setCustomAcpCommand(customCommand)
        }
        if (agentManager.activeProfileId != profileId) {
            agentManager.switchAgent(profileId)
        }
        // Always sync the session store agent name on connect — switchAgent only fires
        // the listener when the profile changes, so reconnecting to the same profile
        // after a disconnect would leave currentAgent stale.
        conversationStore.setCurrentAgent(agentManager.activeProfile.displayName)
        if (::promptOrchestrator.isInitialized) resetSessionState()
        // Stay on connect panel while spinner shows "Connecting…"
        // loadModelsAsync triggers agent.start() via getClient() — wait for it to complete
        loadModelsAsync(
            onSuccess = { models ->
                loadedModels = models
                buildAndShowChatPanel()
                restoreModelSelection(models)
                statusBanner?.showInfo("Connected to ${agentManager.activeProfile.displayName}")
            },
            onFailure = { error ->
                connectPanel.showError(error.message ?: "Connection failed")
                val msg = error.message ?: "Connection failed"
                ChatWebServer.getInstance(project)?.broadcastTransient(
                    "connectStatusEl.textContent=${
                        com.google.gson.Gson().toJson(msg)
                    };connectBtn.disabled=false;connectBtn.textContent='Connect';"
                )
            }
        )
    }

    private fun promptPlaceholder(): String {
        val name = agentManager.activeProfile.displayName
        val action = if (isSending) "Nudge" else "Ask"
        return "$action $name..."
    }

    private fun updatePromptPlaceholder() {
        val editor = promptTextArea.editor as? EditorEx ?: return
        editor.setPlaceholder(promptPlaceholder())
        if (::shortcutHintPanel.isInitialized) {
            shortcutHintPanel.setNudgeMode(isSending)
        }
    }

    fun disconnectFromAgent() {
        try {
            agentManager.stop()
        } catch (e: Exception) {
            LOG.warn("Error stopping agent", e)
        }
        agentManager.isConnected = false
        loadedModels = emptyList()
        selectedModelIndex = -1
        modelsStatusText = null
        connectPanel.resetConnectButton()
        connectPanel.refreshMcpStatus()
        cardLayout.show(mainPanel, CARD_CONNECT)
        // Reset toolbar icon to default when disconnecting
        restartSessionGroup?.updateIconForDisconnect()
        notifyWebServerDisconnected()
    }

    // ── Web server state helpers ──────────────────────────────────────────────

    private fun buildModelsJson(): String {
        if (loadedModels.isEmpty()) return "[]"
        return "[" + loadedModels.joinToString(",") { m ->
            "{\"id\":${com.google.gson.Gson().toJson(m.id())},\"name\":${com.google.gson.Gson().toJson(m.name())}}"
        } + "]"
    }

    private fun buildProfilesJson(): String {
        val profiles = agentManager.availableProfiles.toList()
        if (profiles.isEmpty()) return "[]"
        return "[" + profiles.joinToString(",") { p ->
            val g = com.google.gson.Gson()
            "{\"id\":${g.toJson(p.id)},\"name\":${g.toJson(p.displayName)}}"
        } + "]"
    }

    private fun selectModelById(modelId: String) {
        val idx = loadedModels.indexOfFirst { it.id() == modelId }
        if (idx < 0) return
        selectedModelIndex = idx
        agentManager.settings.setSelectedModel(modelId)
        consolePanel.setCurrentModel(modelId)
        val supportsMultiplier = agentManager.client.supportsMultiplier()
        if (supportsMultiplier) {
            val multiplier = getModelMultiplier(modelId)
            if (multiplier != null) consolePanel.setPromptStats(modelId, multiplier)
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val sessionId = promptOrchestrator.currentSessionId
                if (sessionId != null) agentManager.client.setModel(sessionId, modelId)
            } catch (e: Exception) {
                LOG.warn("Failed to set model $modelId via web", e)
            }
        }
    }

    private fun notifyWebServerConnected() {
        val ws = ChatWebServer.getInstance(project) ?: return
        val modelsJson = buildModelsJson()
        val profilesJson = buildProfilesJson()
        ws.setConnected(true)
        ws.setModelsJson(modelsJson)
        ws.setProfilesJson(profilesJson)
        ws.broadcastTransient("handleConnected(${escJsStr(modelsJson)},${escJsStr(profilesJson)})")
    }

    private fun notifyWebServerDisconnected() {
        val ws = ChatWebServer.getInstance(project) ?: return
        val profilesJson = buildProfilesJson()
        ws.setConnected(false)
        ws.setModelsJson("[]")
        ws.setProfilesJson(profilesJson)
        ws.broadcastTransient("handleDisconnected(${escJsStr(profilesJson)})")
    }

    private fun escJsStr(s: String): String = com.google.gson.Gson().toJson(s)

    private fun updateSessionInfo() {
        ApplicationManager.getApplication().invokeLater {
            if (!::sessionInfoLabel.isInitialized) return@invokeLater
            val sid = if (::promptOrchestrator.isInitialized) promptOrchestrator.currentSessionId else null
            if (sid != null) {
                val shortId = sid.take(8) + "..."
                val cwd = project.basePath ?: "unknown"
                sessionInfoLabel.text = "Session: $shortId  ·  $cwd"
                sessionInfoLabel.foreground = JBColor.foreground()
            } else {
                sessionInfoLabel.text = "No active session"
                sessionInfoLabel.foreground = JBUI.CurrentTheme.Label.disabledForeground()
            }
        }
    }

    // Track tool calls for Session tab file correlation
    private val toolCallFiles = mutableMapOf<String, String>() // toolCallId -> file path

    private fun handleClientUpdate(update: SessionUpdate) {
        when (update) {
            is SessionUpdate.ToolCall -> handleToolCall(update)
            is SessionUpdate.ToolCallUpdate -> handleToolCallUpdate(update)
            is SessionUpdate.Plan -> handlePlanUpdate(update)
            else -> Unit
        }
    }

    private fun handleToolCall(update: SessionUpdate.ToolCall) {
        val filePath = update.filePaths().firstOrNull()
        val toolCallId = update.toolCallId()
        if (filePath != null && toolCallId.isNotEmpty()) {
            toolCallFiles[toolCallId] = filePath
        }
    }

    private fun handleToolCallUpdate(update: SessionUpdate.ToolCallUpdate) {
        val status = update.status()
        if (status != SessionUpdate.ToolCallStatus.COMPLETED && status != SessionUpdate.ToolCallStatus.FAILED) return

        val filePath = toolCallFiles[update.toolCallId()]
        if (status == SessionUpdate.ToolCallStatus.COMPLETED && filePath != null) {
            loadCompletedToolFile(filePath)
        }
    }

    private fun loadCompletedToolFile(filePath: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val file = java.io.File(filePath)
                if (file.exists() && file.length() < 100_000) {
                    val content = file.readText()
                    ApplicationManager.getApplication().invokeLater {
                        if (!::planRoot.isInitialized) return@invokeLater
                        val fileNode = FileTreeNode(file.name)
                        planRoot.add(fileNode)
                        planTreeModel.reload()
                        planDetailsArea.text = "${file.name}\n${"—".repeat(40)}\n\n$content"
                    }
                }
            } catch (_: Exception) {
                // Plan file loading is best-effort; errors are non-critical
            }
        }
    }

    private fun handlePlanUpdate(update: SessionUpdate.Plan) {
        val entries = update.entries()
        ApplicationManager.getApplication().invokeLater {
            if (!::planRoot.isInitialized) return@invokeLater
            val toRemove = mutableListOf<javax.swing.tree.DefaultMutableTreeNode>()
            for (i in 0 until planRoot.childCount) {
                val child = planRoot.getChildAt(i) as javax.swing.tree.DefaultMutableTreeNode
                if (child.userObject == "Plan") toRemove.add(child)
            }
            toRemove.forEach { planRoot.remove(it) }

            val planNode = javax.swing.tree.DefaultMutableTreeNode("Plan")
            for (entry in entries) {
                val label =
                    "${entry.content()} [${entry.status()}]${
                        if (entry.priority()?.isNotEmpty() == true) " (${entry.priority()})" else ""
                    }"
                planNode.add(javax.swing.tree.DefaultMutableTreeNode(label))
            }
            planRoot.add(planNode)
            planTreeModel.reload()
        }
    }

    /** Creates a banner for Copilot CLI setup issues (not installed / not authenticated). */
    private fun createCopilotSetupBanner(onFixed: () -> Unit): AuthSetupBanner {
        val banner = AuthSetupBanner(
            pollIntervalDown = 30,
            pollIntervalUp = 60,
            diagnosticsFn = { authService.copilotSetupDiagnostics() },
            onFixed = onFixed,
        ) { diag ->
            val profile = agentManager.activeProfile
            val isCLINotFound = "copilot cli not found" in diag.lowercase() ||
                ("not found" in diag.lowercase() && ("copilot" in diag.lowercase() || "claude" in diag.lowercase()))
            when {
                isCLINotFound && profile.installUrl.isNotEmpty() -> {
                    val url = profile.installUrl
                    updateState(
                        "${profile.displayName} is not installed \u2014 install from $url",
                        showInstall = true,
                    )
                }

                isCLINotFound -> {
                    val cmd = if (System.getProperty("os.name").lowercase().contains("win"))
                        "winget install GitHub.Copilot" else "npm install -g @github/copilot-cli"
                    updateState("Copilot CLI is not installed \u2014 install with: $cmd", showInstall = true)
                }

                !profile.isSupportsOAuthSignIn && profile.terminalSignInCommand != null && authService.isAuthenticationError(
                    diag
                ) ->
                    updateState(
                        "Not signed in to ${profile.displayName} \u2014 click Sign In to authenticate, then Retry.",
                        showSignIn = true,
                    )

                !profile.isSupportsOAuthSignIn && authService.isAuthenticationError(diag) ->
                    updateState(
                        "Not signed in to ${profile.displayName} \u2014 check credentials and click Retry.",
                        showSignIn = false,
                    )

                authService.isAuthenticationError(diag) ->
                    updateState("Not signed in to Copilot \u2014 click Sign In, then click Retry.", showSignIn = true)

                else -> updateState("${profile.displayName} unavailable")
            }
        }
        banner.installHandler = {
            val url = agentManager.activeProfile.installUrl
            if (url.isNotEmpty()) {
                com.intellij.ide.BrowserUtil.browse(url)
            }
        }
        banner.retryHandler = { authService.clearPendingAuthError() }
        banner.signInHandler = {
            val terminalCmd = agentManager.activeProfile.terminalSignInCommand
            if (terminalCmd != null) {
                authService.startTerminalSignIn(terminalCmd)
            } else {
                banner.showSignInPending()
                inlineAuthProcess?.destroy()
                inlineAuthProcess = authService.startInlineAuth(
                    onDeviceCode = { info: AuthLoginService.DeviceCodeInfo ->
                        banner.showDeviceCode(info.code, info.url)
                    },
                    onAuthComplete = {
                        banner.hideDeviceCode()
                        inlineAuthProcess = null
                        authService.clearPendingAuthError()
                        banner.triggerCheck()
                    },
                    onFallback = {
                        banner.hideDeviceCode()
                        inlineAuthProcess = null
                        authService.startCopilotLogin()
                    },
                )
            }
        }
        return banner
    }

    /** Creates a banner for GH CLI setup issues (not installed / not authenticated). */
    private fun createGhSetupBanner(onFixed: () -> Unit): AuthSetupBanner {
        val banner = AuthSetupBanner(
            pollIntervalDown = 30,
            pollIntervalUp = 120,
            diagnosticsFn = { authService.ghSetupDiagnostics(billing) },
            onFixed = onFixed,
        ) { diag ->
            when {
                "not installed" in diag.lowercase() ->
                    updateState(
                        "GitHub CLI (gh) is not installed \u2014 needed for billing info. Install from cli.github.com.",
                        showInstall = true
                    )

                else ->
                    updateState(
                        "Not signed in to GitHub CLI (gh) \u2014 needed for billing info. Click Sign In.",
                        showSignIn = true
                    )
            }
        }
        banner.installHandler = {
            com.intellij.ide.BrowserUtil.browse("https://cli.github.com")
        }
        banner.signInHandler = {
            banner.showSignInPending()
            authService.startGhLogin()
        }
        return banner
    }

    private fun createPromptTab(): JComponent {
        val panel = JBPanel<JBPanel<*>>(BorderLayout())

        val responsePanel = createResponsePanel()
        responsePanelContainer = JBPanel<JBPanel<*>>(BorderLayout())
        responsePanelContainer.add(responsePanel, BorderLayout.CENTER)
        val topPanel = JBPanel<JBPanel<*>>(BorderLayout())
        val northStack = JBPanel<JBPanel<*>>()
        northStack.layout = BoxLayout(northStack, BoxLayout.Y_AXIS)

        fun loadModels() {
            loadModelsAsync(onSuccess = { models -> loadedModels = models })
        }

        copilotBanner = createCopilotSetupBanner {
            authService.pendingAuthError = null
            promptOrchestrator.currentSessionId = null
            loadModels()
        }
        agentManager.addSwitchListener {
            // Update the session store's agent name when the user switches profiles.
            conversationStore.setCurrentAgent(agentManager.activeProfile.displayName)
            // Reset session state so ensureSessionCreated() calls createSession() on the
            // new client. Without this, Claude CLI's cliResumeSessionId property is never
            // consumed and --resume is never passed, so context is lost on switch-back.
            promptOrchestrator.currentSessionId = null
            promptOrchestrator.conversationSummaryInjected = false
            ApplicationManager.getApplication().invokeLater {
                copilotBanner?.triggerCheck()
            }
        }
        val cb = copilotBanner!!
        northStack.add(cb)
        val ghBanner = createGhSetupBanner { billing.loadBillingData() }
        northStack.add(ghBanner)
        val gitBanner = GitWarningBanner(project)
        northStack.add(gitBanner)
        val sb = StatusBanner(project)
        statusBanner = sb
        northStack.add(sb)

        val allBanners = listOf(cb, ghBanner, gitBanner, sb)
        val greyBorder = JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0)
        val updateContainerBorder = java.beans.PropertyChangeListener {
            responsePanelContainer.border = if (allBanners.none { b -> b.isVisible }) greyBorder else null
        }
        allBanners.forEach { it.addPropertyChangeListener("visible", updateContainerBorder) }
        responsePanelContainer.border = if (allBanners.none { it.isVisible }) greyBorder else null

        consolePanel.onStatusMessage = { type, message ->
            when (type) {
                "error" -> sb.showError(message)
                "warning" -> sb.showWarning(message)
                else -> sb.showInfo(message)
            }
        }
        topPanel.add(northStack, BorderLayout.NORTH)
        topPanel.add(responsePanelContainer, BorderLayout.CENTER)

        val inputRow = createInputRow()
        val splitter = com.intellij.ui.OnePixelSplitter(true, "AgentBridge.InputSplitter", 0.78f)
        splitter.firstComponent = topPanel
        splitter.secondComponent = inputRow
        panel.add(splitter, BorderLayout.CENTER)

        val fixedFooter = createFixedFooter()
        panel.add(fixedFooter, BorderLayout.SOUTH)

        billing.loadBillingData()

        return panel
    }

    private fun createFixedFooter(): JBPanel<JBPanel<*>> {
        val footer = JBPanel<JBPanel<*>>()
        footer.layout = BoxLayout(footer, BoxLayout.Y_AXIS)
        footer.border = JBUI.Borders.compound(
            com.intellij.ui.SideBorder(JBColor.border(), com.intellij.ui.SideBorder.TOP),
            JBUI.Borders.empty(0, 0, 2, 0)
        )

        shortcutHintPanel = PromptShortcutHintPanel { dismissShortcutHints() }
        shortcutHintPanel.alignmentX = Component.LEFT_ALIGNMENT
        shortcutHintPanel.isVisible =
            com.github.catatafishen.agentbridge.settings.ChatInputSettings.getInstance().isShowShortcutHints
        footer.add(shortcutHintPanel)

        val controlsRow = createControlsRow()
        controlsRow.alignmentX = Component.LEFT_ALIGNMENT
        footer.add(controlsRow)
        return footer
    }

    private fun createInputRow(): JBPanel<JBPanel<*>> {
        val row = JBPanel<JBPanel<*>>(BorderLayout())
        val minHeight = JBUI.scale(48)
        row.minimumSize = JBUI.size(100, minHeight)
        val editorCustomizations = mutableListOf<com.intellij.ui.EditorCustomization>()
        try {
            val spellCheck = com.intellij.openapi.editor.SpellCheckingEditorCustomizationProvider
                .getInstance().enabledCustomization
            if (spellCheck != null) editorCustomizations.add(spellCheck)
        } catch (_: Exception) {
            // Spellchecker plugin not available
        }
        promptTextArea = com.intellij.ui.EditorTextFieldProvider.getInstance()
            .getEditorField(com.intellij.openapi.fileTypes.PlainTextLanguage.INSTANCE, project, editorCustomizations)
        @Suppress("UsePropertyAccessSyntax") // isOneLineMode getter is protected in EditorTextField
        promptTextArea.setOneLineMode(false)
        // Padding is applied here (not on editor.contentComponent) to avoid interfering with
        // IntelliJ's selection painting, which uses the contentComponent's full bounds.
        promptTextArea.border = JBUI.Borders.empty(4, 6)
        contextManager = PromptContextManager(project, promptTextArea) { text -> appendResponse(text) }

        pasteToScratchHandler = PasteToScratchHandler(project, promptTextArea, contextManager)
        promptOrchestrator = PromptOrchestrator(
            project, agentManager, billing, contextManager, authService,
            { consolePanel }, { copilotBanner }, { statusBanner },
            PromptOrchestratorCallbacks(
                onSendingStateChanged = ::setSendingState,
                appendNewEntries = ::appendNewEntries,
                appendNewEntriesThrottled = ::appendNewEntriesThrottled,
                notifyIfUnfocused = ::notifyIfUnfocused,
                saveTurnStatistics = ::saveTurnStatistics,
                updateSessionInfo = ::updateSessionInfo,
                requestFocusAfterTurn = { promptTextArea.requestFocusInWindow() },
                onTimerIncrementToolCalls = {
                    if (::processingTimerPanel.isInitialized) processingTimerPanel.incrementToolCalls()
                },
                onTimerRecordUsage = { i, o, c ->
                    if (::processingTimerPanel.isInitialized) processingTimerPanel.recordUsage(i, o, c)
                },
                onTimerSetCodeChangeStats = { a, r ->
                    if (::processingTimerPanel.isInitialized) processingTimerPanel.setCodeChangeStats(a, r)
                },
                onClientUpdate = ::handleClientUpdate,
                sendPromptDirectly = ::sendPromptDirectly,
                restorePromptText = ::restorePromptText,
            )
        )

        setupPromptDragDrop(promptTextArea)
        promptTextArea.addSettingsProvider { editor ->
            setupPromptKeyBindings(editor)
            setupPromptContextMenu(editor)
            editor.setPlaceholder(promptPlaceholder())
            editor.setShowPlaceholderWhenFocused(true)
            editor.settings.isUseSoftWraps =
                com.github.catatafishen.agentbridge.settings.ChatInputSettings.getInstance().isSoftWrapsEnabled
            editor.setBorder(null)
        }

        promptTextArea.addDocumentListener(object : com.intellij.openapi.editor.event.DocumentListener {
            override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                ApplicationManager.getApplication().invokeLater {
                    promptTextArea.revalidate()
                    checkSlashCommandAutocomplete()
                }
            }
        })

        row.border = JBUI.Borders.empty()
        row.add(promptTextArea, BorderLayout.CENTER)

        return row
    }

    private fun onSendStopClicked() {
        val rawText = promptTextArea.text.trim()
        if (consolePanel.hasPendingAskUserRequest()) {
            if (rawText.isNotEmpty()) {
                consolePanel.consumePendingAskUserResponse(rawText)
                promptTextArea.text = ""
            }
            return
        }
        if (isSending) {
            promptOrchestrator.stop()
            setSendingState(false)
            return
        }
        if (rawText.isEmpty()) return
        consolePanel.disableQuickReplies()
        statusBanner?.dismissCurrent()
        setSendingState(true)

        val contextItems = contextManager.collectInlineContextItems()
        val prompt = contextManager.replaceOrcsWithTextRefs(rawText, contextItems)
        val ctxFiles = if (contextItems.isNotEmpty()) {
            contextItems.map { item ->
                Triple(item.name, item.path, if (item.isSelection) item.startLine else 0)
            }
        } else null
        val bubbleHtml = buildBubbleHtml(rawText, contextItems)
        val entryId = consolePanel.addPromptEntry(prompt, ctxFiles, bubbleHtml)
        appendNewEntries()
        promptTextArea.text = ""

        val selectedModelId = resolveSelectedModelId()
        ApplicationManager.getApplication().executeOnPooledThread {
            promptOrchestrator.execute(prompt, contextItems, selectedModelId, rawText, entryId)
        }
    }

    private fun restorePromptText(rawText: String) {
        ApplicationManager.getApplication().invokeLater {
            promptTextArea.text = rawText
        }
    }

    private fun onNudgeClicked() {
        if (!isSending) return
        val rawText = promptTextArea.text.trim()
        if (rawText.isEmpty()) return
        promptTextArea.text = ""

        val existingId = pendingNudgeId
        if (existingId != null) {
            pendingNudgeText = (pendingNudgeText ?: "") + "\n\n" + rawText
            consolePanel.showNudgeBubble(existingId, pendingNudgeText!!)
        } else {
            val id = System.currentTimeMillis().toString()
            pendingNudgeId = id
            pendingNudgeText = rawText
            consolePanel.showNudgeBubble(id, rawText)
        }

        val psiBridge = com.github.catatafishen.agentbridge.psi.PsiBridgeService.getInstance(project)
        psiBridge.setPendingNudge(rawText)
        val resolveId = pendingNudgeId!!
        psiBridge.setOnNudgeConsumed {
            val capturedText = pendingNudgeText
            pendingNudgeId = null
            pendingNudgeText = null
            ApplicationManager.getApplication().invokeLater {
                consolePanel.resolveNudgeBubble(resolveId)
                if (capturedText != null) {
                    consolePanel.addNudgeEntry(resolveId, capturedText)
                    appendNewEntries()
                }
            }
        }
    }

    private fun buildBubbleHtml(rawText: String, items: List<ContextItemData>): String? {
        if (items.isEmpty()) return null
        val sb = StringBuilder()
        var idx = 0
        for (ch in rawText) {
            if (ch == PromptContextManager.ORC && idx < items.size) {
                val item = items[idx++]
                val href =
                    if (item.isSelection && item.startLine > 0) "openfile://${item.path}:${item.startLine}" else "openfile://${item.path}"
                val title =
                    escHtml(if (item.isSelection && item.startLine > 0) "${item.path}:${item.startLine}" else item.path)
                sb.append("<a class='prompt-ctx-chip' href='$href' title='$title'>${escHtml(item.name)}</a>")
            } else {
                appendHtmlChar(ch, sb)
            }
        }
        return sb.toString().trim()
    }

    private fun appendHtmlChar(ch: Char, sb: StringBuilder) {
        when (ch) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '\'' -> sb.append("&#39;")
            '"' -> sb.append("&quot;")
            '\n' -> sb.append("\n")
            else -> sb.append(ch)
        }
    }

    private fun escHtml(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;")

    fun setSoftWrapsEnabled(enabled: Boolean) {
        promptTextArea.editor?.settings?.isUseSoftWraps = enabled
    }

    fun setShortcutHintsVisible() {
        if (!::shortcutHintPanel.isInitialized) return
        shortcutHintPanel.isVisible =
            com.github.catatafishen.agentbridge.settings.ChatInputSettings.getInstance().isShowShortcutHints
    }

    private fun dismissShortcutHints() {
        com.github.catatafishen.agentbridge.settings.ChatInputSettings.getInstance().setShowShortcutHints(false)
        if (::shortcutHintPanel.isInitialized) {
            shortcutHintPanel.isVisible = false
        }
    }

    private fun setSendingState(sending: Boolean) {
        isSending = sending
        ChatWebServer.getInstance(project)?.setAgentRunning(sending)
        if (!sending) {
            // If nudge was never consumed (no tool calls happened), remove bubble and restore text
            val nudgeId = pendingNudgeId
            val nudgeText = pendingNudgeText
            if (nudgeId != null) {
                pendingNudgeId = null
                pendingNudgeText = null
                val psiBridge = com.github.catatafishen.agentbridge.psi.PsiBridgeService.getInstance(project)
                psiBridge.setPendingNudge(null)
                psiBridge.setOnNudgeConsumed(null)
                ApplicationManager.getApplication().invokeLater {
                    consolePanel.removeNudgeBubble(nudgeId)
                    if (nudgeText != null) {
                        promptTextArea.text = nudgeText
                        onSendStopClicked()
                    }
                }
            }
        }
        ApplicationManager.getApplication().invokeLater {
            updatePromptPlaceholder()
            controlsToolbar.updateActionsAsync()
            if (::processingTimerPanel.isInitialized) {
                if (sending) processingTimerPanel.start() else processingTimerPanel.stop()
            }
        }
    }

    private fun createControlsRow(): JBPanel<JBPanel<*>> {
        val row = JBPanel<JBPanel<*>>(BorderLayout())

        val leftGroup = DefaultActionGroup()
        leftGroup.add(SendStopAction())
        leftGroup.addSeparator()
        leftGroup.add(AttachContextDropdownAction())
        leftGroup.addSeparator()
        leftGroup.add(ModelSelectorAction())
        leftGroup.addSeparator()
        restartSessionGroup = RestartSessionGroup()
        leftGroup.add(restartSessionGroup!!)

        controlsToolbar = ActionManager.getInstance().createActionToolbar(
            "AgentControls", leftGroup, true
        )
        controlsToolbar.targetComponent = row
        controlsToolbar.isReservePlaceAutoPopupIcon = false
        controlsToolbar.component.border = JBUI.Borders.empty()

        val rightGroup = DefaultActionGroup()
        rightGroup.add(ProcessingIndicatorAction())
        rightGroup.add(billing.createUsageGraphAction(project))

        val rightToolbar = ActionManager.getInstance().createActionToolbar(
            "AgentRight", rightGroup, true
        )
        rightToolbar.targetComponent = row
        rightToolbar.isReservePlaceAutoPopupIcon = false
        rightToolbar.component.border = JBUI.Borders.empty()

        val wrapper = JBPanel<JBPanel<*>>(GridBagLayout())
        wrapper.isOpaque = false
        wrapper.border = JBUI.Borders.empty(2, 0) // Balance vertical padding
        val c = GridBagConstraints()
        c.fill = GridBagConstraints.VERTICAL
        c.weighty = 1.0
        c.anchor = GridBagConstraints.WEST
        c.weightx = 1.0
        wrapper.add(controlsToolbar.component, c)

        c.weightx = 0.0
        c.anchor = GridBagConstraints.EAST
        wrapper.add(rightToolbar.component, c)

        row.add(wrapper, BorderLayout.CENTER)

        return row
    }

    /** Toolbar action showing a native processing timer while the agent works. */
    private inner class ProcessingIndicatorAction : AnAction("Processing"), CustomComponentAction {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) { /* UI-only toolbar widget */
        }

        override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
            processingTimerPanel = ProcessingTimerPanel(
                supportsMultiplier = { agentManager.client.supportsMultiplier() },
                localSessionRequests = { billing.localSessionRequests }
            )
            com.intellij.openapi.util.Disposer.register(project, processingTimerPanel)
            return processingTimerPanel
        }
    }

    /** Send/Stop toggle action for the toolbar. */
    private inner class SendStopAction : AnAction(
        "Send", "Send prompt (Enter)", AllIcons.Actions.Execute
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            onSendStopClicked()
        }

        override fun update(e: AnActionEvent) {
            val isLoggedIn = authService.pendingAuthError == null
            if (isSending) {
                e.presentation.icon = AllIcons.Actions.Suspend
                e.presentation.text = "Stop"
                e.presentation.description = "Stop"
                e.presentation.isEnabled = true
            } else {
                e.presentation.icon = AllIcons.Actions.Execute
                e.presentation.text = "Send"
                e.presentation.description = if (isLoggedIn) "Send prompt (Enter)" else "Sign in to Copilot first"
                e.presentation.isEnabled = isLoggedIn
            }
        }
    }

    /** Unified attach dropdown: current file, selection, or search project files. */
    private inner class AttachContextDropdownAction : AnAction(
        "Attach Context", "Attach file, selection, or search project files",
        AllIcons.General.Add
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            val inputEvent = e.inputEvent ?: return
            val component = inputEvent.source as? Component ?: return

            val group = DefaultActionGroup()
            group.add(object : AnAction(
                "Current File",
                "Attach the currently open file",
                AllIcons.Actions.AddFile
            ) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) = contextManager.handleAddCurrentFile()
            })
            group.add(object : AnAction(
                "Editor Selection",
                "Attach the selected text",
                AllIcons.Actions.AddMulticaret
            ) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) = contextManager.handleAddSelection()
            })
            group.addSeparator()
            group.add(object : AnAction(
                "Search Project Files\u2026",
                "Search and attach a file from the project",
                AllIcons.Actions.Search
            ) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) = contextManager.openFileSearchPopup()
            })
            group.add(object : AnAction(
                "New Scratch File\u2026",
                "Create a scratch file, open it in the editor, and attach to context",
                AllIcons.FileTypes.Text
            ) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(ev: AnActionEvent) = pasteToScratchHandler.handleCreateScratch()
            })
            val popup = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createActionGroupPopup(
                null, group, e.dataContext,
                com.intellij.openapi.ui.popup.JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false
            )
            popup.showUnderneathOf(component)
        }
    }

    /** Dropdown toolbar button with restart and disconnect options. */
    private inner class RestartSessionGroup : AnAction(
        "Session", "Manage agent session",
        AllIcons.Actions.Restart
    ) {
        init {
            // Listen for agent switches and update icon; also keep session store in sync.
            agentManager.addSwitchListener {
                conversationStore.setCurrentAgent(agentManager.activeProfile.displayName)
                updateIconForActiveAgent()
            }
        }

        fun updateIconForActiveAgent() {
            ApplicationManager.getApplication().invokeLater {
                // This triggers update() to be called on the toolbar button
                controlsToolbar.updateActionsAsync()
            }
        }

        fun updateIconForDisconnect() {
            updateIconForActiveAgent()
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val isConnected = agentManager.isConnected
            val profile = agentManager.activeProfile
            val icon = if (isConnected) {
                AgentIconProvider.getIconForProfile(profile.id)
            } else {
                AgentIconProvider.getDefaultIcon()
            }
            e.presentation.icon = icon
            e.presentation.setText(profile.displayName, true)
        }

        override fun actionPerformed(e: AnActionEvent) {
            val inputEvent = e.inputEvent ?: return
            val component = inputEvent.source as? Component ?: return

            val group = DefaultActionGroup()

            // ── Agent selection ──────────────────────────────────────
            val agents = try {
                agentManager.client.availableAgents
            } catch (_: Exception) {
                emptyList()
            }
            if (agents.isNotEmpty()) {
                group.addSeparator("Agent")
                val currentSlug = try {
                    agentManager.client.currentAgentSlug
                } catch (_: Exception) {
                    null
                }
                agents.forEach { agent ->
                    group.add(object : AnAction(agent.name()) {
                        override fun getActionUpdateThread() = ActionUpdateThread.BGT
                        override fun update(e: AnActionEvent) {
                            e.presentation.icon = if (agent.slug() == currentSlug) AllIcons.Actions.Checked else null
                        }

                        override fun actionPerformed(e: AnActionEvent) {
                            if (agent.slug() != currentSlug) restartWithNewAgent(agent.slug())
                        }
                    })
                }
            }

            // ── Session options (modes, etc.) ────────────────────────
            val options = try {
                agentManager.client.listSessionOptions()
            } catch (_: Exception) {
                emptyList()
            }
            for (option in options) {
                group.addSeparator(option.displayName)
                val stored = agentManager.settings.getSessionOptionValue(option.key)
                val current = stored.ifEmpty { option.initialValue ?: "" }
                for (value in option.values) {
                    group.add(object : AnAction(option.labelFor(value)) {
                        override fun getActionUpdateThread() = ActionUpdateThread.BGT
                        override fun update(e: AnActionEvent) {
                            e.presentation.icon = if (value == current) AllIcons.Actions.Checked else null
                        }

                        override fun actionPerformed(e: AnActionEvent) {
                            agentManager.settings.setSessionOptionValue(option.key, value)
                            val sessionId = promptOrchestrator.currentSessionId ?: return
                            ApplicationManager.getApplication().executeOnPooledThread {
                                try {
                                    agentManager.client.setSessionOption(sessionId, option.key, value)
                                } catch (ex: Exception) {
                                    LOG.warn("Failed to set session option ${option.key}=$value", ex)
                                }
                            }
                        }
                    })
                }
            }

            // ── Session management ───────────────────────────────────
            if (agents.isNotEmpty() || options.isNotEmpty()) group.addSeparator()
            group.add(object : AnAction(
                "Disconnect",
                "Stop the ACP process and return to the connection screen",
                AllIcons.Actions.Cancel
            ) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) = disconnectFromAgent()
            })

            val dangerousActionsGroup = DefaultActionGroup("Session", true)
            dangerousActionsGroup.add(object : AnAction(
                "Restart (Keep History)",
                "Start a new agent session while keeping the conversation visible",
                AllIcons.Actions.Restart
            ) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) = resetSessionKeepingHistory()
            })
            dangerousActionsGroup.add(object : AnAction(
                "Clear and Restart",
                "Clear the conversation and start a completely fresh session",
                AllIcons.Actions.GC
            ) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) = resetSession()
            })
            dangerousActionsGroup.addSeparator()
            dangerousActionsGroup.add(object : AnAction(
                "Logout",
                "Delete authentication tokens for the current agent",
                AllIcons.Actions.Exit
            ) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) {
                    if (authService.logout()) {
                        disconnectFromAgent()
                    }
                }
            })
            group.add(dangerousActionsGroup)

            val popup = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createActionGroupPopup(
                null, group, e.dataContext,
                com.intellij.openapi.ui.popup.JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false
            )
            popup.showUnderneathOf(component)
        }
    }

    /** Toolbar button that opens the plugin settings. */
    private inner class SettingsAction : AnAction(
        "Settings", "Open AgentBridge settings",
        AllIcons.General.Settings
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            com.github.catatafishen.agentbridge.settings.PluginSettingsConfigurable.open(project)
        }
    }

    private inner class FollowAgentFilesToggleAction : ToggleAction(
        "Follow Agent",
        "Open files and highlight regions as the agent reads or edits them",
        AllIcons.Actions.Preview
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT

        override fun isSelected(e: AnActionEvent): Boolean =
            ActiveAgentManager.getFollowAgentFiles(project)

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            ActiveAgentManager.setFollowAgentFiles(project, state)
        }
    }

    @Volatile
    private var autoScrollEnabled = true

    private inner class AutoScrollToggleAction : ToggleAction(
        "Auto-Scroll",
        "Scroll to bottom automatically when new content arrives",
        AllIcons.RunConfigurations.Scroll_down
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT

        override fun isSelected(e: AnActionEvent): Boolean = autoScrollEnabled

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            autoScrollEnabled = state
            chatConsolePanel.setAutoScroll(state)
        }
    }

    /** Open a project-root file in the editor if it exists. */
    private fun openProjectFile(fileName: String) {
        val base = project.basePath ?: return
        val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .findFileByPath("$base/$fileName") ?: return
        com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(vf, true)
    }

    /** Dropdown action for project configuration files. */
    private inner class ProjectFilesDropdownAction : AnAction(
        "Project Files", "Open project configuration files",
        AllIcons.Nodes.Folder
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun actionPerformed(e: AnActionEvent) {
            val inputEvent = e.inputEvent ?: return
            val component = inputEvent.source as? Component ?: return
            showPopup(component)
        }

        private fun showPopup(owner: Component) {
            val group = DefaultActionGroup()
            val base = project.basePath ?: return

            // Shared
            val sharedGroup = DefaultActionGroup()
            addFileAction(sharedGroup, base, "TODO.md", "TODO")
            addFileAction(sharedGroup, base, "AGENTS.md", "AGENTS")
            if (sharedGroup.childrenCount > 0) {
                if (group.childrenCount > 0) group.addSeparator()
                group.addSeparator("Shared")
                group.addAll(*sharedGroup.childActionsOrStubs)
            }

            // Copilot CLI
            val copilotGroup = DefaultActionGroup()
            addGlobSection(copilotGroup, base, ".agent-work/copilot/agents", "*.md")
            addGlobSection(copilotGroup, base, ".agent-work/copilot/skills", "*/SKILL.md")
            addGlobSection(copilotGroup, base, ".agent-work/copilot/instructions", "*.instructions.md")
            if (copilotGroup.childrenCount > 0) {
                if (group.childrenCount > 0) group.addSeparator()
                group.addSeparator("Copilot CLI")
                group.addAll(*copilotGroup.childActionsOrStubs)
            }

            // OpenCode
            val openCodeGroup = DefaultActionGroup()
            addGlobSection(openCodeGroup, base, ".agent-work/opencode/agent", "*.md")
            if (openCodeGroup.childrenCount > 0) {
                if (group.childrenCount > 0) group.addSeparator()
                group.addSeparator("OpenCode")
                group.addAll(*openCodeGroup.childActionsOrStubs)
            }

            // Junie
            val junieGroup = DefaultActionGroup()
            addFileAction(junieGroup, base, ".agent-work/junie/guidelines.md", "guidelines.md")
            addGlobSection(junieGroup, base, ".agent-work/junie/agents", "*.md")
            if (junieGroup.childrenCount > 0) {
                if (group.childrenCount > 0) group.addSeparator()
                group.addSeparator("Junie")
                group.addAll(*junieGroup.childActionsOrStubs)
            }

            // Kiro
            val kiroGroup = DefaultActionGroup()
            addGlobSection(kiroGroup, base, ".agent-work/kiro/agents", "*.json")
            addGlobSection(kiroGroup, base, ".agent-work/kiro/skills", "*/SKILL.md")
            if (kiroGroup.childrenCount > 0) {
                if (group.childrenCount > 0) group.addSeparator()
                group.addSeparator("Kiro")
                group.addAll(*kiroGroup.childActionsOrStubs)
            }

            val popup = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
                .createActionGroupPopup(
                    null, group, com.intellij.openapi.actionSystem.impl.SimpleDataContext.getProjectContext(project),
                    com.intellij.openapi.ui.popup.JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                    true
                )
            popup.showUnderneathOf(owner)
        }

        private fun addFileAction(group: DefaultActionGroup, base: String, path: String, label: String) {
            val file = java.io.File(base, path)
            val exists = file.exists()
            val extension = path.substringAfterLast('.', "")
            val icon = if (exists) {
                fileIconFor(extension)
            } else {
                AllIcons.Actions.IntentionBulbGrey
            }

            group.add(object : AnAction(
                label,
                if (exists) "Open $path" else "Create $path",
                icon
            ) {
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
                override fun actionPerformed(e: AnActionEvent) {
                    if (!exists) {
                        file.parentFile?.mkdirs()
                        file.writeText("")
                    }
                    openProjectFile(path)
                }
            })
        }

        private fun fileIconFor(extension: String): Icon {
            return com.intellij.openapi.fileTypes.FileTypeManager.getInstance()
                .getFileTypeByExtension(extension).icon ?: AllIcons.FileTypes.Text
        }

        private fun addGlobSection(
            group: DefaultActionGroup,
            base: String,
            dirPath: String,
            pattern: String
        ) {
            val dir = java.io.File(base, dirPath)
            val files = findMatchingFiles(dir, pattern)

            files.forEach { file ->
                val relPath = file.relativeTo(java.io.File(base)).path
                val extension = file.extension
                val icon = com.intellij.openapi.fileTypes.FileTypeManager.getInstance()
                    .getFileTypeByExtension(extension).icon

                group.add(object : AnAction(file.nameWithoutExtension, "Open ${file.name}", icon) {
                    override fun getActionUpdateThread() = ActionUpdateThread.BGT
                    override fun actionPerformed(e: AnActionEvent) = openProjectFile(relPath)
                })
            }
        }

        private fun findMatchingFiles(dir: java.io.File, pattern: String): List<java.io.File> {
            if (!dir.exists()) return emptyList()

            return if (pattern.contains("/")) {
                // Pattern like "*/SKILL.md" - search subdirectories
                val parts = pattern.split("/")
                dir.listFiles()?.filter { it.isDirectory }?.flatMap { subDir ->
                    subDir.listFiles { f -> f.isFile && f.name == parts[1] }?.toList() ?: emptyList()
                }?.sortedBy { it.name } ?: emptyList()
            } else {
                // Simple pattern like "*.md"
                val regex = Regex("^" + pattern.replace(".", "\\.").replace("*", ".*") + "$")
                dir.listFiles { f -> f.isFile && regex.matches(f.name) }?.sortedBy { it.name }?.toList() ?: emptyList()
            }
        }
    }

    /** ComboBoxAction for model selection — matches Run panel dropdown style. */
    private inner class ModelSelectorAction : ComboBoxAction() {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun createPopupActionGroup(button: JComponent, context: DataContext): DefaultActionGroup {
            val group = DefaultActionGroup()
            val supportsMultiplier = agentManager.client.supportsMultiplier()
            loadedModels.forEachIndexed { index, model ->
                val cost = if (supportsMultiplier) getModelMultiplier(model.id()) else null
                val label = if (cost != null) "${model.name()}  ($cost)" else model.name()
                group.add(object : AnAction(label) {
                    override fun actionPerformed(e: AnActionEvent) {
                        if (index == selectedModelIndex) return

                        selectedModelIndex = index
                        agentManager.settings.setSelectedModel(model.id())
                        LOG.debug("Model selected: ${model.id()} (index=$index)")
                        ApplicationManager.getApplication().invokeLater {
                            consolePanel.setCurrentModel(model.id())
                            if (supportsMultiplier) {
                                val multiplier = getModelMultiplier(model.id())
                                if (multiplier != null) {
                                    consolePanel.setPromptStats(model.id(), multiplier)
                                }
                            }
                        }
                        ApplicationManager.getApplication().executeOnPooledThread {
                            try {
                                val client = agentManager.client
                                val sessionId = promptOrchestrator.currentSessionId
                                if (sessionId != null) {
                                    client.setModel(sessionId, model.id())
                                    LOG.debug("Model switched to ${model.id()} on session $sessionId")
                                } else {
                                    LOG.debug("No active session; model ${model.id()} will be used on next session")
                                }
                            } catch (ex: Exception) {
                                LOG.warn("Failed to set model ${model.id()} via session/set_model", ex)
                            }
                        }
                    }

                    override fun getActionUpdateThread() = ActionUpdateThread.BGT
                })
            }
            return group
        }

        override fun update(e: AnActionEvent) {
            val text = modelsStatusText
                ?: loadedModels.getOrNull(selectedModelIndex)?.name()
                ?: MSG_LOADING
            e.presentation.text = text
            e.presentation.isEnabled = modelsStatusText == null && loadedModels.isNotEmpty()
            // Hide entirely when models loaded successfully but list is empty
            // (agent uses configOptions for model selection instead)
            e.presentation.isVisible = modelsStatusText != null || loadedModels.isNotEmpty()
        }
    }

    /**
     * Persists the selected agent slug, then silently restarts the agent process so
     * the new [--agent] flag takes effect.  The chat panel stays visible; a session
     * separator is added after reconnection so the history context is preserved.
     */
    private fun restartWithNewAgent(slug: String) {
        agentManager.settings.setSelectedAgent(slug)
        // Stop the running process (the persisted slug will be applied on the next start()
        // call via ActiveAgentManager.start() reading getSettings().getSelectedAgent()).
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                agentManager.stop()
            } catch (ex: Exception) {
                LOG.warn("Error stopping agent during agent switch", ex)
            }
        }
        resetSessionKeepingHistory()
        loadModelsAsync(
            onSuccess = { models ->
                loadedModels = models
                buildAndShowChatPanel()
                restoreModelSelection(models)
                statusBanner?.showInfo("Switched to agent: $slug")
            },
            onFailure = { error ->
                statusBanner?.showError(error.message ?: "Failed to restart with agent $slug")
            }
        )
    }

    private fun createResponsePanel(): JComponent {
        chatConsolePanel = ChatConsolePanel(project)
        consolePanel = chatConsolePanel
        chatConsolePanel.onLoadMoreRequested = ::onLoadMoreHistory
        chatConsolePanel.onCancelNudge = { id ->
            if (pendingNudgeId == id) {
                pendingNudgeId = null
                pendingNudgeText = null
                val psiBridge = com.github.catatafishen.agentbridge.psi.PsiBridgeService.getInstance(project)
                psiBridge.setPendingNudge(null)
                psiBridge.setOnNudgeConsumed(null)
                ApplicationManager.getApplication().invokeLater { consolePanel.removeNudgeBubble(id) }
            }
        }
        chatConsolePanel.onCancelQueuedMessage = { id, text ->
            val psiBridge = com.github.catatafishen.agentbridge.psi.PsiBridgeService.getInstance(project)
            psiBridge.removeQueuedMessage(text)
            ApplicationManager.getApplication().invokeLater { consolePanel.removeQueuedMessage(id) }
        }
        consolePanel.onQuickReply = { text ->
            ApplicationManager.getApplication().invokeLater {
                if (!consolePanel.consumePendingAskUserResponse(text)) {
                    sendQuickReply(text)
                }
            }
        }
        com.intellij.openapi.util.Disposer.register(project, consolePanel)

        ChatWebServer.getInstance(project)?.also { ws ->
            ws.onSendPrompt = { prompt ->
                ApplicationManager.getApplication().invokeLater { sendPromptDirectly(prompt) }
            }
            ws.onQuickReply = { text ->
                ApplicationManager.getApplication().invokeLater {
                    if (!consolePanel.consumePendingAskUserResponse(text)) {
                        sendQuickReply(text)
                    }
                }
            }
            ws.onNudge = { text ->
                ApplicationManager.getApplication().invokeLater {
                    if (isSending) {
                        val existingId = pendingNudgeId
                        if (existingId != null) {
                            pendingNudgeText = (pendingNudgeText ?: "") + "\n\n" + text
                            consolePanel.showNudgeBubble(existingId, pendingNudgeText!!)
                        } else {
                            val id = System.currentTimeMillis().toString()
                            pendingNudgeId = id
                            pendingNudgeText = text
                            consolePanel.showNudgeBubble(id, text)
                        }
                        val psiBridge =
                            com.github.catatafishen.agentbridge.psi.PsiBridgeService.getInstance(project)
                        psiBridge.setPendingNudge(text)
                        val resolveId = pendingNudgeId!!
                        psiBridge.setOnNudgeConsumed {
                            val capturedText = pendingNudgeText
                            pendingNudgeId = null
                            pendingNudgeText = null
                            ApplicationManager.getApplication()
                                .invokeLater {
                                    consolePanel.resolveNudgeBubble(resolveId)
                                    if (capturedText != null) {
                                        consolePanel.addNudgeEntry(resolveId, capturedText)
                                        appendNewEntries()
                                    }
                                }
                        }
                    }
                }
            }
            ws.onStop = {
                ApplicationManager.getApplication().invokeLater {
                    if (isSending) {
                        promptOrchestrator.stop()
                        setSendingState(false)
                    }
                }
            }
            ws.onCancelNudge = { id ->
                ApplicationManager.getApplication().invokeLater {
                    chatConsolePanel.onCancelNudge?.invoke(id)
                }
            }
            ws.onPermissionResponse = java.util.function.Consumer { data ->
                ApplicationManager.getApplication().invokeLater {
                    chatConsolePanel.handleWebPermissionResponse(data)
                }
            }
            ws.onSelectModel = java.util.function.Consumer { modelId ->
                ApplicationManager.getApplication().invokeLater { selectModelById(modelId) }
            }
        }

        return consolePanel.component
    }

    private fun appendResponse(text: String) {
        consolePanel.appendText(text)
    }

    private fun setupPromptKeyBindings(editor: EditorEx) {
        val contentComponent = editor.contentComponent
        registerEnterSend(contentComponent)
        registerShiftEnterNewLine(editor, contentComponent)
        registerCtrlEnterNudge(contentComponent)
        registerCtrlShiftEnterQueue(contentComponent)
        registerPasteIntercept(editor, contentComponent)
        registerTriggerCharDetection(editor)
    }

    private fun registerEnterSend(contentComponent: JComponent) {
        object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) {
                if (promptTextArea.text.isBlank() || authService.pendingAuthError != null) return
                when {
                    consolePanel.hasPendingAskUserRequest() -> onSendStopClicked()
                    isSending -> onNudgeClicked()
                    else -> onSendStopClicked()
                }
            }
        }.registerCustomShortcutSet(
            PromptShortcutAction.resolveShortcutSet(
                PromptShortcutAction.SEND_ID,
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0)
            ),
            contentComponent
        )
    }

    private fun registerShiftEnterNewLine(editor: EditorEx, contentComponent: JComponent) {
        object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) {
                val offset = editor.caretModel.offset
                com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                    editor.document.insertString(offset, "\n")
                }
                editor.caretModel.moveToOffset(offset + 1)
            }
        }.registerCustomShortcutSet(
            PromptShortcutAction.resolveShortcutSet(
                PromptShortcutAction.NEW_LINE_ID,
                KeyStroke.getKeyStroke(
                    java.awt.event.KeyEvent.VK_ENTER,
                    java.awt.event.InputEvent.SHIFT_DOWN_MASK
                )
            ),
            contentComponent
        )
    }

    private fun registerCtrlEnterNudge(contentComponent: JComponent) {
        object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) = onForceStopAndSend()
        }.registerCustomShortcutSet(
            PromptShortcutAction.resolveShortcutSet(
                PromptShortcutAction.STOP_AND_SEND_ID,
                KeyStroke.getKeyStroke(
                    java.awt.event.KeyEvent.VK_ENTER,
                    java.awt.event.InputEvent.CTRL_DOWN_MASK
                )
            ),
            contentComponent
        )
    }

    private fun registerCtrlShiftEnterQueue(contentComponent: JComponent) {
        object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) = onQueueMessageClicked()
        }.registerCustomShortcutSet(
            PromptShortcutAction.resolveShortcutSet(
                PromptShortcutAction.QUEUE_ID,
                KeyStroke.getKeyStroke(
                    java.awt.event.KeyEvent.VK_ENTER,
                    java.awt.event.InputEvent.CTRL_DOWN_MASK or java.awt.event.InputEvent.SHIFT_DOWN_MASK
                )
            ),
            contentComponent
        )
    }

    private fun onQueueMessageClicked() {
        val rawText = promptTextArea.text.trim()
        if (rawText.isEmpty()) return
        if (authService.pendingAuthError != null) return
        val id = System.currentTimeMillis().toString()
        promptTextArea.text = ""
        consolePanel.showQueuedMessage(id, rawText)
        com.github.catatafishen.agentbridge.psi.PsiBridgeService.getInstance(project)
            .enqueueMessage(rawText)
    }

    private fun onForceStopAndSend() {
        val rawText = promptTextArea.text.trim()
        if (rawText.isEmpty()) return
        if (isSending) {
            // Discard any pending nudge before stopping so setSendingState doesn't auto-send it
            if (pendingNudgeId != null) {
                val nudgeId = pendingNudgeId!!
                pendingNudgeId = null
                pendingNudgeText = null
                val psiBridge = com.github.catatafishen.agentbridge.psi.PsiBridgeService.getInstance(project)
                psiBridge.setPendingNudge(null)
                psiBridge.setOnNudgeConsumed(null)
                ApplicationManager.getApplication().invokeLater { consolePanel.removeNudgeBubble(nudgeId) }
            }
            promptOrchestrator.stop()
            setSendingState(false)
        }
        promptTextArea.text = rawText
        onSendStopClicked()
    }

    private fun handlePastePreprocess(
        event: java.util.EventObject,
        editor: EditorEx,
        contentComponent: JComponent,
        pasteStrokes: Set<KeyStroke>
    ): Boolean {
        val chatInputSettings = com.github.catatafishen.agentbridge.settings.ChatInputSettings.getInstance()
        if (!chatInputSettings.isSmartPasteEnabled) return false
        if (event !is java.awt.event.KeyEvent) return false
        if (editor.isDisposed) return false
        if (event.id != java.awt.event.KeyEvent.KEY_PRESSED) return false
        if (KeyStroke.getKeyStrokeForEvent(event) !in pasteStrokes) return false
        val focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        if (!SwingUtilities.isDescendingFrom(focused, contentComponent)) return false

        val clipText = contextManager.getClipboardText()
        val minLines = chatInputSettings.smartPasteMinLines
        val minChars = chatInputSettings.smartPasteMinChars
        if (clipText == null || (clipText.lines().size <= minLines && clipText.length <= minChars)) return false

        val projectSource = contextManager.findClipboardSourceInProject(clipText)
        event.consume()
        ApplicationManager.getApplication().invokeLater {
            if (editor.isDisposed) return@invokeLater
            if (projectSource != null) {
                contextManager.insertInlineChip(editor, projectSource)
            } else {
                pasteToScratchHandler.handlePasteToScratch(clipText)
            }
        }
        return true
    }

    private fun registerPasteIntercept(editor: EditorEx, contentComponent: JComponent) {
        val pasteStrokes = setOf(
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_V, java.awt.event.InputEvent.CTRL_DOWN_MASK),
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_V, java.awt.event.InputEvent.META_DOWN_MASK),
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_INSERT, java.awt.event.InputEvent.SHIFT_DOWN_MASK)
        )
        // Use IdeEventQueue preprocessor (runs before IdeKeyEventDispatcher) so we consume the
        // event before any other handler sees it — avoiding the double-paste that occurred when
        // popup.cancel() restored focus to contentComponent mid-dispatch.
        com.intellij.ide.IdeEventQueue.getInstance().addPreprocessor(
            { event ->
                handlePastePreprocess(event, editor, contentComponent, pasteStrokes)
            },
            project
        )
    }

    private fun registerTriggerCharDetection(editor: EditorEx) {
        editor.document.addDocumentListener(object : com.intellij.openapi.editor.event.DocumentListener {
            override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                val trigger = ActiveAgentManager.getAttachTriggerChar()
                if (trigger.isEmpty()) return
                val inserted = event.newFragment.toString()
                if (inserted != trigger) return

                val offset = event.offset
                val text = editor.document.text
                val isAtStart = offset == 0
                val isAfterSpace = offset > 0 && text[offset - 1] == ' '
                if (!isAtStart && !isAfterSpace) return

                ApplicationManager.getApplication().invokeLater {
                    com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                        editor.document.deleteString(offset, offset + trigger.length)
                    }
                    contextManager.openFileSearchPopup()
                }
            }
        }, project)
    }

    private fun setupPromptContextMenu(editor: EditorEx) {
        val group = DefaultActionGroup().apply {
            val editorPopup = ActionManager.getInstance().getAction("EditorPopupMenu")
            if (editorPopup != null) {
                add(editorPopup)
            }

            addSeparator()

            add(object : AnAction("Attach Current File", null, AllIcons.Actions.AddFile) {
                override fun actionPerformed(e: AnActionEvent) = contextManager.handleAddCurrentFile()
            })
            add(object : AnAction("Attach Editor Selection", null, AllIcons.Actions.AddMulticaret) {
                override fun actionPerformed(e: AnActionEvent) = contextManager.handleAddSelection()
            })
            add(object : AnAction("Clear Attachments", null, AllIcons.Actions.GC) {
                override fun actionPerformed(e: AnActionEvent) {
                    contextManager.clearInlineChips(editor)
                }

                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = contextManager.collectInlineContextItems().isNotEmpty()
                }
            })

            addSeparator()

            add(object : AnAction("New Conversation", null, AllIcons.General.Add) {
                override fun actionPerformed(e: AnActionEvent) {
                    promptOrchestrator.currentSessionId = null
                    consolePanel.addSessionSeparator(
                        java.time.Instant.now().toString(),
                        agentManager.activeProfile.displayName
                    )
                    updateSessionInfo()
                }
            })
        }

        editor.installPopupHandler(
            com.intellij.openapi.editor.impl.ContextMenuPopupHandler.Simple(group)
        )
    }

    private fun setupPromptDragDrop(textArea: EditorTextField) {
        textArea.dropTarget = java.awt.dnd.DropTarget(
            textArea, java.awt.dnd.DnDConstants.ACTION_COPY,
            object : java.awt.dnd.DropTargetAdapter() {
                override fun drop(dtde: java.awt.dnd.DropTargetDropEvent) {
                    handleFileDrop(dtde, textArea)
                }
            })
    }

    private fun handleFileDrop(dtde: java.awt.dnd.DropTargetDropEvent, textArea: EditorTextField) {
        try {
            dtde.acceptDrop(java.awt.dnd.DnDConstants.ACTION_COPY)
            val transferable = dtde.transferable
            if (transferable.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) {
                @Suppress("UNCHECKED_CAST") // DataFlavor API returns Object
                val files = transferable.getTransferData(
                    java.awt.datatransfer.DataFlavor.javaFileListFlavor
                ) as List<java.io.File>
                val editor = textArea.editor as? EditorEx
                for (file in files) {
                    val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                        .findFileByIoFile(file) ?: continue
                    val doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                        .getDocument(vf) ?: continue
                    if (editor != null) {
                        val existing = contextManager.collectInlineContextItems().any { it.path == vf.path }
                        if (!existing) {
                            val data = ContextItemData(
                                path = vf.path, name = vf.name,
                                startLine = 1, endLine = doc.lineCount,
                                fileTypeName = vf.fileType.name, isSelection = false
                            )
                            contextManager.insertInlineChip(editor, data)
                        }
                    }
                }
                dtde.dropComplete(true)
            } else {
                dtde.dropComplete(false)
            }
        } catch (_: Exception) {
            dtde.dropComplete(false)
        }
    }

    /**
     * Appends any entries written since the last persist to disk (append-only, no overwrite).
     * Tracks [persistedEntryCount] so only genuinely new entries are flushed each call.
     */
    private fun appendNewEntries() {
        lastIncrementalSaveMs = System.currentTimeMillis()
        val allEntries = conversationReplayer.deferredEntries() + chatConsolePanel.getEntries()
        val newEntries = allEntries.drop(persistedEntryCount)
        if (newEntries.isEmpty()) return
        conversationStore.appendEntriesAsync(project.basePath, newEntries)
        persistedEntryCount = allEntries.size
    }

    /**
     * Appends new entries if at least [saveIntervalMs] elapsed since the last append.
     * Called after each tool-call completion during streaming so that long-running turns
     * are periodically persisted and survive IDE crashes.
     */
    private fun appendNewEntriesThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastIncrementalSaveMs >= saveIntervalMs) {
            appendNewEntries()
        }
    }

    private fun restoreConversation(onComplete: () -> Unit = {}) {
        ApplicationManager.getApplication().executeOnPooledThread {
            V1ToV2Migrator.migrateIfNeeded(project.basePath)
            val entries = conversationStore.loadEntries(project.basePath)
            ApplicationManager.getApplication().invokeLater {
                if (entries != null) {
                    val histSettings = ChatHistorySettings.getInstance(project)
                    chatConsolePanel.setDomMessageLimit(histSettings.domMessageLimit)
                    conversationReplayer.loadAndSplit(entries, histSettings.recentTurnsOnRestore)
                    chatConsolePanel.appendEntries(
                        conversationReplayer.recentEntries(),
                        conversationReplayer.totalPromptCount()
                    )
                    val deferred = conversationReplayer.remainingPromptCount()
                    if (deferred > 0) chatConsolePanel.showLoadMore(deferred)
                    val lastStats = entries.filterIsInstance<EntryData.TurnStats>().lastOrNull()
                    if (lastStats != null && ::processingTimerPanel.isInitialized) {
                        val turnCount = entries.count { it is EntryData.TurnStats }
                        processingTimerPanel.restoreSessionStats(
                            lastStats.totalDurationMs, lastStats.totalInputTokens,
                            lastStats.totalOutputTokens, lastStats.totalCostUsd,
                            lastStats.totalToolCalls, lastStats.totalLinesAdded,
                            lastStats.totalLinesRemoved, turnCount
                        )
                    }
                    persistedEntryCount = conversationReplayer.totalLoadedCount()
                }
                onComplete()
            }
        }
    }

    /** Send a quick-reply directly without touching the user's input field. */
    private fun sendQuickReply(text: String) {
        if (isSending) return
        consolePanel.disableQuickReplies()
        sendPromptDirectly(text)
    }

    /** Send a prompt string directly, bypassing the text area (used for quick-replies). */
    private fun sendPromptDirectly(prompt: String) {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) return

        // Intercept Kiro slash commands
        val client = agentManager.getClient()
        if (client is com.github.catatafishen.agentbridge.acp.client.KiroClient && trimmed.startsWith("/")) {
            statusBanner?.dismissCurrent()
            setSendingState(true)
            consolePanel.addPromptEntry(trimmed, null)
            appendNewEntries()
            ApplicationManager.getApplication().executeOnPooledThread {
                client.executeSlashCommand(trimmed) { _ ->
                    ApplicationManager.getApplication().invokeLater {
                        setSendingState(false)
                    }
                }
            }
            return
        }

        statusBanner?.dismissCurrent()
        setSendingState(true)
        val entryId = consolePanel.addPromptEntry(trimmed, null)
        appendNewEntries()
        val selectedModelId = resolveSelectedModelId()
        ApplicationManager.getApplication().executeOnPooledThread {
            promptOrchestrator.execute(trimmed, emptyList(), selectedModelId, trimmed, entryId)
        }
    }

    private var autocompletePopup: com.intellij.openapi.ui.popup.JBPopup? = null

    private fun checkSlashCommandAutocomplete() {
        val client = agentManager.getClient()
        if (client !is com.github.catatafishen.agentbridge.acp.client.KiroClient) {
            autocompletePopup?.cancel()
            return
        }

        val text = promptTextArea.text
        if (!text.startsWith("/") || text.contains("\n")) {
            autocompletePopup?.cancel()
            return
        }

        val commands = client.availableCommands
        if (commands.size() == 0) return

        val matches = mutableListOf<String>()
        for (i in 0 until commands.size()) {
            val cmdObj = commands[i].asJsonObject
            val cmd = cmdObj["name"]?.asString ?: continue
            if (cmd.startsWith(text, ignoreCase = true)) {
                matches.add(cmd)
            }
        }

        if (matches.isEmpty()) {
            autocompletePopup?.cancel()
            return
        }

        showAutocompletePopup(matches)
    }

    private fun showAutocompletePopup(commands: List<String>) {
        autocompletePopup?.cancel()

        autocompletePopup = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
            .createPopupChooserBuilder(commands)
            .setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            .setItemChosenCallback { selected -> promptTextArea.text = selected.toString() }
            .createPopup()

        autocompletePopup?.showInBestPositionFor(promptTextArea.editor ?: return)
    }

    private fun onLoadMoreHistory() {
        val batchSize = ChatHistorySettings.getInstance(project).loadMoreBatchSize
        val batch = conversationReplayer.loadNextBatch(batchSize)
        if (batch.isNotEmpty()) chatConsolePanel.prependEntries(batch)
        val remaining = conversationReplayer.remainingPromptCount()
        if (remaining > 0) chatConsolePanel.showLoadMore(remaining)
        else chatConsolePanel.hideLoadMore()
    }

    fun getComponent(): JComponent = mainPanel

    private fun resetSessionState() {
        promptOrchestrator.currentSessionId = null
        promptOrchestrator.conversationSummaryInjected = false
        billing.billingCycleStartUsed = -1
        billing.resetLocalCounter()
        if (::processingTimerPanel.isInitialized) processingTimerPanel.resetSession()
        com.github.catatafishen.agentbridge.psi.CodeChangeTracker.clearSession()
        com.github.catatafishen.agentbridge.psi.PsiBridgeService.getInstance(project).clearSessionAllowedTools()
    }

    fun resetSession() {
        // Clear the persisted resume ID so the next session/new starts completely fresh.
        agentManager.settings.setResumeSessionId(null)
        agentManager.getClient().clearPersistedSession()
        resetSessionState()
        consolePanel.clear()
        consolePanel.showPlaceholder("New conversation started.")
        updateSessionInfo()
        archiveConversation()
        // Delete .current-session-id so the next save creates a brand-new v2 session.
        // This is separate from archive() because archive() must NOT delete the ID during
        // agent switches — doExport still needs the session ID for subsequent export steps.
        conversationStore.resetCurrentSessionId(project.basePath)
        ApplicationManager.getApplication().invokeLater {
            if (::planRoot.isInitialized) {
                planRoot.removeAllChildren()
                planTreeModel.reload()
                planDetailsArea.text =
                    "Session files and plan details will appear here.\n\nSelect an item in the tree to see details."
            }
        }
    }

    fun resetSessionKeepingHistory() {
        resetSessionState()
        updateSessionInfo()
    }

    private fun notifyIfUnfocused(toolCallCount: Int) {
        val frame = com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project) ?: return
        if (frame.isActive) return
        val title = "Copilot Response Ready"
        val content =
            if (toolCallCount > 0) "Turn completed with $toolCallCount tool call${if (toolCallCount != 1) "s" else ""}"
            else "Turn completed"
        com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            .notifyByBalloon(
                "AgentBridge",
                com.intellij.openapi.ui.MessageType.INFO,
                "<b>$title</b><br>$content"
            )
        com.intellij.ui.SystemNotifications.getInstance().notify("AgentBridge Notifications", title, content)
        com.intellij.ui.AppIcon.getInstance().requestAttention(project, false)
    }

    private fun saveTurnStatistics(prompt: String, toolCalls: Int, modelId: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val statsDir = java.io.File(project.basePath ?: return@executeOnPooledThread, AGENT_WORK_DIR)
                statsDir.mkdirs()
                val statsFile = java.io.File(statsDir, "usage-stats.jsonl")
                val entry = com.google.gson.JsonObject().apply {
                    addProperty("timestamp", java.time.Instant.now().toString())
                    addProperty("prompt", prompt.take(200))
                    addProperty("model", modelId)
                    if (agentManager.client.supportsMultiplier()) {
                        val multiplier = try {
                            agentManager.client.getModelMultiplier(modelId)
                        } catch (_: Exception) {
                            null
                        }
                        if (multiplier != null) addProperty("multiplier", multiplier)
                    }
                    addProperty("toolCalls", toolCalls)
                }
                statsFile.appendText(entry.toString() + "\n")
            } catch (_: Exception) { /* best-effort */
            }
        }
    }

    private fun archiveConversation() {
        conversationStore.archive(project.basePath)
        persistedEntryCount = 0
    }

    private fun getModelMultiplier(modelId: String): String? {
        return try {
            agentManager.client.getModelMultiplier(modelId)
        } catch (_: Exception) {
            null
        }
    }

    private fun restoreModelSelection(models: List<Model>) {
        val savedModel = agentManager.settings.selectedModel
        LOG.debug("Restoring model selection: saved='$savedModel', current='${agentManager.client.currentModelId}', available=${models.map { it.id() }}")
        if (savedModel != null) {
            val idx = models.indexOfFirst { it.id() == savedModel }
            if (idx >= 0) {
                selectedModelIndex = idx; LOG.debug("Restored model index=$idx"); return
            }
            LOG.debug("Saved model '$savedModel' not found in available models")
        }
        // Fall back to the agent-reported current model from session/new
        val currentModelId = agentManager.client.currentModelId
        if (currentModelId != null) {
            val idx = models.indexOfFirst { it.id() == currentModelId }
            if (idx >= 0) {
                selectedModelIndex = idx; LOG.debug("Selected agent-reported model index=$idx"); return
            }
        }
        if (models.isNotEmpty()) selectedModelIndex = 0
    }

    private fun resolveSelectedModelId(): String {
        loadedModels.getOrNull(selectedModelIndex)?.id()?.takeIf { it.isNotEmpty() }?.let { return it }
        return agentManager.client.currentModelId?.takeIf { it.isNotEmpty() } ?: ""
    }

    private fun loadModelsAsync(
        onSuccess: (List<Model>) -> Unit,
        onFailure: ((Exception) -> Unit)? = null
    ) {
        val generation = ++modelLoadGeneration
        ApplicationManager.getApplication().invokeLater {
            loadedModels = emptyList()
            modelsStatusText = MSG_LOADING
            selectedModelIndex = -1
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val models = fetchModelsWithRetry()
                ApplicationManager.getApplication().invokeLater {
                    if (generation == modelLoadGeneration) {
                        onModelsLoaded(models, onSuccess)
                    } else {
                        LOG.info("Discarding stale model load (gen $generation, current $modelLoadGeneration)")
                    }
                }
            } catch (e: Exception) {
                val errorMsg = e.message ?: MSG_UNKNOWN_ERROR
                LOG.warn("Failed to load models: $errorMsg")
                ApplicationManager.getApplication().invokeLater {
                    if (generation == modelLoadGeneration) {
                        onModelsLoadFailed(e)
                        onFailure?.invoke(e)
                    }
                }
            }
        }
    }

    private fun fetchModelsWithRetry(): List<Model> {
        // Wait for any in-progress session export to complete before starting the agent.
        // Without this, createSession() reads a stale or missing resumeSessionId because
        // the export from the previous agent runs concurrently on a pooled thread.
        SessionSwitchService.getInstance(project).awaitPendingExport(10_000)

        var lastError: Exception? = null
        for (attempt in 1..3) {
            if (attempt > 1) Thread.sleep(2000L)
            try {
                return agentManager.client.getAvailableModels()
            } catch (e: Exception) {
                lastError = e
                if (authService.isAuthenticationError(e.message ?: "") || isCLINotFoundError(e)) break
            }
        }
        throw lastError ?: RuntimeException(MSG_UNKNOWN_ERROR)
    }

    private fun onModelsLoaded(models: List<Model>, onSuccess: (List<Model>) -> Unit) {
        loadedModels = models
        modelsStatusText = null
        restoreModelSelection(models)
        onSuccess(models)
    }

    private fun onModelsLoadFailed(lastError: Exception) {
        val errorMsg = lastError.message ?: MSG_UNKNOWN_ERROR
        modelsStatusText = "Unavailable"
        if (isCLINotFoundError(lastError)) {
            agentManager.isConnected = false
            restartSessionGroup?.updateIconForDisconnect()
            connectPanel.showError(errorMsg)
            cardLayout.show(mainPanel, CARD_CONNECT)
        } else {
            statusBanner?.showError(errorMsg)
            if (authService.isAuthenticationError(errorMsg)) {
                authService.markAuthError(errorMsg)
                copilotBanner?.triggerCheck()
            }
        }
    }

    /** Returns true if the exception (or its cause chain) indicates the agent CLI binary was not found. */
    private fun isCLINotFoundError(e: Exception): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is AgentException && !cause.isRecoverable) return true
            cause = cause.cause
        }
        return false
    }

    /** Tree node for the Plans tab — display name is shown in the tree. */
    private class FileTreeNode(
        fileName: String
    ) : javax.swing.tree.DefaultMutableTreeNode("\uD83D\uDCC4 $fileName")
}
