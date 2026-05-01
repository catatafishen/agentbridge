package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.acp.model.Model
import com.github.catatafishen.agentbridge.acp.model.SessionUpdate
import com.github.catatafishen.agentbridge.psi.review.AgentEditSession
import com.github.catatafishen.agentbridge.services.ActiveAgentManager
import com.github.catatafishen.agentbridge.services.ChatWebServer
import com.github.catatafishen.agentbridge.session.SessionSwitchService
import com.github.catatafishen.agentbridge.session.migration.V1ToV2Migrator
import com.github.catatafishen.agentbridge.session.v2.SessionStoreV2
import com.github.catatafishen.agentbridge.settings.ChatHistorySettings
import com.github.catatafishen.agentbridge.settings.ChatInputSettings
import com.intellij.icons.AllIcons
import com.intellij.ide.ActivityTracker
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.actionSystem.toolbarLayout.ToolbarLayoutStrategy
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
        private const val PREF_SIDE_PANEL_OPEN = "agentbridge.sidePanelOpen"
        private const val PREF_INPUT_PANEL_HEIGHT = "agentbridge.inputPanelHeight"

        private val instances = java.util.concurrent.ConcurrentHashMap<Project, ChatToolWindowContent>()

        fun getInstance(project: Project): ChatToolWindowContent? = instances[project]
    }

    private val cardLayout = CardLayout()
    private val mainPanel = JBPanel<JBPanel<*>>(cardLayout)

    // Splitter wrapping the card layout: side panel on LEFT, chat on RIGHT.
    // Collapsed by default (proportion 0.0f). The user can drag, double-click, or use
    // the title-bar toggle to expand. The side panel is built lazily the first time the
    // chat panel is created so it has access to the ChatConsolePanel for the Prompts tab.
    private var sidePanel: com.github.catatafishen.agentbridge.ui.side.SidePanel? = null
    private val rootSplitter = com.intellij.ui.OnePixelSplitter(
        /* vertical = */ false, /* proportion = */ 0.0f
    ).also {
        it.secondComponent = mainPanel
        it.setHonorComponentsMinimumSize(false)
        // When the tool window is resized (by dragging its border), keep the chat pane
        // at its current width and let the sidebar absorb the size change.
        it.dividerPositionStrategy = com.intellij.openapi.ui.Splitter.DividerPositionStrategy.KEEP_SECOND_SIZE
        it.border = com.intellij.ui.SideBorder(JBColor.border(), com.intellij.ui.SideBorder.TOP)
    }

    /** Proportion used when expanding the review panel after it was collapsed. */
    private val defaultReviewProportion = 0.3f
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
    private lateinit var innerInputToolbar: ActionToolbar
    private var restartSessionGroup: RestartSessionGroup? = null
    private lateinit var promptTextArea: EditorTextField
    private lateinit var shortcutHintPanel: PromptShortcutHintPanel
    private val queuedTexts = ArrayDeque<String>()

    @Volatile
    private var isSending = false

    @Volatile
    private var pendingNudgeId: String? = null

    @Volatile
    private var pendingNudgeText: String? = null
    private lateinit var processingTimerPanel: ProcessingTimerPanel
    private lateinit var promptOrchestrator: PromptOrchestrator
    private lateinit var pasteToScratchHandler: PasteToScratchHandler
    private lateinit var pasteAttachmentHandler: PasteAttachmentHandler

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
        registerReviewPanelHandlers()
        setupUI()
        subscribeToFocusRestoreEvents()
        // Initialise the session store's agent name from the currently active profile.
        conversationStore.setCurrentAgent(agentManager.activeProfile.displayName)
    }

    /**
     * Registers expand/toggle callbacks with {@link ReviewPanelController} so non-UI code
     * (e.g. AgentEditSession gating notifications) can drive the splitter without reaching
     * into this class directly. The expand handler also selects the Review tab so callers
     * that request "show me the review" don't land on an unrelated tab.
     */
    private fun registerReviewPanelHandlers() {
        val expand = Runnable {
            sidePanel?.selectReviewTab()
            if (rootSplitter.proportion < 0.01f) {
                rootSplitter.proportion = defaultReviewProportion
            }
        }
        com.github.catatafishen.agentbridge.ui.review.ReviewPanelController
            .getInstance(project)
            .registerExpandHandler(expand)
    }

    /**
     * Subscribes to focus restore events published by PsiBridgeService after tool calls complete.
     * Restores keyboard focus to the chat input after files are opened in follow mode.
     *
     * <p>Uses a short delay (150ms) to ensure the restore fires <em>after</em> any secondary
     * focus changes triggered by tool window activations, navigate() calls, or showDiff() events
     * that may themselves use invokeLater internally.
     *
     * <p>This alarm complements {@code FocusGuard}, which handles focus steals <em>during</em>
     * tool execution synchronously. The alarm covers a different window: queued invokeLater tasks
     * that were created during the tool but run after the guard is removed (between tool completion
     * and T+150ms). The {@code isChatToolWindowActive} check inside the callback prevents the
     * alarm from stealing focus back if the user navigated away intentionally in that window.
     */
    private fun subscribeToFocusRestoreEvents() {
        val alarm = com.intellij.util.Alarm(com.intellij.util.Alarm.ThreadToUse.SWING_THREAD, project)
        val connection = project.messageBus.connect()
        connection.subscribe(
            com.github.catatafishen.agentbridge.psi.PsiBridgeService.FOCUS_RESTORE_TOPIC,
            com.github.catatafishen.agentbridge.psi.PsiBridgeService.FocusRestoreListener {
                if (::promptTextArea.isInitialized) {
                    alarm.cancelAllRequests()
                    alarm.addRequest({
                        // Re-check that chat is still the intended focus target. If the user
                        // clicked elsewhere in the 150ms window, honour that intent rather than
                        // stealing focus back to the prompt.
                        if (com.github.catatafishen.agentbridge.psi.PsiBridgeService
                                .isChatToolWindowActive(project)
                        ) {
                            promptTextArea.requestFocusInWindow()
                        }
                    }, 150)
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
            ws.setOnConnect(java.util.function.Consumer { profileId ->
                ApplicationManager.getApplication().invokeLater { connectToAgent(profileId, null) }
            })
            ws.setOnDisconnect(Runnable {
                ApplicationManager.getApplication().invokeLater { disconnectFromAgent() }
            })
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
            SidePanelToggleAction(),
            Separator.create(),
            StatisticsAction(),
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
        refreshShortcutHints()
    }

    fun disconnectFromAgent() {
        LOG.info("disconnectFromAgent: stopping agent and switching to connect panel")
        // Invalidate any in-flight loadModelsAsync() threads so they don't restart the agent
        // or apply stale model results after the user has explicitly disconnected.
        ++modelLoadGeneration
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
        ) { diag -> updateStateForCopilotDiagnostic(diag) }
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

    private fun AuthSetupBanner.updateStateForCopilotDiagnostic(diag: String) {
        val profile = agentManager.activeProfile
        val isCLINotFound = "copilot cli not found" in diag.lowercase() ||
            ("not found" in diag.lowercase() && ("copilot" in diag.lowercase() || "claude" in diag.lowercase()))
        val isAuthError = authService.isAuthenticationError(diag)
        when {
            isCLINotFound && profile.installUrl.isNotEmpty() ->
                updateState(
                    "${profile.displayName} is not installed \u2014 install from ${profile.installUrl}",
                    showInstall = true,
                )

            isCLINotFound -> {
                val cmd = if (System.getProperty("os.name").lowercase().contains("win"))
                    "winget install GitHub.Copilot" else "npm install -g @github/copilot-cli"
                updateState("Copilot CLI is not installed \u2014 install with: $cmd", showInstall = true)
            }

            !profile.isSupportsOAuthSignIn && profile.terminalSignInCommand != null && isAuthError ->
                updateState(
                    "Not signed in to ${profile.displayName} \u2014 click Sign In to authenticate, then Retry.",
                    showSignIn = true,
                )

            !profile.isSupportsOAuthSignIn && isAuthError ->
                updateState(
                    "Not signed in to ${profile.displayName} \u2014 check credentials and click Retry.",
                    showSignIn = false,
                )

            isAuthError ->
                updateState("Not signed in to Copilot \u2014 click Sign In, then click Retry.", showSignIn = true)

            else -> updateState("${profile.displayName} unavailable")
        }
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
        val sessionStatsPanel = createSessionStatsPanel()
        attachSidePanel(sessionStatsPanel)

        responsePanelContainer = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty()
            add(responsePanel, BorderLayout.CENTER)
        }

        val topPanel = createPromptTopPanel()
        val inputRow = createInputRow()
        val sideButtonsPanel = createSideButtonsPanel()
        val inputSection = createInputSection(inputRow, sideButtonsPanel)
        controlsToolbar.targetComponent = inputSection
        innerInputToolbar.targetComponent = inputSection

        val bottomSection = createBottomSection(inputSection)
        val splitPanel = createResizableSplitPanel(topPanel, bottomSection, inputSection)
        panel.add(splitPanel, BorderLayout.CENTER)

        billing.loadBillingData()
        return panel
    }

    private fun createSessionStatsPanel(): com.github.catatafishen.agentbridge.ui.side.SessionStatsPanel {
        processingTimerPanel = ProcessingTimerPanel(
            supportsMultiplier = { agentManager.isClientHealthy && agentManager.client.supportsMultiplier() },
            localPremiumRequests = { billing.localSessionPremiumRequests }
        )
        com.intellij.openapi.util.Disposer.register(project, processingTimerPanel)

        val statsUsageGraphPanel = UsageGraphPanel().apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    billing.showUsagePopup(this@apply)
                }
            })
        }
        billing.usageGraphPanel = statsUsageGraphPanel

        return com.github.catatafishen.agentbridge.ui.side.SessionStatsPanel(
            project,
            processingTimerPanel,
            statsUsageGraphPanel,
            billing
        )
    }

    private fun attachSidePanel(sessionStatsPanel: com.github.catatafishen.agentbridge.ui.side.SessionStatsPanel) {
        val side = com.github.catatafishen.agentbridge.ui.side.SidePanel(project, chatConsolePanel, sessionStatsPanel).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.empty(4),
                com.intellij.ui.RoundedLineBorder(JBUI.CurrentTheme.ToolWindow.borderColor(), JBUI.scale(8), 1)
            )
        }
        com.intellij.openapi.util.Disposer.register(toolWindow.disposable, side)
        sidePanel = side
        rootSplitter.firstComponent = side
        restoreSidePanelOpenState()
    }

    private fun restoreSidePanelOpenState() {
        val props = com.intellij.ide.util.PropertiesComponent.getInstance(project)
        if (props.getBoolean(PREF_SIDE_PANEL_OPEN, false)) {
            rootSplitter.proportion = defaultReviewProportion
        }
    }

    private fun createPromptTopPanel(): JBPanel<JBPanel<*>> {
        val topPanel = JBPanel<JBPanel<*>>(BorderLayout())
        val northStack = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        fun loadModels() {
            loadModelsAsync(onSuccess = { models -> loadedModels = models })
        }

        copilotBanner = createCopilotSetupBanner {
            authService.pendingAuthError = null
            promptOrchestrator.currentSessionId = null
            loadModels()
        }
        registerAgentSwitchBannerRefresh()

        val status = StatusBanner(project)
        statusBanner = status
        northStack.add(copilotBanner!!)
        northStack.add(createGhSetupBanner { billing.loadBillingData() })
        northStack.add(GitWarningBanner(project))
        northStack.add(status)

        consolePanel.onStatusMessage = { type, message -> showConsoleStatus(status, type, message) }
        topPanel.add(northStack, BorderLayout.NORTH)
        topPanel.add(responsePanelContainer, BorderLayout.CENTER)
        return topPanel
    }

    private fun registerAgentSwitchBannerRefresh() {
        agentManager.addSwitchListener {
            conversationStore.setCurrentAgent(agentManager.activeProfile.displayName)
            promptOrchestrator.currentSessionId = null
            promptOrchestrator.conversationSummaryInjected = false
            ApplicationManager.getApplication().invokeLater {
                copilotBanner?.triggerCheck()
            }
        }
    }

    private fun showConsoleStatus(status: StatusBanner, type: String, message: String) {
        when (type) {
            "error" -> status.showError(message)
            "warning" -> status.showWarning(message)
            else -> status.showInfo(message)
        }
    }

    private fun createInputSection(
        inputRow: JComponent,
        sideButtonsPanel: JComponent
    ): JBPanel<JBPanel<*>> {
        val sideRailWidth = { sideButtonsPanel.preferredSize.width }
        return object : JBPanel<JBPanel<*>>(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g.create() as Graphics2D
                try {
                    paintInputSectionBackground(g2, sideRailWidth())
                } finally {
                    g2.dispose()
                }
            }
        }.apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 0, 1, 4)
            add(sideButtonsPanel, BorderLayout.WEST)
            add(inputRow, BorderLayout.CENTER)
        }
    }

    private fun JComponent.paintInputSectionBackground(g2: Graphics2D, sideRailWidth: Int) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val arc = JBUI.scale(8)
        g2.color = com.intellij.util.ui.UIUtil.getTextFieldBackground()
        g2.fillRoundRect(0, 0, width, height, arc, arc)
        paintInputSectionDivider(g2, sideRailWidth)
        g2.color = UIManager.getColor("Component.borderColor") ?: JBUI.CurrentTheme.ToolWindow.borderColor()
        g2.drawRoundRect(1, 1, width - 2, height - 2, arc, arc)
    }

    private fun JComponent.paintInputSectionDivider(g2: Graphics2D, sideRailWidth: Int) {
        val dividerX = insets.left + sideRailWidth
        if (dividerX <= insets.left || dividerX >= width - insets.right) return
        g2.color = JBUI.CurrentTheme.ToolWindow.borderColor()
        g2.drawLine(
            dividerX,
            insets.top + JBUI.scale(2),
            dividerX,
            height - insets.bottom - JBUI.scale(2)
        )
    }

    private fun createBottomSection(inputSection: JComponent): JBPanel<JBPanel<*>> =
        JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 8, 8, 8)
            add(inputSection, BorderLayout.CENTER)
        }

    private fun createResizableSplitPanel(
        topPanel: JComponent,
        bottomSection: JBPanel<JBPanel<*>>,
        inputSection: JComponent
    ): JBPanel<JBPanel<*>> {
        val splitPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply { isOpaque = false }
        val props = com.intellij.ide.util.PropertiesComponent.getInstance(project)
        installInputResizeHandler(inputSection, bottomSection, splitPanel, props)
        installSavedInputHeight(splitPanel, bottomSection, props.getInt(PREF_INPUT_PANEL_HEIGHT, 0))
        splitPanel.add(topPanel, BorderLayout.CENTER)
        splitPanel.add(bottomSection, BorderLayout.SOUTH)
        return splitPanel
    }

    private data class ResizeState(var activeResize: Pair<Int, Int>? = null)

    private fun installInputResizeHandler(
        inputSection: JComponent,
        bottomSection: JComponent,
        splitPanel: JComponent,
        props: com.intellij.ide.util.PropertiesComponent
    ) {
        val resizeState = ResizeState()
        val resizeDragZone = JBUI.scale(8)
        val resizeHandler = object : java.awt.event.MouseAdapter() {
            override fun mouseMoved(e: java.awt.event.MouseEvent) {
                inputSection.cursor = if (e.y <= resizeDragZone) {
                    Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)
                } else {
                    Cursor.getDefaultCursor()
                }
            }

            override fun mousePressed(e: java.awt.event.MouseEvent) {
                if (e.y <= resizeDragZone) resizeState.activeResize = Pair(e.locationOnScreen.y, bottomSection.height)
            }

            override fun mouseDragged(e: java.awt.event.MouseEvent) {
                val (startY, startH) = resizeState.activeResize ?: return
                val delta = startY - e.locationOnScreen.y
                bottomSection.preferredSize = Dimension(
                    bottomSection.width,
                    (startH + delta).coerceIn(minInputHeight(), maxInputHeight(splitPanel.height))
                )
                splitPanel.revalidate()
            }

            override fun mouseReleased(e: java.awt.event.MouseEvent) {
                if (resizeState.activeResize == null) return
                resizeState.activeResize = null
                props.setValue(PREF_INPUT_PANEL_HEIGHT, bottomSection.height, 0)
            }

            override fun mouseExited(e: java.awt.event.MouseEvent) {
                if (resizeState.activeResize == null) inputSection.cursor = Cursor.getDefaultCursor()
            }
        }
        inputSection.addMouseMotionListener(resizeHandler)
        inputSection.addMouseListener(resizeHandler)
    }

    private fun installSavedInputHeight(
        splitPanel: JComponent,
        bottomSection: JComponent,
        savedInputHeight: Int
    ) {
        splitPanel.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) {
                if (splitPanel.height <= 0) return
                splitPanel.removeComponentListener(this)
                val targetHeight = savedInputHeight.takeIf { it > 0 } ?: (splitPanel.height * 0.22).toInt()
                bottomSection.preferredSize = Dimension(
                    bottomSection.width,
                    targetHeight.coerceIn(minInputHeight(), maxInputHeight(splitPanel.height))
                )
                splitPanel.revalidate()
            }
        })
    }

    private fun minInputHeight(): Int = JBUI.scale(100)

    private fun maxInputHeight(splitPanelHeight: Int): Int =
        (splitPanelHeight - JBUI.scale(80)).coerceAtLeast(minInputHeight())

    private fun createInputRow(): JBPanel<JBPanel<*>> {
        val row = JBPanel<JBPanel<*>>(BorderLayout())
        row.isOpaque = false
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
        pasteAttachmentHandler = PasteAttachmentHandler(project, promptTextArea, contextManager)
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
                onTimerSetLastTurnMultiplier = { mult ->
                    if (::processingTimerPanel.isInitialized) processingTimerPanel.setLastTurnMultiplier(mult)
                },
                onTimerSetCodeChangeStats = { a, r ->
                    if (::processingTimerPanel.isInitialized) processingTimerPanel.setCodeChangeStats(a, r)
                },
                onClientUpdate = ::handleClientUpdate,
                sendPromptDirectly = ::sendPromptDirectly,
                restorePromptText = ::restorePromptText,
                onTurnMineEntries = ::mineEntriesAfterTurn,
                onQueuedMessageConsumed = { text ->
                    // Mirror the cancel path: remove the LAST matching entry so that when
                    // the same text was queued multiple times, "recall most recent queued
                    // message" ordering remains intact (Up-arrow restores the oldest copies
                    // first, newest copies last).
                    val lastMatchingIndex = queuedTexts.lastIndexOf(text)
                    if (lastMatchingIndex >= 0) {
                        queuedTexts.removeAt(lastMatchingIndex)
                    }
                    ApplicationManager.getApplication().invokeLater { refreshShortcutHints() }
                },
            )
        )

        // Shortcut hint bar — initialized here so input wiring below can reference it.
        shortcutHintPanel = PromptShortcutHintPanel()
        shortcutHintPanel.isVisible =
            com.github.catatafishen.agentbridge.settings.ChatInputSettings.getInstance().isShowShortcutHints

        setupPromptDragDrop(promptTextArea)
        promptTextArea.addSettingsProvider { editor ->
            setupPromptKeyBindings(editor)
            setupPromptContextMenu(editor)
            editor.setPlaceholder(promptPlaceholder())
            editor.setShowPlaceholderWhenFocused(true)
            editor.settings.isUseSoftWraps =
                com.github.catatafishen.agentbridge.settings.ChatInputSettings.getInstance().isSoftWrapsEnabled
            editor.setBorder(null)
            editor.scrollPane.verticalScrollBar.preferredSize =
                Dimension(JBUI.scale(10), editor.scrollPane.verticalScrollBar.preferredSize.height)
        }

        promptTextArea.addDocumentListener(object : com.intellij.openapi.editor.event.DocumentListener {
            override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                com.github.catatafishen.agentbridge.psi.PsiBridgeService.notifyChatInputChanged(
                    project, event.document.textLength == 0
                )
                ApplicationManager.getApplication().invokeLater {
                    promptTextArea.revalidate()
                    checkSlashCommandAutocomplete()
                    // Refresh Send button enabled-state immediately on every keystroke.
                    // ActionToolbar's default polling cycle (~500ms) makes the button feel
                    // sluggish to enable/disable when text appears/disappears.
                    if (::innerInputToolbar.isInitialized) {
                        innerInputToolbar.updateActionsAsync()
                    }
                }
            }
        })

        row.add(promptTextArea, BorderLayout.CENTER)

        val footerGroup = DefaultActionGroup()
        footerGroup.add(ShortcutHintsAction())
        footerGroup.add(ModelSelectorAction())
        footerGroup.add(SendAction())

        innerInputToolbar = ActionManager.getInstance().createActionToolbar("AgentInputFooter", footerGroup, true)
        innerInputToolbar.layoutStrategy = ToolbarLayoutStrategy.NOWRAP_STRATEGY
        innerInputToolbar.isReservePlaceAutoPopupIcon = true
        innerInputToolbar.component.isOpaque = false
        innerInputToolbar.component.apply {
            border = JBUI.Borders.empty()
            alignmentY = Component.RIGHT_ALIGNMENT
        }
        row.add(RightAnchoredFooterScrollPane(innerInputToolbar.component), BorderLayout.SOUTH)

        refreshShortcutHints()

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
        // Auto-clean approved review rows when a brand-new user turn starts (not nudge / queued follow-up).
        if (com.github.catatafishen.agentbridge.settings.McpServerSettings.getInstance(project).isAutoCleanReviewOnNewPrompt) {
            try {
                AgentEditSession.getInstance(project)
                    ?.removeAllApproved()
            } catch (_: Throwable) { /* defensive: review session is best-effort */
            }
        }
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

        // Resolve file reference ORCs to plain text names before clearing the editor —
        // nudges don't support context attachments, so inline chips become backtick-wrapped names.
        val contextItems = contextManager.collectInlineContextItems()
        val text = contextManager.replaceOrcsWithTextRefs(rawText, contextItems)

        promptTextArea.text = ""
        submitNudge(text)
    }

    /** Shows or appends a nudge bubble and arms the PsiBridge consume handler. */
    private fun submitNudge(text: String) {
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
        val psiBridge = com.github.catatafishen.agentbridge.psi.PsiBridgeService.getInstance(project)
        psiBridge.setPendingNudge(text)
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
                refreshShortcutHints()
            }
        }
        refreshShortcutHints()
    }

    /** Clears pending nudge state and removes the nudge bubble from the chat panel. */
    private fun clearAndRemoveNudge(nudgeId: String) {
        pendingNudgeId = null
        pendingNudgeText = null
        val psiBridge = com.github.catatafishen.agentbridge.psi.PsiBridgeService.getInstance(project)
        psiBridge.setPendingNudge(null)
        psiBridge.setOnNudgeConsumed(null)
        ApplicationManager.getApplication().invokeLater {
            consolePanel.removeNudgeBubble(nudgeId)
            refreshShortcutHints()
        }
    }

    private fun buildBubbleHtml(rawText: String, items: List<ContextItemData>): String? =
        PromptBubbleBuilder.buildBubbleHtml(rawText, items)

    fun setSoftWrapsEnabled(enabled: Boolean) {
        promptTextArea.editor?.settings?.isUseSoftWraps = enabled
    }

    fun setShortcutHintsVisible() {
        if (!::shortcutHintPanel.isInitialized) return
        refreshShortcutHints()
    }

    /**
     * Rebuilds the shortcut hint bar based on the current input/turn state.
     *
     * - Idle: Enter ▸ Send, Shift+Enter ▸ New line.
     * - Busy: Enter ▸ Nudge, Ctrl+Enter ▸ Stop && send, Ctrl+Shift+Enter ▸ Queue,
     *         Shift+Enter ▸ New line — all four are relevant during a turn.
     * - When a nudge or queued message is pending, an extra `↑ ▸ Edit last`
     *   hint is appended so the user knows they can recall it.
     */
    private fun refreshShortcutHints() {
        if (!::shortcutHintPanel.isInitialized) return
        val list = mutableListOf<Pair<KeyStroke, String>>()
        if (isSending) {
            list += PromptShortcutAction.resolveKeystroke(
                PromptShortcutAction.SEND_ID,
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0)
            ) to "Nudge"
            list += PromptShortcutAction.resolveKeystroke(
                PromptShortcutAction.STOP_AND_SEND_ID,
                KeyStroke.getKeyStroke(
                    java.awt.event.KeyEvent.VK_ENTER,
                    java.awt.event.InputEvent.CTRL_DOWN_MASK
                )
            ) to "Stop && send"
            list += PromptShortcutAction.resolveKeystroke(
                PromptShortcutAction.QUEUE_ID,
                KeyStroke.getKeyStroke(
                    java.awt.event.KeyEvent.VK_ENTER,
                    java.awt.event.InputEvent.CTRL_DOWN_MASK or java.awt.event.InputEvent.SHIFT_DOWN_MASK
                )
            ) to "Queue"
        } else {
            list += PromptShortcutAction.resolveKeystroke(
                PromptShortcutAction.SEND_ID,
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0)
            ) to "Send"
        }
        list += PromptShortcutAction.resolveKeystroke(
            PromptShortcutAction.NEW_LINE_ID,
            KeyStroke.getKeyStroke(
                java.awt.event.KeyEvent.VK_ENTER,
                java.awt.event.InputEvent.SHIFT_DOWN_MASK
            )
        ) to "New line"
        if (pendingNudgeId != null || queuedTexts.isNotEmpty()) {
            list += KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_UP, 0) to "Edit last"
        }
        shortcutHintPanel.setShortcuts(list)
        shortcutHintPanel.isVisible = ChatInputSettings.getInstance().isShowShortcutHints
    }

    private inner class ShortcutHintsAction : AnAction(), com.intellij.openapi.actionSystem.ex.CustomComponentAction {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            // Custom-component placeholder for the shortcut hint row; it has no direct click action.
        }

        override fun createCustomComponent(presentation: Presentation, place: String): JComponent = shortcutHintPanel

        override fun updateCustomComponent(component: JComponent, presentation: Presentation) {
            component.isVisible = ChatInputSettings.getInstance().isShowShortcutHints
        }
    }

    private fun setSendingState(sending: Boolean) {
        isSending = sending
        ChatWebServer.getInstance(project)?.setAgentRunning(sending)
        if (!sending) restoreUnhandledNudgeIfNeeded()
        ApplicationManager.getApplication().invokeLater {
            updatePromptPlaceholder()
            controlsToolbar.updateActionsAsync()
            innerInputToolbar.updateActionsAsync()
            refreshShortcutHints()
            updateProcessingTimer(sending)
        }
    }

    private fun restoreUnhandledNudgeIfNeeded() {
        val nudgeId = pendingNudgeId ?: return
        val nudgeText = pendingNudgeText
        pendingNudgeId = null
        pendingNudgeText = null
        val psiBridge = com.github.catatafishen.agentbridge.psi.PsiBridgeService.getInstance(project)
        psiBridge.setPendingNudge(null)
        psiBridge.setOnNudgeConsumed(null)
        ApplicationManager.getApplication().invokeLater {
            consolePanel.removeNudgeBubble(nudgeId)
            nudgeText?.let { restoreUnhandledNudgeText(it) }
        }
    }

    private fun restoreUnhandledNudgeText(nudgeText: String) {
        val mode = ChatInputSettings.getInstance().unhandledNudgeMode
        if (mode == ChatInputSettings.UnhandledNudgeMode.RESTORE_INTO_INPUT) {
            prependNudgeToInput(nudgeText)
        } else {
            sendUnhandledNudge(nudgeText)
        }
    }

    private fun prependNudgeToInput(nudgeText: String) {
        val current = promptTextArea.text
        promptTextArea.text = if (current.isEmpty()) nudgeText else "$nudgeText\n\n$current"
        promptTextArea.requestFocusInWindow()
    }

    private fun sendUnhandledNudge(nudgeText: String) {
        promptTextArea.text = nudgeText
        onSendStopClicked()
    }

    private fun updateProcessingTimer(sending: Boolean) {
        if (!::processingTimerPanel.isInitialized) return
        if (sending) processingTimerPanel.start() else processingTimerPanel.stop()
    }

    private fun createSideButtonsPanel(): JComponent {
        val leftGroup = DefaultActionGroup()
        restartSessionGroup = RestartSessionGroup()
        leftGroup.add(restartSessionGroup!!)
        leftGroup.addSeparator()
        leftGroup.add(AttachContextDropdownAction())
        leftGroup.addSeparator()
        leftGroup.add(DisconnectOrStopAction())

        controlsToolbar = ActionManager.getInstance().createActionToolbar(
            "AgentControls", leftGroup, false
        )
        controlsToolbar.isReservePlaceAutoPopupIcon = false
        controlsToolbar.component.border = JBUI.Borders.empty(4, 4, 4, 0)
        controlsToolbar.component.isOpaque = false

        return controlsToolbar.component
    }

    /**
     * Single toolbar slot that shows as Stop while the agent is running, and as Disconnect when idle.
     * This lets the power/disconnect action occupy the same visual position as the stop button
     * without needing two separate buttons.
     */
    private inner class DisconnectOrStopAction : AnAction() {
        private val powerIcon = com.intellij.openapi.util.IconLoader.getIcon(
            "/icons/power.svg", DisconnectOrStopAction::class.java
        )

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            if (isSending) {
                e.presentation.icon = AllIcons.Actions.Suspend
                e.presentation.text = "Stop"
                e.presentation.description = "Stop the agent"
            } else {
                e.presentation.icon = powerIcon
                e.presentation.text = "Disconnect"
                e.presentation.description = "Disconnect or manage the current session"
            }
            e.presentation.isEnabled = true
        }

        override fun actionPerformed(e: AnActionEvent) {
            if (isSending) {
                promptOrchestrator.stop()
                setSendingState(false)
            } else {
                val inputEvent = e.inputEvent ?: return
                val component = inputEvent.source as? Component ?: return
                val group = DefaultActionGroup()
                addSessionManagementSection(group)
                val popup = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
                    .createActionGroupPopup(
                        null, group, e.dataContext,
                        com.intellij.openapi.ui.popup.JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false
                    )
                popup.showUnderneathOf(component)
            }
        }
    }

    private inner class SendAction : AnAction(), com.intellij.openapi.actionSystem.ex.CustomComponentAction {
        private val sendIcon = com.intellij.openapi.util.IconLoader.getIcon(
            "/icons/send.svg", SendAction::class.java
        )

        // Captured at createCustomComponent time so showSendDropdown has a stable popup anchor.
        private var sendButton: JButton? = null

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
            // Compact icon-only button keeps the footer from growing taller than the
            // dropdowns beside it while still exposing the action through the tooltip.
            val button = JButton(sendIcon)
            button.putClientProperty("JButton.buttonType", "primary")
            button.isFocusable = false
            button.margin = JBUI.insets(0, 6)
            button.iconTextGap = 0
            button.toolTipText = presentation.description
            // Direct routing avoids the deprecated AnActionEvent.createFromAnAction.
            button.addActionListener {
                if (!isSending || consolePanel.hasPendingAskUserRequest()) {
                    onSendStopClicked()
                } else {
                    showSendDropdown(button)
                }
            }
            sendButton = button
            return button
        }

        override fun updateCustomComponent(component: JComponent, presentation: Presentation) {
            (component as? JButton)?.let { btn ->
                btn.isEnabled = presentation.isEnabled
                btn.text = ""
                btn.icon = presentation.icon ?: sendIcon
                btn.toolTipText = presentation.description
            }
        }

        override fun update(e: AnActionEvent) {
            val isLoggedIn = authService.pendingAuthError == null
            val hasText = promptTextArea.text.trim().isNotEmpty()
            if (isSending && !consolePanel.hasPendingAskUserRequest()) {
                e.presentation.icon = sendIcon
                e.presentation.text = ""
                e.presentation.description = "Nudge, queue, or stop and send"
                e.presentation.isEnabled = hasText
            } else {
                e.presentation.icon = sendIcon
                e.presentation.text = ""
                e.presentation.description = if (isLoggedIn) "Send prompt (Enter)" else "Sign in to Copilot first"
                e.presentation.isEnabled = isLoggedIn && hasText
            }
        }

        override fun actionPerformed(e: AnActionEvent) {
            if (!isSending || consolePanel.hasPendingAskUserRequest()) {
                onSendStopClicked()
                return
            }
            showSendDropdown(sendButton ?: return)
        }

        private fun showSendDropdown(anchor: Component) {
            val hasText = promptTextArea.text.trim().isNotEmpty()
            val group = DefaultActionGroup()
            group.add(object : AnAction("Nudge", "Send a nudge to the running agent", AllIcons.Actions.Forward) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = hasText
                }

                override fun actionPerformed(e: AnActionEvent) = onNudgeClicked()
            })
            group.add(object :
                AnAction("Queue", "Queue this message to send after the agent finishes", AllIcons.General.Add) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = hasText && authService.pendingAuthError == null
                }

                override fun actionPerformed(e: AnActionEvent) = onQueueMessageClicked()
            })
            group.add(object :
                AnAction("Stop and Send", "Stop the agent and send this as a new prompt", AllIcons.Actions.Restart) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = hasText
                }

                override fun actionPerformed(e: AnActionEvent) = onForceStopAndSend()
            })

            val popup = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
                .createActionGroupPopup(
                    null, group,
                    com.intellij.openapi.actionSystem.impl.SimpleDataContext.getProjectContext(project),
                    com.intellij.openapi.ui.popup.JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false
                )
            popup.showUnderneathOf(anchor)
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
            addAgentSelectionSection(group)
            addSessionOptionsSection(group)
            if (group.childrenCount == 0) return
            val popup = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createActionGroupPopup(
                null, group, e.dataContext,
                com.intellij.openapi.ui.popup.JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false
            )
            popup.showUnderneathOf(component)
        }
    }

    private fun addAgentSelectionSection(group: DefaultActionGroup): Boolean {
        val agents = try {
            agentManager.client.availableAgents
        } catch (_: Exception) {
            emptyList()
        }
        if (agents.isEmpty()) return false
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
        return true
    }

    private fun addSessionOptionsSection(group: DefaultActionGroup): Boolean {
        val options = try {
            agentManager.client.listSessionOptions()
        } catch (_: Exception) {
            emptyList()
        }
        if (options.isEmpty()) return false
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
        return true
    }

    private fun addSessionManagementSection(group: DefaultActionGroup) {
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
            override fun update(e: AnActionEvent) {
                // The plugin no longer manages credentials for Claude or Codex — there is
                // no programmatic logout for either CLI (Claude has no clean revoke command;
                // Codex has no `codex logout` subcommand at all). Hide the button for both
                // so users aren't misled into thinking it does anything.
                // See docs/AUTH-HANDLING.md.
                val agentId = agentManager.getActiveProfile().id
                val isClaudeOrCodex =
                    agentId == com.github.catatafishen.agentbridge.agent.claude.ClaudeCliClient.PROFILE_ID
                        || agentId == com.github.catatafishen.agentbridge.agent.codex.CodexAppServerClient.PROFILE_ID
                e.presentation.isEnabledAndVisible = !isClaudeOrCodex
            }

            override fun actionPerformed(e: AnActionEvent) {
                LOG.info("Logout: disabling auto-connect and disconnecting")
                agentManager.isAutoConnect = false
                authService.logout()
                disconnectFromAgent()
            }
        })
        group.add(dangerousActionsGroup)
    }

    private inner class StatisticsAction : AnAction(
        "Usage Statistics", "View usage statistics across agent sessions",
        AllIcons.Actions.ProfileCPU
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            com.github.catatafishen.agentbridge.ui.statistics.UsageStatisticsDialog(project).show()
        }
    }

    /** Toolbar button that opens the plugin settings. */
    private inner class SettingsAction : AnAction(
        "Settings", "Open AgentBridge settings",
        AllIcons.General.Settings
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            com.github.catatafishen.agentbridge.settings.openAgentBridgeSettings(project)
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

    private inner class SidePanelToggleAction : ToggleAction(
        "Side Panel",
        "Show or hide the side panel (Review, Project Files, Prompts)",
        AllIcons.Actions.PreviewDetails
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun isSelected(e: AnActionEvent): Boolean =
            rootSplitter.proportion >= 0.01f

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (state) {
                // When showing: record the current tool window width (= chat width when side is hidden),
                // then expand the tool window by the side panel width so the chat area stays the same size.
                val chatWidth = rootSplitter.width
                rootSplitter.proportion = defaultReviewProportion
                if (chatWidth > 0) {
                    val stretchAmount = (chatWidth * defaultReviewProportion / (1.0 - defaultReviewProportion)).toInt()
                    (toolWindow as? com.intellij.openapi.wm.ex.ToolWindowEx)?.stretchWidth(stretchAmount)
                }
            } else {
                // When hiding: record the current side panel width, collapse it,
                // then shrink the tool window by that width to restore the original chat area size.
                val sideWidth = rootSplitter.firstComponent?.width ?: 0
                rootSplitter.proportion = 0.0f
                if (sideWidth > 0) {
                    (toolWindow as? com.intellij.openapi.wm.ex.ToolWindowEx)?.stretchWidth(-sideWidth)
                }
            }
            com.intellij.ide.util.PropertiesComponent.getInstance(project)
                .setValue(PREF_SIDE_PANEL_OPEN, state)
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

    /** ComboBoxAction for model selection — matches Run panel dropdown style. */
    private inner class ModelSelectorAction : ComboBoxAction() {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun createPopupActionGroup(button: JComponent, context: DataContext): DefaultActionGroup {
            val group = DefaultActionGroup()
            val supportsMultiplier = agentManager.client.supportsMultiplier()
            loadedModels.forEachIndexed { index, model ->
                val cost = if (supportsMultiplier) getModelMultiplier(model.id()) else null
                group.add(createModelSelectionAction(model, index, cost))
            }
            return group
        }

        override fun update(e: AnActionEvent) {
            e.presentation.text = currentModelSelectorText()
            e.presentation.isEnabled = modelsStatusText == null && loadedModels.isNotEmpty()
            // Hide entirely when models loaded successfully but list is empty
            // (agent uses configOptions for model selection instead)
            e.presentation.isVisible = modelsStatusText != null || loadedModels.isNotEmpty()
        }
    }

    private fun currentModelSelectorText(): String {
        modelsStatusText?.let { return it }
        return if (selectedModelIndex in loadedModels.indices) {
            loadedModels[selectedModelIndex].name()
        } else {
            MSG_LOADING
        }
    }

    private fun createModelSelectionAction(model: Model, index: Int, cost: String?): AnAction {
        val label = if (cost != null) "${model.name()}  ($cost)" else model.name()
        return object : AnAction(label) {
            override fun actionPerformed(e: AnActionEvent) {
                if (index == selectedModelIndex) return
                selectedModelIndex = index
                agentManager.settings.setSelectedModel(model.id())
                LOG.debug("Model selected: ${model.id()} (index=$index)")
                ApplicationManager.getApplication().invokeLater {
                    consolePanel.setCurrentModel(model.id())
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
            if (pendingNudgeId == id) clearAndRemoveNudge(id)
        }
        chatConsolePanel.onCancelQueuedMessage = { id, text ->
            val psiBridge = com.github.catatafishen.agentbridge.psi.PsiBridgeService.getInstance(project)
            psiBridge.removeQueuedMessage(text)
            // Drop the most-recent matching entry so Up-arrow recall reflects what's still queued.
            val lastIdx = queuedTexts.indexOfLast { it == text }
            if (lastIdx >= 0) queuedTexts.removeAt(lastIdx)
            ApplicationManager.getApplication().invokeLater {
                consolePanel.removeQueuedMessage(id)
                refreshShortcutHints()
            }
        }
        chatConsolePanel.onAutoScrollDisabled = {
            autoScrollEnabled = false
            ActivityTracker.getInstance().inc()
        }
        chatConsolePanel.onAutoScrollEnabled = {
            autoScrollEnabled = true
            ActivityTracker.getInstance().inc()
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
            setupWebServerCallbacks(ws)
        }

        return consolePanel.component
    }

    private fun setupWebServerCallbacks(ws: ChatWebServer) {
        ws.setOnSendPrompt { prompt ->
            ApplicationManager.getApplication().invokeLater { sendPromptDirectly(prompt) }
        }
        ws.setOnQuickReply { text ->
            ApplicationManager.getApplication().invokeLater {
                if (!consolePanel.consumePendingAskUserResponse(text)) sendQuickReply(text)
            }
        }
        ws.setOnNudge { text ->
            ApplicationManager.getApplication().invokeLater {
                if (isSending) submitNudge(text)
            }
        }
        ws.setOnStop {
            ApplicationManager.getApplication().invokeLater {
                if (isSending) {
                    promptOrchestrator.stop()
                    setSendingState(false)
                }
            }
        }
        ws.setOnCancelNudge { id ->
            ApplicationManager.getApplication().invokeLater {
                chatConsolePanel.onCancelNudge?.invoke(id)
            }
        }
        ws.setOnPermissionResponse(java.util.function.Consumer { data ->
            ApplicationManager.getApplication().invokeLater {
                chatConsolePanel.handleWebPermissionResponse(data)
            }
        })
        ws.setOnSelectModel(java.util.function.Consumer { modelId ->
            ApplicationManager.getApplication().invokeLater { selectModelById(modelId) }
        })
        ws.setOnLoadMore(Runnable {
            ApplicationManager.getApplication().invokeLater { onLoadMoreHistory() }
        })
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
        registerShowShortcutsPopup(contentComponent)
        registerUpArrowRecall(contentComponent)
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

    private fun registerShowShortcutsPopup(contentComponent: JComponent) {
        object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) = ShortcutCheatSheetPopup.show(promptTextArea)
        }.registerCustomShortcutSet(
            PromptShortcutAction.resolveShortcutSet(
                PromptShortcutAction.SHOW_SHORTCUTS_ID,
                KeyStroke.getKeyStroke(
                    java.awt.event.KeyEvent.VK_SLASH,
                    java.awt.event.InputEvent.CTRL_DOWN_MASK
                )
            ),
            contentComponent
        )
    }

    /**
     * Up-arrow recall: when the prompt is empty and a nudge or queued message
     * is pending, pop the most recent one back into the input box for editing.
     * Pending nudge takes priority over queued messages (it's the more recent
     * pending action). The Up-arrow is only consumed when the input is empty
     * so multi-line caret navigation still works once the user starts typing.
     */
    private fun registerUpArrowRecall(contentComponent: JComponent) {
        object : AnAction() {
            override fun getActionUpdateThread(): com.intellij.openapi.actionSystem.ActionUpdateThread =
                com.intellij.openapi.actionSystem.ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                // Only consume Up when the prompt is empty AND something is pending to recall.
                // When disabled, the keystroke falls through to the editor's default
                // caret-up behavior so multi-line navigation isn't blocked.
                val empty = promptTextArea.text.isEmpty()
                val hasPending = pendingNudgeId != null || queuedTexts.isNotEmpty()
                e.presentation.isEnabledAndVisible = empty && hasPending
            }

            override fun actionPerformed(e: AnActionEvent) {
                if (promptTextArea.text.isNotEmpty()) return
                val nudgeId = pendingNudgeId
                val nudgeText = pendingNudgeText
                if (nudgeId != null && nudgeText != null) {
                    promptTextArea.text = nudgeText
                    clearAndRemoveNudge(nudgeId)
                    refreshShortcutHints()
                    return
                }
                val lastQueued = queuedTexts.removeLastOrNull() ?: return
                promptTextArea.text = lastQueued
                val psiBridge = com.github.catatafishen.agentbridge.psi.PsiBridgeService.getInstance(project)
                psiBridge.removeQueuedMessage(lastQueued)
                ApplicationManager.getApplication().invokeLater {
                    consolePanel.removeQueuedMessageByText(lastQueued)
                    refreshShortcutHints()
                }
            }
        }.registerCustomShortcutSet(
            com.intellij.openapi.actionSystem.CustomShortcutSet(
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_UP, 0)
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
        queuedTexts.addLast(rawText)
        refreshShortcutHints()
    }

    private fun onForceStopAndSend() {
        val rawText = promptTextArea.text.trim()
        if (rawText.isEmpty()) return
        if (isSending) {
            // Discard any pending nudge before stopping so setSendingState doesn't auto-send it
            val nudgeId = pendingNudgeId
            if (nudgeId != null) clearAndRemoveNudge(nudgeId)
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
        if (event !is java.awt.event.KeyEvent) return false
        if (editor.isDisposed) return false
        if (event.id != java.awt.event.KeyEvent.KEY_PRESSED) return false
        if (KeyStroke.getKeyStrokeForEvent(event) !in pasteStrokes) return false
        val focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        if (!SwingUtilities.isDescendingFrom(focused, contentComponent)) return false

        // Image and file pastes take precedence over the smart-paste-to-scratch text path.
        // They are handled regardless of the smart-paste setting because they have no
        // sensible "insert as plain text" fallback in the prompt editor.
        if (handleImageOrFilePaste(event)) return true

        val chatInputSettings = com.github.catatafishen.agentbridge.settings.ChatInputSettings.getInstance()
        if (!chatInputSettings.isSmartPasteEnabled) return false

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

    /**
     * Detect a non-text clipboard payload (raster image or file list) and route it to
     * [pasteAttachmentHandler]. Returns true (and consumes [event]) when a payload was
     * handled — meaning callers must not fall through to text paste handling.
     *
     * Images take priority: many apps (e.g. browsers) put both the image bytes AND a
     * placeholder string on the clipboard; we want the image, not the string.
     */
    private fun handleImageOrFilePaste(event: java.awt.event.KeyEvent): Boolean {
        val clipboard = try {
            Toolkit.getDefaultToolkit().systemClipboard
        } catch (_: Exception) {
            return false
        }
        val contents = try {
            clipboard.getContents(null) ?: return false
        } catch (_: IllegalStateException) {
            return false
        }

        if (contents.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.imageFlavor)) {
            val image = try {
                contents.getTransferData(java.awt.datatransfer.DataFlavor.imageFlavor) as? Image
            } catch (_: Exception) {
                null
            }
            if (image != null) {
                event.consume()
                ApplicationManager.getApplication().executeOnPooledThread {
                    pasteAttachmentHandler.handleImagePaste(image)
                }
                return true
            }
        }

        if (contents.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) {
            @Suppress("UNCHECKED_CAST")
            val files = try {
                contents.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor)
                    as? List<java.io.File>
            } catch (_: Exception) {
                null
            }
            if (!files.isNullOrEmpty()) {
                event.consume()
                ApplicationManager.getApplication().executeOnPooledThread {
                    pasteAttachmentHandler.handleFilePaste(files)
                }
                return true
            }
        }

        return false
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

    /**
     * Mines the current turn's entries into semantic memory (async, non-blocking).
     * Called by PromptOrchestrator after each turn completes.
     */
    private fun mineEntriesAfterTurn(sessionId: String, agentName: String) {
        val settings = com.github.catatafishen.agentbridge.memory.MemorySettings.getInstance(project)
        if (!settings.isEnabled || !settings.isAutoMineOnTurnComplete) return

        val entries = chatConsolePanel.getEntries()
        if (entries.isEmpty()) return

        val tracker = com.github.catatafishen.agentbridge.memory.mining.MiningTracker.getInstance(project)
        tracker.startTurnMining()

        val miner = com.github.catatafishen.agentbridge.memory.mining.TurnMiner(project)
        miner.mineTurn(entries, sessionId, agentName)
            .whenComplete { _, _ -> tracker.stop() }
    }

    private fun restoreConversation(onComplete: () -> Unit = {}) {
        ApplicationManager.getApplication().executeOnPooledThread {
            V1ToV2Migrator.migrateIfNeeded(project.basePath)
            val result = conversationStore.loadRecentEntries(project.basePath)
            val entries = result?.entries() ?: emptyList()
            val hasMoreOnDisk = result?.hasMoreOnDisk() ?: false
            ApplicationManager.getApplication().invokeLater {
                restoreEntries(entries, hasMoreOnDisk)
                onComplete()
            }
        }
    }

    private fun restoreEntries(entries: List<EntryData>, hasMoreOnDisk: Boolean) {
        if (entries.isEmpty()) return
        val histSettings = ChatHistorySettings.getInstance(project)
        chatConsolePanel.setDomMessageLimit(histSettings.domMessageLimit)
        conversationReplayer.loadAndSplit(entries, histSettings.recentTurnsOnRestore, hasMoreOnDisk)
        chatConsolePanel.appendEntries(
            conversationReplayer.recentEntries(),
            conversationReplayer.totalPromptCount()
        )
        showDeferredRestoreCount()
        restoreTurnStats(entries.filterIsInstance<EntryData.TurnStats>())
        persistedEntryCount = conversationReplayer.totalLoadedCount()
    }

    private fun showDeferredRestoreCount() {
        val deferred = conversationReplayer.remainingPromptCount()
        if (deferred > 0) chatConsolePanel.showLoadMore(deferred)
    }

    private fun restoreTurnStats(turnStatsList: List<EntryData.TurnStats>) {
        val lastStats = turnStatsList.lastOrNull() ?: return
        if (!::processingTimerPanel.isInitialized) return
        restoreBillingCounters(turnStatsList)
        processingTimerPanel.restoreSessionStats(
            ProcessingTimerPanel.RestoredSessionStats(
                totalTimeMs = lastStats.totalDurationMs,
                totalInputTokens = lastStats.totalInputTokens,
                totalOutputTokens = lastStats.totalOutputTokens,
                totalCostUsd = lastStats.totalCostUsd,
                totalToolCalls = lastStats.totalToolCalls,
                totalLinesAdded = lastStats.totalLinesAdded,
                totalLinesRemoved = lastStats.totalLinesRemoved,
                turnCount = turnStatsList.size
            )
        )
        processingTimerPanel.restoreLastTurnStats(
            ProcessingTimerPanel.RestoredLastTurnStats(
                elapsedSec = lastStats.durationMs / 1000,
                inputTokens = lastStats.inputTokens.toInt(),
                outputTokens = lastStats.outputTokens.toInt(),
                costUsd = if (lastStats.costUsd > 0.0) lastStats.costUsd else null,
                toolCalls = lastStats.toolCallCount,
                linesAdded = lastStats.linesAdded,
                linesRemoved = lastStats.linesRemoved,
                multiplier = lastStats.multiplier
            )
        )
    }

    private fun restoreBillingCounters(turnStatsList: List<EntryData.TurnStats>) {
        val totalPremium = turnStatsList.sumOf {
            BillingCalculator.parseMultiplier(it.multiplier.ifEmpty { "1x" })
        }
        billing.restoreSessionCounters(turnStatsList.size, totalPremium)
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
        if (remaining > 0) {
            chatConsolePanel.showLoadMore(remaining)
        } else {
            if (conversationReplayer.hasOlderHistoryOnDisk) {
                LOG.info("Older history exists on disk but was not loaded (session too large for tail-read budget)")
            }
            chatConsolePanel.hideLoadMore()
        }
    }

    fun getComponent(): JComponent = rootSplitter

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
        ApplicationManager.getApplication().invokeLater {
            val frame = com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project) ?: return@invokeLater
            if (frame.isActive) return@invokeLater
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
        // Mine remaining entries before archiving (safety net for missed turns)
        val settings = com.github.catatafishen.agentbridge.memory.MemorySettings.getInstance(project)
        if (settings.isEnabled && settings.isAutoMineOnSessionArchive) {
            val entries = chatConsolePanel.getEntries()
            if (entries.isNotEmpty()) {
                val tracker = com.github.catatafishen.agentbridge.memory.mining.MiningTracker.getInstance(project)
                tracker.startTurnMining()
                val sessionId = conversationStore.getCurrentSessionId(project.basePath)
                val miner = com.github.catatafishen.agentbridge.memory.mining.TurnMiner(project)
                miner.mineTurn(entries, sessionId, agentManager.activeProfile.displayName)
                    .whenComplete { _, _ -> tracker.stop() }
            }
        }
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

        val startGeneration = modelLoadGeneration
        var lastError: Exception? = null
        for (attempt in 1..3) {
            if (attempt > 1) {
                Thread.sleep(2000L)
                // If the user disconnected during the sleep, abort rather than restarting the agent.
                if (modelLoadGeneration != startGeneration) return emptyList()
            }
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

    private fun isCLINotFoundError(e: Exception): Boolean = PromptErrorClassifier.isCLINotFoundError(e)

    /** Tree node for the Plans tab — display name is shown in the tree. */
    private class FileTreeNode(
        fileName: String
    ) : javax.swing.tree.DefaultMutableTreeNode("\uD83D\uDCC4 $fileName")
}
