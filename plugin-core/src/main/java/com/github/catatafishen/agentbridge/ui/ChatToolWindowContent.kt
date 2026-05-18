package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.acp.model.Model
import com.github.catatafishen.agentbridge.acp.model.SessionUpdate
import com.github.catatafishen.agentbridge.psi.review.AgentEditSession
import com.github.catatafishen.agentbridge.services.*
import com.github.catatafishen.agentbridge.session.SessionSwitchService
import com.github.catatafishen.agentbridge.session.db.ConversationService
import com.github.catatafishen.agentbridge.session.migration.V1ToV2Migrator
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
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.SystemInfo
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
        it.setResizeEnabled(false)
        // Suppress the 1px divider line without setting dividerWidth=0 (which breaks layout).
        // setBlindZone makes OnePixelDivider fill a bounds rect shrunken by the insets; with a
        // top inset larger than any screen height the bounds.height goes negative, making
        // fillRect a no-op — nothing is drawn. Short.MAX_VALUE (×4 DPI = 131k px) is safe.
        it.setBlindZone { JBUI.insets(Short.MAX_VALUE.toInt(), 0, 0, 0) }
        it.secondComponent = mainPanel
        it.setHonorComponentsMinimumSize(false)
        // When the tool window is resized (by dragging its border), keep the chat pane
        // at its current width and let the sidebar absorb the size change.
        it.dividerPositionStrategy = com.intellij.openapi.ui.Splitter.DividerPositionStrategy.KEEP_SECOND_SIZE
    }

    /** Proportion used when expanding the review panel after it was collapsed. */
    private val defaultReviewProportion = 0.3f

    /** Wrapper panels for per-tab ContentManager contents, indexed by tab index. Non-empty iff side panel is open. */
    private val contentWrappers = mutableListOf<JPanel>()

    /** Listener that syncs ContentManager tab selection to the side panel. Null when side panel is closed. */
    private var contentTabListener: com.intellij.ui.content.ContentManagerListener? = null

    /** True while [updateSideTabContents] is rebuilding the ContentManager — prevents listener re-entrance. */
    @Volatile
    private var isUpdatingContentTabs = false
    private val agentManager = ActiveAgentManager.getInstance(project)
    private lateinit var connectPanel: AcpConnectPanel
    private var chatPanel: JComponent? = null
    private var chatSessionInitialized = false

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

    /** Tracks whether the current pause was triggered by auto-pause-on-input-focus logic. */
    @Volatile
    private var pausedByInputFocus = false

    @Volatile
    private var isSending = false

    @Volatile
    private var activeBubbleId: String? = null

    /** Human-typed portion of the pending nudge bubble — for restore-to-input when a turn ends unhandled. */
    @Volatile
    private var pendingHumanText: String? = null
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
    private lateinit var broadcastPanel: BroadcastChatPanel
    private lateinit var responsePanelContainer: JBPanel<JBPanel<*>>
    private var copilotBanner: AuthSetupBanner? = null
    private var statusBanner: StatusBanner? = null
    private var inlineAuthProcess: Process? = null

    private val conversationStore = ConversationService.getInstance(project)
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
        subscribeToToolWindowFocus()
        // Initialise the session store's agent name from the currently active profile.
        conversationStore.setCurrentAgent(agentManager.activeProfile.displayName)
    }

    /**
     * Repaints the send button when the tool window gains or loses focus,
     * toggling between primary (blue + white icon) and normal styling.
     */
    private fun subscribeToToolWindowFocus() {
        val listener: com.intellij.openapi.wm.ex.ToolWindowManagerListener =
            object : com.intellij.openapi.wm.ex.ToolWindowManagerListener {
                override fun stateChanged(toolWindowManager: com.intellij.openapi.wm.ToolWindowManager) {
                    if (::innerInputToolbar.isInitialized) {
                        innerInputToolbar.updateActionsAsync()
                    }
                }
            }
        project.messageBus.connect().subscribe(
            com.intellij.openapi.wm.ex.ToolWindowManagerListener.TOPIC,
            listener
        )
    }

    /**
     * Registers expand/toggle callbacks with {@link ReviewPanelController} so non-UI code
     * (e.g. AgentEditSession gating notifications) can drive the splitter without reaching
     * into this class directly. The expand handler also selects the Review tab so callers
     * that request "show me the review" don't land on an unrelated tab.
     */
    private fun registerReviewPanelHandlers() {
        val expand = Runnable {
            ensureSidePanelAvailable()
            sidePanel?.selectReviewTab()
            if (rootSplitter.proportion < 0.01f) {
                rootSplitter.proportion = defaultReviewProportion
                updateSideTabContents(true)
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
            NativeUiToggleAction(),
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
        ensureSidePanelAvailable()
        if (!chatSessionInitialized) {
            archiveConversation()
            // Set agent color immediately so it is queued in pendingJs before the browser loads.
            // Without this there is a race: the browser becomes ready (pendingJs flushed empty) before
            // addSeparatorNow runs, so a message sent in that window shows the default color.
            consolePanel.setCurrentAgent(
                agentManager.activeProfile.displayName,
                agentManager.activeProfile.id,
                agentManager.activeProfile.clientCssClass
            )
            chatSessionInitialized = true
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
                val cmd = if (SystemInfo.isWindows)
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

    private fun ensureSidePanelAvailable() {
        if (sidePanel != null) return
        val panel = createPromptTab()
        chatPanel = panel
        mainPanel.add(panel, CARD_CHAT)
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
        val splitPanel = createResizableSplitPanel(topPanel, bottomSection, inputSection, sideButtonsPanel)
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
        val side =
            com.github.catatafishen.agentbridge.ui.side.SidePanel(project, chatConsolePanel, sessionStatsPanel).apply {
                border = JBUI.Borders.empty(4)
            }
        com.intellij.openapi.util.Disposer.register(toolWindow.disposable, side)
        sidePanel = side
        side.setOnPlanTitleChanged { newTitle ->
            if (contentWrappers.isNotEmpty()) {
                toolWindow.contentManager.getContent(com.github.catatafishen.agentbridge.ui.side.SidePanel.TAB_TODOS)
                    ?.displayName = newTitle
            }
            ActivityTracker.getInstance().inc()
        }
        rootSplitter.firstComponent = side
        restoreSidePanelOpenState()

        // When the agent calls query_conversation_history with follow-agent enabled, open the side panel.
        PromptDbService.getInstance(project).registerShowPanelCallback {
            if (rootSplitter.proportion < 0.01f) {
                rootSplitter.proportion = defaultReviewProportion
                com.intellij.ide.util.PropertiesComponent.getInstance(project)
                    .setValue(PREF_SIDE_PANEL_OPEN, true)
                updateSideTabContents(true)
            }
        }
        com.intellij.openapi.util.Disposer.register(toolWindow.disposable) {
            PromptDbService.getInstance(project).registerShowPanelCallback(null)
        }
    }

    private fun restoreSidePanelOpenState() {
        val props = com.intellij.ide.util.PropertiesComponent.getInstance(project)
        if (props.getBoolean(PREF_SIDE_PANEL_OPEN, false)) {
            rootSplitter.proportion = defaultReviewProportion
            updateSideTabContents(true)
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
            // Repaint when focus moves so the border colour reflects toolWindow.isActive
            // in sync with the button's isDefaultButton() repaint (same trigger).
            private val focusSync = java.beans.PropertyChangeListener { repaint() }

            override fun addNotify() {
                super.addNotify()
                KeyboardFocusManager.getCurrentKeyboardFocusManager()
                    .addPropertyChangeListener("focusOwner", focusSync)
            }

            override fun removeNotify() {
                KeyboardFocusManager.getCurrentKeyboardFocusManager()
                    .removePropertyChangeListener("focusOwner", focusSync)
                super.removeNotify()
            }

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g.create() as Graphics2D
                try {
                    paintInputSectionBackground(g2, sideRailWidth(), toolWindow.isActive)
                } finally {
                    g2.dispose()
                }
            }
        }.apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 0, 4, 4)
            add(sideButtonsPanel, BorderLayout.WEST)
            add(inputRow, BorderLayout.CENTER)
        }
    }

    private fun JComponent.paintInputSectionBackground(g2: Graphics2D, sideRailWidth: Int, isActive: Boolean) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val arc = JBUI.scale(8)
        g2.color = com.intellij.util.ui.UIUtil.getTextFieldBackground()
        g2.fillRoundRect(0, 0, width, height, arc, arc)
        paintInputSectionDivider(g2, sideRailWidth)
        if (isActive) {
            g2.color = JBUI.CurrentTheme.Focus.defaultButtonColor()
            g2.stroke = BasicStroke(JBUI.scale(2).toFloat())
            // Inset by 1px so the 2px stroke (centred on the path) stays fully within the component.
            g2.drawRoundRect(1, 1, width - 2, height - 2, arc, arc)
        } else {
            g2.stroke = BasicStroke(1.0f)
            g2.color = UIManager.getColor("Component.borderColor") ?: JBUI.CurrentTheme.ToolWindow.borderColor()
            g2.drawRoundRect(1, 1, width - 2, height - 2, arc, arc)
        }
        paintNwCornerGrip(g2, isActive)
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

    /** Paints three small dots in the NW corner as a visual cue that this corner is draggable. */
    private fun paintNwCornerGrip(g2: Graphics2D, isActive: Boolean) {
        val baseColor = if (isActive) {
            JBUI.CurrentTheme.Focus.defaultButtonColor()
        } else {
            UIManager.getColor("Component.borderColor") ?: JBUI.CurrentTheme.ToolWindow.borderColor()
        }
        val saved = g2.composite
        g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.63f)
        g2.color = baseColor
        val dot = JBUI.scale(2)
        val gap = JBUI.scale(3)
        val off = JBUI.scale(6)
        // Three dots in a triangular NW arrangement:
        //  ● ●
        //  ●
        g2.fillRect(off, off, dot, dot)
        g2.fillRect(off + dot + gap, off, dot, dot)
        g2.fillRect(off, off + dot + gap, dot, dot)
        g2.composite = saved
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
        inputSection: JComponent,
        sideButtonsPanel: JComponent
    ): JBPanel<JBPanel<*>> {
        val splitPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply { isOpaque = false }
        val props = com.intellij.ide.util.PropertiesComponent.getInstance(project)
        installInputResizeHandler(inputSection, bottomSection, splitPanel, sideButtonsPanel, props)
        installSavedInputHeight(splitPanel, bottomSection, props.getInt(PREF_INPUT_PANEL_HEIGHT, 0))
        splitPanel.add(topPanel, BorderLayout.CENTER)
        splitPanel.add(bottomSection, BorderLayout.SOUTH)
        return splitPanel
    }

    private fun installInputResizeHandler(
        inputSection: JComponent,
        bottomSection: JComponent,
        splitPanel: JComponent,
        sideButtonsPanel: JComponent,
        props: com.intellij.ide.util.PropertiesComponent
    ) {
        // N resize zone: covers the full 8px top inset of inputSection (comfortable grab area).
        val nDragZone = JBUI.scale(8)
        // W resize zone: 8px from the left edge, applied to both the outer gap and sideButtonsPanel.
        val wDragZone = JBUI.scale(8)
        // NW corner: x ≤ nwCornerSize within the top inset triggers NW (wider than W-only zone).
        val nwCornerSize = JBUI.scale(20)
        // How far into sideButtonsPanel (y coords) the NW corner extends below the top inset.
        val nwExtendedY = JBUI.scale(12)

        // --- Shared drag state ---
        var heightDragStart: Pair<Int, Int>? = null  // (startScreenY, startHeight)
        var widthDragStart: Pair<Int, Int>? = null   // (startScreenX, startSideWidth)

        fun startWidthDrag(screenX: Int) {
            val sideWidth = rootSplitter.firstComponent?.width ?: 0
            widthDragStart = Pair(screenX, sideWidth)
        }

        fun applyWidthDrag(screenX: Int) {
            widthDragStart?.let { (startX, startSideWidth) ->
                val deltaX = screenX - startX
                val totalWidth = rootSplitter.width.takeIf { it > 0 } ?: return@let
                rootSplitter.proportion =
                    ((startSideWidth + deltaX).toFloat() / totalWidth).coerceIn(0.0f, 0.9f)
            }
        }

        fun applyHeightDrag(screenY: Int) {
            heightDragStart?.let { (startY, startH) ->
                val delta = startY - screenY
                bottomSection.preferredSize = Dimension(
                    bottomSection.width,
                    (startH + delta).coerceIn(minInputHeight(), maxInputHeight(splitPanel.height))
                )
                splitPanel.revalidate()
            }
        }

        // N (and NW corner) handler — attached to inputSection.
        // The 8px top inset has no children so events in y=0..7 go directly here.
        // The NW corner (x ≤ nwCornerSize, y ≤ nDragZone) triggers both height and width drag.
        val nResizeHandler = object : java.awt.event.MouseAdapter() {
            override fun mouseMoved(e: java.awt.event.MouseEvent) {
                inputSection.cursor = when {
                    e.y <= nDragZone && e.x <= nwCornerSize ->
                        Cursor.getPredefinedCursor(Cursor.NW_RESIZE_CURSOR)

                    e.y <= nDragZone ->
                        Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)

                    else -> Cursor.getDefaultCursor()
                }
            }

            override fun mousePressed(e: java.awt.event.MouseEvent) {
                if (e.y > nDragZone) return
                if (e.x <= nwCornerSize) {
                    heightDragStart = Pair(e.locationOnScreen.y, bottomSection.height)
                    startWidthDrag(e.locationOnScreen.x)
                } else {
                    heightDragStart = Pair(e.locationOnScreen.y, bottomSection.height)
                }
            }

            override fun mouseDragged(e: java.awt.event.MouseEvent) {
                applyHeightDrag(e.locationOnScreen.y)
                applyWidthDrag(e.locationOnScreen.x)
            }

            override fun mouseReleased(e: java.awt.event.MouseEvent) {
                if (heightDragStart != null) props.setValue(PREF_INPUT_PANEL_HEIGHT, bottomSection.height, 0)
                heightDragStart = null
                widthDragStart = null
            }

            override fun mouseExited(e: java.awt.event.MouseEvent) {
                if (heightDragStart == null) inputSection.cursor = Cursor.getDefaultCursor()
            }
        }
        inputSection.addMouseMotionListener(nResizeHandler)
        inputSection.addMouseListener(nResizeHandler)

        // W handler on sideButtonsPanel — covers the visible left border of inputSection.
        // The top nwExtendedY pixels of sideButtonsPanel (directly below the top inset) extend
        // the NW corner downward so the visible corner of the input box triggers NW drag.
        val wSideHandler = object : java.awt.event.MouseAdapter() {
            override fun mouseMoved(e: java.awt.event.MouseEvent) {
                sideButtonsPanel.cursor = when {
                    e.x > wDragZone -> Cursor.getDefaultCursor()
                    e.y <= nwExtendedY -> Cursor.getPredefinedCursor(Cursor.NW_RESIZE_CURSOR)
                    else -> Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR)
                }
            }

            override fun mousePressed(e: java.awt.event.MouseEvent) {
                if (e.x > wDragZone) return
                startWidthDrag(e.locationOnScreen.x)
                if (e.y <= nwExtendedY) {
                    heightDragStart = Pair(e.locationOnScreen.y, bottomSection.height)
                }
            }

            override fun mouseDragged(e: java.awt.event.MouseEvent) {
                applyWidthDrag(e.locationOnScreen.x)
                applyHeightDrag(e.locationOnScreen.y)
            }

            override fun mouseReleased(e: java.awt.event.MouseEvent) {
                if (heightDragStart != null) props.setValue(PREF_INPUT_PANEL_HEIGHT, bottomSection.height, 0)
                widthDragStart = null
                heightDragStart = null
            }

            override fun mouseExited(e: java.awt.event.MouseEvent) {
                if (widthDragStart == null) sideButtonsPanel.cursor = Cursor.getDefaultCursor()
            }
        }
        sideButtonsPanel.addMouseMotionListener(wSideHandler)
        sideButtonsPanel.addMouseListener(wSideHandler)

        // W/NW handler on bottomSection — covers the 8px outer gap to the left of inputSection.
        val wResizeHandler = object : java.awt.event.MouseAdapter() {
            override fun mouseMoved(e: java.awt.event.MouseEvent) {
                if (e.x > wDragZone) {
                    if (widthDragStart == null) bottomSection.cursor = Cursor.getDefaultCursor()
                    return
                }
                bottomSection.cursor = if (e.y <= nDragZone) {
                    Cursor.getPredefinedCursor(Cursor.NW_RESIZE_CURSOR)
                } else {
                    Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR)
                }
            }

            override fun mousePressed(e: java.awt.event.MouseEvent) {
                if (e.x > wDragZone) return
                startWidthDrag(e.locationOnScreen.x)
                if (e.y <= nDragZone) {
                    heightDragStart = Pair(e.locationOnScreen.y, bottomSection.height)
                }
            }

            override fun mouseDragged(e: java.awt.event.MouseEvent) {
                applyWidthDrag(e.locationOnScreen.x)
                applyHeightDrag(e.locationOnScreen.y)
            }

            override fun mouseReleased(e: java.awt.event.MouseEvent) {
                if (heightDragStart != null) props.setValue(PREF_INPUT_PANEL_HEIGHT, bottomSection.height, 0)
                widthDragStart = null
                heightDragStart = null
            }

            override fun mouseExited(e: java.awt.event.MouseEvent) {
                if (widthDragStart == null) bottomSection.cursor = Cursor.getDefaultCursor()
            }
        }
        bottomSection.addMouseMotionListener(wResizeHandler)
        bottomSection.addMouseListener(wResizeHandler)
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

    private fun createOrchestratorCallbacks() = PromptOrchestratorCallbacks(
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
            // Remove the LAST matching entry so that when the same text was queued multiple
            // times, "recall most recent queued message" ordering remains intact (Up-arrow
            // restores the oldest copies first, newest copies last).
            val lastMatchingIndex = queuedTexts.lastIndexOf(text)
            if (lastMatchingIndex >= 0) queuedTexts.removeAt(lastMatchingIndex)
            ApplicationManager.getApplication().invokeLater { refreshShortcutHints() }
        },
    )

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
            createOrchestratorCallbacks()
        )

        // Shortcut hint bar — initialized here so input wiring below can reference it.
        shortcutHintPanel = PromptShortcutHintPanel()
        shortcutHintPanel.isVisible =
            com.github.catatafishen.agentbridge.settings.ChatInputSettings.getInstance().isShowShortcutHints

        promptTextArea.addSettingsProvider { editor ->
            setupPromptDragDrop(editor)
            setupPromptKeyBindings(editor)
            setupPromptContextMenu(editor)
            editor.setPlaceholder(promptPlaceholder())
            editor.setShowPlaceholderWhenFocused(true)
            editor.settings.isUseSoftWraps =
                com.github.catatafishen.agentbridge.settings.ChatInputSettings.getInstance().isSoftWrapsEnabled
            editor.setBorder(null)
            editor.scrollPane.verticalScrollBar.preferredSize =
                Dimension(JBUI.scale(10), editor.scrollPane.verticalScrollBar.preferredSize.height)
            editor.contentComponent.addFocusListener(object : java.awt.event.FocusAdapter() {
                override fun focusGained(e: java.awt.event.FocusEvent) {
                    val s = ChatInputSettings.getInstance()
                    if (s.isPauseOnInputFocus()) {
                        val pauseService = McpPauseService.getInstance(project)
                        if (!pauseService.isPaused()) {
                            pausedByInputFocus = true
                            pauseService.setPaused(true)
                        }
                    }
                }

                override fun focusLost(e: java.awt.event.FocusEvent) {
                    if (pausedByInputFocus) {
                        pausedByInputFocus = false
                        McpPauseService.getInstance(project).setPaused(false)
                    }
                }
            })
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
        footerGroup.add(ModelSelectorAction())
        footerGroup.add(SendAction())

        innerInputToolbar = ActionManager.getInstance().createActionToolbar("AgentInputFooter", footerGroup, true)
        innerInputToolbar.layoutStrategy = ToolbarLayoutStrategy.NOWRAP_STRATEGY
        innerInputToolbar.isReservePlaceAutoPopupIcon = true
        innerInputToolbar.component.isOpaque = false
        innerInputToolbar.component.border = JBUI.Borders.empty()

        val footerPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            add(shortcutHintPanel, BorderLayout.CENTER)
            add(innerInputToolbar.component, BorderLayout.EAST)
        }
        row.add(footerPanel, BorderLayout.SOUTH)

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
        if (rawText.isEmpty()) {
            showEmptyPromptWarning()
            return
        }
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
        // Always clear pause state when the user sends a message — a blocked MCP thread must be
        // unblocked regardless of whether the pause feature is currently enabled in settings.
        pausedByInputFocus = false
        McpPauseService.getInstance(project).setPaused(false)
        ApplicationManager.getApplication().executeOnPooledThread {
            promptOrchestrator.execute(prompt, contextItems, selectedModelId, rawText, entryId)
        }
    }

    private fun showEmptyPromptWarning() {
        JBPopupFactory.getInstance()
            .createHtmlTextBalloonBuilder(
                "Write a prompt to the coding agent first",
                com.intellij.openapi.ui.MessageType.WARNING,
                null
            )
            .setFadeoutTime(3000)
            .createBalloon()
            .show(
                com.intellij.ui.awt.RelativePoint.getCenterOf(promptTextArea),
                com.intellij.openapi.ui.popup.Balloon.Position.above
            )
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

    /** Submits a human nudge to the pending queue, which triggers the nudge listener to show the bubble. */
    private fun submitNudge(text: String) {
        AgentNudgeService.getInstance(project).addNudge(text, NudgeSource.HUMAN, true)
        refreshShortcutHints()
    }

    /** Cancels the pending nudge in the service; the nudge listener handles bubble removal. */
    private fun clearAndRemoveNudge(nudgeId: String) {
        AgentNudgeService.getInstance(project).cancelNudge(nudgeId)
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
        if (activeBubbleId != null || queuedTexts.isNotEmpty()) {
            list += KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_UP, 0) to "Edit last"
        }
        shortcutHintPanel.setShortcuts(list)
        shortcutHintPanel.isVisible = ChatInputSettings.getInstance().isShowShortcutHints
    }

    private fun setSendingState(sending: Boolean) {
        isSending = sending
        ChatWebServer.getInstance(project)?.setAgentRunning(sending)
        if (!sending) {
            McpPauseService.getInstance(project).setPaused(false)
            restoreUnhandledNudgeIfNeeded()
        }
        ApplicationManager.getApplication().invokeLater {
            updatePromptPlaceholder()
            controlsToolbar.updateActionsAsync()
            innerInputToolbar.updateActionsAsync()
            refreshShortcutHints()
            updateProcessingTimer(sending)
        }
    }

    private fun restoreUnhandledNudgeIfNeeded() {
        val bubbleId = activeBubbleId ?: return
        // Capture human-typed text before clearing — reprimand text should not be restored to input.
        val humanText = pendingHumanText
        activeBubbleId = null
        pendingHumanText = null
        val nudgeService = AgentNudgeService.getInstance(project)
        // Clear human nudges from the service (silent — no listener events).
        // Any pending reprimand stays so it is silently injected at the start of the next turn.
        nudgeService.clearHumanNudges()
        ApplicationManager.getApplication().invokeLater {
            consolePanel.removeNudgeBubble(bubbleId)
            humanText?.let { restoreUnhandledNudgeText(it) }
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
        leftGroup.add(AttachContextDropdownAction())
        leftGroup.add(DisconnectOrStopAction())
        leftGroup.add(PauseToggleAction())

        controlsToolbar = ActionManager.getInstance().createActionToolbar(
            "AgentControls", leftGroup, false
        )
        controlsToolbar.isReservePlaceAutoPopupIcon = false
        controlsToolbar.component.border = JBUI.Borders.empty(8, 4, 4, 0)
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

    /**
     * Pause/resume button that defers incoming MCP tool calls.
     *
     * Three visual states track the full lifecycle:
     * - [McpPauseService.PauseState.RUNNING]  → Pause icon, enabled — click to pause
     * - [McpPauseService.PauseState.PENDING]  → Pause icon, enabled — click to cancel the pending pause
     * - [McpPauseService.PauseState.PAUSED]   → Resume icon, enabled — click to unblock
     */
    private inner class PauseToggleAction : AnAction() {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            if (!isSending) {
                e.presentation.isVisible = false
                return
            }
            e.presentation.isVisible = true
            val state = McpPauseService.getInstance(project).getPauseState()
            // Show a highlighted (pressed) background whenever pause is active or pending,
            // so the user can see at a glance that the button is "on".
            Toggleable.setSelected(e.presentation, state != McpPauseService.PauseState.RUNNING)
            when (state) {
                McpPauseService.PauseState.RUNNING -> {
                    e.presentation.icon = AllIcons.Actions.Pause
                    e.presentation.text = "Pause Agent"
                    e.presentation.description =
                        "Defer the next tool call so you can review and send a nudge before it runs"
                    e.presentation.isEnabled = true
                }

                McpPauseService.PauseState.PENDING -> {
                    e.presentation.icon = AllIcons.Actions.Pause
                    e.presentation.text = "Pausing…"
                    e.presentation.description = "Waiting for the agent to make a tool call — click to cancel"
                    e.presentation.isEnabled = true
                }

                McpPauseService.PauseState.PAUSED -> {
                    e.presentation.icon = AllIcons.Actions.Resume
                    e.presentation.text = "Resume Agent"
                    e.presentation.description = "Unblock the deferred tool call and continue execution"
                    e.presentation.isEnabled = true
                }
            }
        }

        override fun actionPerformed(e: AnActionEvent) {
            val service = McpPauseService.getInstance(project)
            service.setPaused(!service.isPaused())
        }
    }

    private inner class SendAction : AnAction(), com.intellij.openapi.actionSystem.ex.CustomComponentAction {
        private val sendIcon = com.intellij.openapi.util.IconLoader.getIcon(
            "/icons/send.svg", SendAction::class.java
        )

        // keepBrightness=false ensures a true white icon, not a brightness-preserved grey.
        private val sendIconWhite = com.intellij.util.IconUtil.colorize(
            sendIcon, JBColor.WHITE, keepGray = false, keepBrightness = false
        )

        // Selects white or normal send icon at paint time based on the button's current
        // isDefaultButton() state. This keeps the icon colour in sync with the blue button
        // background without depending on the async action-update cycle.
        private val adaptiveIcon = object : Icon {
            override fun getIconWidth() = sendIcon.iconWidth
            override fun getIconHeight() = sendIcon.iconHeight
            override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
                val icon = if ((c as? JButton)?.isDefaultButton == true) sendIconWhite else sendIcon
                icon.paintIcon(c, g, x, y)
            }
        }

        // Captured at createCustomComponent time so showSendDropdown has a stable popup anchor.
        private var sendButton: JButton? = null

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
            // Compact icon-only button keeps the footer from growing taller than the
            // dropdowns beside it while still exposing the action through the tooltip.
            val button = object : JButton(adaptiveIcon) {
                override fun isDefaultButton(): Boolean = toolWindow.isActive

                // Repaint immediately when focus moves between components so isDefaultButton()
                // (which checks toolWindow.isActive) is re-evaluated without waiting for the
                // async action-update cycle that otherwise drives the blue↔grey transition.
                private val focusSync = java.beans.PropertyChangeListener { repaint() }

                override fun addNotify() {
                    super.addNotify()
                    KeyboardFocusManager.getCurrentKeyboardFocusManager()
                        .addPropertyChangeListener("focusOwner", focusSync)
                }

                override fun removeNotify() {
                    KeyboardFocusManager.getCurrentKeyboardFocusManager()
                        .removePropertyChangeListener("focusOwner", focusSync)
                    super.removeNotify()
                }
            }
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
                // Don't override icon — adaptiveIcon (set at creation) picks white/normal at
                // paint time based on isDefaultButton(), keeping colour in sync with the fill.
                btn.toolTipText = presentation.description
            }
        }

        override fun update(e: AnActionEvent) {
            // Icon colour is handled by adaptiveIcon at paint time — no async delay.
            if (isSending && !consolePanel.hasPendingAskUserRequest()) {
                e.presentation.text = ""
                e.presentation.description = "Nudge, queue, or stop and send"
            } else {
                e.presentation.text = ""
                val isLoggedIn = authService.pendingAuthError == null
                e.presentation.description = if (isLoggedIn) "Send prompt (Enter)" else "Sign in to Copilot first"
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
            group.addSeparator()
            group.add(object :
                AnAction("Stop and Send", "Stop the current agent and send this prompt", AllIcons.Actions.Suspend) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = hasText && authService.pendingAuthError == null
                }

                override fun actionPerformed(e: AnActionEvent) = onForceStopAndSend()
            })
            val popup = JBPopupFactory.getInstance()
                .createActionGroupPopup(
                    null,
                    group,
                    DataContext.EMPTY_CONTEXT,
                    JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                    false
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
                ensureSidePanelAvailable()
                // When showing: record the current tool window width (= chat width when side is hidden),
                // then expand the tool window by the side panel width so the chat area stays the same size.
                val chatWidth = rootSplitter.width
                rootSplitter.proportion = defaultReviewProportion
                if (chatWidth > 0) {
                    val stretchAmount = (chatWidth * defaultReviewProportion / (1.0 - defaultReviewProportion)).toInt()
                    (toolWindow as? com.intellij.openapi.wm.ex.ToolWindowEx)?.stretchWidth(stretchAmount)
                }
                updateSideTabContents(true)
            } else {
                // When hiding: record the current side panel width, collapse it,
                // then shrink the tool window by that width to restore the original chat area size.
                val sideWidth = rootSplitter.firstComponent?.width ?: 0
                rootSplitter.proportion = 0.0f
                if (sideWidth > 0) {
                    (toolWindow as? com.intellij.openapi.wm.ex.ToolWindowEx)?.stretchWidth(-sideWidth)
                }
                updateSideTabContents(false)
            }
            com.intellij.ide.util.PropertiesComponent.getInstance(project)
                .setValue(PREF_SIDE_PANEL_OPEN, state)
        }
    }

    /**
     * Switches between single-content mode (side panel closed) and multi-content
     * tab mode (side panel open). In tab mode, each tab becomes a native IntelliJ
     * {@link com.intellij.ui.content.Content} tab rendered in the tool window header,
     * matching the look of the Commit/Version Control tool window.
     *
     * <p>{@code rootSplitter} is moved between wrapper panels via Swing re-parenting —
     * only the currently selected wrapper is in the view hierarchy, so the component moves
     * rather than being duplicated.
     *
     * <p>Must be called on the EDT.
     */
    private fun updateSideTabContents(open: Boolean) {
        isUpdatingContentTabs = true
        try {
            val contentManager = toolWindow.contentManager
            val contentFactory = com.intellij.ui.content.ContentFactory.getInstance()

            contentTabListener?.let { contentManager.removeContentManagerListener(it) }
            contentTabListener = null
            contentManager.removeAllContents(false)
            contentWrappers.clear()

            if (open) {
                toolWindow.title = ""
                val tabNames = com.github.catatafishen.agentbridge.ui.side.SidePanel.TAB_NAMES
                tabNames.forEachIndexed { i, name ->
                    val wrapper = JPanel(BorderLayout())
                    contentWrappers.add(wrapper)
                    val displayName = if (i == com.github.catatafishen.agentbridge.ui.side.SidePanel.TAB_TODOS)
                        (sidePanel?.getPlanTitle() ?: name) else name
                    val content = contentFactory.createContent(wrapper, displayName, false)
                    content.isCloseable = false
                    contentManager.addContent(content)
                }

                val activeIdx = (sidePanel?.getSelectedTab() ?: 0).coerceIn(0, contentWrappers.lastIndex)
                contentWrappers[activeIdx].add(rootSplitter, BorderLayout.CENTER)
                contentManager.setSelectedContent(contentManager.getContent(activeIdx)!!, false)

                val listener = object : com.intellij.ui.content.ContentManagerListener {
                    override fun selectionChanged(event: com.intellij.ui.content.ContentManagerEvent) {
                        if (!isUpdatingContentTabs
                            && event.operation == com.intellij.ui.content.ContentManagerEvent.ContentOperation.add
                        ) {
                            val idx = contentManager.getIndexOfContent(event.content)
                            if (idx >= 0) onContentTabSelected(idx)
                        }
                    }
                }
                contentTabListener = listener
                contentManager.addContentManagerListener(listener)
            } else {
                toolWindow.title = "AgentBridge"
                val content = contentFactory.createContent(rootSplitter, "", false)
                content.isCloseable = false
                contentManager.addContent(content)
            }
        } finally {
            isUpdatingContentTabs = false
        }
    }

    /** Handles a user clicking a native content tab: re-parents [rootSplitter] and switches the side panel. */
    private fun onContentTabSelected(tabIndex: Int) {
        isUpdatingContentTabs = true
        try {
            val wrapper = contentWrappers.getOrNull(tabIndex) ?: return
            if (rootSplitter.parent !== wrapper) {
                wrapper.add(rootSplitter, BorderLayout.CENTER)
                wrapper.revalidate()
                wrapper.repaint()
            }
            sidePanel?.selectTab(tabIndex)
        } finally {
            isUpdatingContentTabs = false
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
            if (::broadcastPanel.isInitialized) broadcastPanel.nativePanel.setAutoScroll(state)
        }
    }

    private inner class NativeUiToggleAction : ToggleAction(
        "Native UI",
        "Switch between the native Swing chat panel and the JCEF web panel",
        AllIcons.Actions.ToggleSoftWrap
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun isSelected(e: AnActionEvent): Boolean =
            if (::consolePanel.isInitialized) (consolePanel as? BroadcastChatPanel)?.isShowingNative()
                ?: false else false

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (::consolePanel.isInitialized) (consolePanel as? BroadcastChatPanel)?.toggle(state)
        }
    }

    /** ComboBoxAction for model selection — matches Run panel dropdown style. */
    private inner class ModelSelectorAction : ComboBoxAction() {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun createComboBoxButton(presentation: Presentation): ComboBoxButton {
            return super.createComboBoxButton(presentation).apply {
                isBorderPainted = false
                isContentAreaFilled = false
            }
        }

        override fun createPopupActionGroup(button: JComponent, context: DataContext): DefaultActionGroup {
            val group = DefaultActionGroup()
            val supportsMultiplier = agentManager.client.supportsMultiplier()
            loadedModels.forEachIndexed { index, model ->
                val cost = if (supportsMultiplier) getModelMultiplier(model.id()) else null
                group.add(createModelSelectionAction(model, index, cost))
            }
            return group
        }

        override fun createActionPopup(
            context: DataContext,
            component: JComponent,
            disposeCallback: Runnable?
        ): com.intellij.openapi.ui.popup.JBPopup {
            if (agentManager.client.supportsModelGrouping()) {
                return createGroupedPopup(disposeCallback)
            }
            return super.createActionPopup(context, component, disposeCallback)
        }

        private fun createGroupedPopup(disposeCallback: Runnable?): com.intellij.openapi.ui.popup.JBPopup {
            val models = loadedModels.toList()
            if (models.isEmpty()) {
                return com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createComponentPopupBuilder(
                    JBLabel("No models available"), null
                ).createPopup()
            }

            val favorites = com.github.catatafishen.agentbridge.ui.util.ModelFavorites.getInstance(project)
            val grouper = com.github.catatafishen.agentbridge.ui.util.ModelGrouper(favorites.toSet())
            val groups = grouper.group(models)

            val picker = ModelPickerPopup(groups)
            picker.onModelSelected = { index ->
                if (index != selectedModelIndex && index in loadedModels.indices) {
                    val model = loadedModels[index]
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
            }
            picker.onFavoriteToggled = { modelId ->
                favorites.toggle(modelId)
            }
            val popup = picker.createPopup()
            if (disposeCallback != null) {
                popup.addListener(object : com.intellij.openapi.ui.popup.JBPopupListener {
                    override fun onClosed(event: com.intellij.openapi.ui.popup.LightweightWindowEvent) {
                        disposeCallback.run()
                    }
                })
            }
            return popup
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
        val nativeChatPanel = NativeChatPanel(project)
        val bp = BroadcastChatPanel(chatConsolePanel, nativeChatPanel)
        broadcastPanel = bp
        consolePanel = bp
        bp.onLoadMoreRequested = ::onLoadMoreHistory
        chatConsolePanel.onCancelNudge = { id -> clearAndRemoveNudge(id) }
        nativeChatPanel.onCancelNudge = { id ->
            val text = AgentNudgeService.getInstance(project).getPendingNudgesText()
            if (!text.isNullOrEmpty()) promptTextArea.text = text
            clearAndRemoveNudge(id)
            refreshShortcutHints()
        }
        chatConsolePanel.onCancelQueuedMessage = { id, text ->
            val nudgeService = AgentNudgeService.getInstance(project)
            nudgeService.removeQueuedMessage(text)
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
        broadcastPanel.nativePanel.onAutoScrollDisabled = {
            autoScrollEnabled = false
            ActivityTracker.getInstance().inc()
        }
        broadcastPanel.nativePanel.onAutoScrollEnabled = {
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

        // Subscribe to nudge lifecycle events. The listener manages all nudge UI:
        // showing/updating the bubble, resolving it when consumed, and removing it when cancelled.
        val nudgeService = AgentNudgeService.getInstance(project)
        nudgeService.addListener(object : AgentNudgeService.Listener {
            override fun onNudgeAdded(entry: AgentNudgeService.NudgeEntry) {
                // Track pending human text synchronously so it isn't lost if the nudge is never shown.
                if (entry.source() == NudgeSource.HUMAN) {
                    pendingHumanText = AgentNudgeService.mergeNudges(pendingHumanText, entry.text())
                }
                if (!entry.showBubble()) return

                // IMPORTANT: activeBubbleId must be read INSIDE invokeLater (on the EDT), not here.
                // onNudgeAdded fires from a background thread. If multiple reprimands arrive before the
                // EDT processes any of the queued runnables, reading activeBubbleId here would return null
                // for every call — each would skip the "remove existing bubble" branch and post its own
                // showNudgeBubble, resulting in multiple visible bubbles. By reading activeBubbleId inside
                // invokeLater, each queued runnable sees the value written by the previous one, so at most
                // one bubble is visible at any time.
                ApplicationManager.getApplication().invokeLater {
                    val existingId = activeBubbleId
                    if (existingId != null) {
                        consolePanel.removeNudgeBubble(existingId)
                    }
                    activeBubbleId = entry.id()
                    // Use the service's current pending text. By the time the EDT runs, reprimand
                    // coalescing may have advanced past this entry, so getPendingNudgesText() reflects
                    // the most recent state. Falls back to entry.text() only when nudges were already
                    // consumed (the bubble will be immediately resolved by onNudgesInjected).
                    val mergedText = nudgeService.getPendingNudgesText() ?: entry.text()
                    consolePanel.showNudgeBubble(entry.id(), mergedText, entry.source())
                    refreshShortcutHints()
                }
            }

            override fun onNudgesInjected(entries: List<AgentNudgeService.NudgeEntry>, mergedText: String) {
                pendingHumanText = null
                val bubbleId = activeBubbleId ?: return
                activeBubbleId = null
                ApplicationManager.getApplication().invokeLater {
                    consolePanel.resolveNudgeBubble(bubbleId)
                    val source = entries.firstOrNull { it.source() == NudgeSource.HUMAN }?.source()
                        ?: entries.first().source()
                    consolePanel.addNudgeEntry(bubbleId, mergedText, source)
                    appendNewEntries()
                    refreshShortcutHints()
                }
            }

            override fun onNudgeCancelled(entry: AgentNudgeService.NudgeEntry) {
                if (entry.id() == activeBubbleId) {
                    activeBubbleId = null
                    pendingHumanText = null
                    ApplicationManager.getApplication().invokeLater {
                        consolePanel.removeNudgeBubble(entry.id())
                        refreshShortcutHints()
                    }
                }
            }
        })

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
                val hasPending = activeBubbleId != null || queuedTexts.isNotEmpty()
                e.presentation.isEnabledAndVisible = empty && hasPending
            }

            override fun actionPerformed(e: AnActionEvent) {
                if (promptTextArea.text.isNotEmpty()) return
                val nudgeId = activeBubbleId
                if (nudgeId != null) {
                    val nudgeText = AgentNudgeService.getInstance(project).getPendingNudgesText()
                    if (!nudgeText.isNullOrEmpty()) promptTextArea.text = nudgeText
                    clearAndRemoveNudge(nudgeId)
                    refreshShortcutHints()
                    return
                }
                val lastQueued = queuedTexts.removeLastOrNull() ?: return
                promptTextArea.text = lastQueued
                val nudgeService = AgentNudgeService.getInstance(project)
                nudgeService.removeQueuedMessage(lastQueued)
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
        AgentNudgeService.getInstance(project).enqueueMessage(rawText)
        queuedTexts.addLast(rawText)
        refreshShortcutHints()
    }

    private fun onForceStopAndSend() {
        val rawText = promptTextArea.text.trim()
        if (rawText.isEmpty()) return
        if (isSending) {
            // Discard any pending nudge before stopping so setSendingState doesn't auto-send it
            val nudgeId = activeBubbleId
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
                        val doc = editor.document
                        val end = offset + trigger.length
                        // Guard against stale offset: the document may have changed between
                        // documentChanged() and this invokeLater callback (e.g. pasting a large block).
                        if (end <= doc.textLength && doc.getText(
                                com.intellij.openapi.util.TextRange(
                                    offset,
                                    end
                                )
                            ) == trigger
                        ) {
                            doc.deleteString(offset, end)
                        }
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

    private fun setupPromptDragDrop(editor: EditorEx) {
        editor.contentComponent.dropTarget = java.awt.dnd.DropTarget(
            editor.contentComponent, java.awt.dnd.DnDConstants.ACTION_COPY,
            object : java.awt.dnd.DropTargetAdapter() {
                override fun dragEnter(dtde: java.awt.dnd.DropTargetDragEvent) {
                    // Always advertise COPY so the source editor does not remove the dragged text.
                    dtde.acceptDrag(java.awt.dnd.DnDConstants.ACTION_COPY)
                }

                override fun dragOver(dtde: java.awt.dnd.DropTargetDragEvent) {
                    dtde.acceptDrag(java.awt.dnd.DnDConstants.ACTION_COPY)
                }

                override fun drop(dtde: java.awt.dnd.DropTargetDropEvent) {
                    handleDrop(dtde, editor)
                }
            })
    }

    private fun handleDrop(dtde: java.awt.dnd.DropTargetDropEvent, editor: EditorEx) {
        try {
            dtde.acceptDrop(java.awt.dnd.DnDConstants.ACTION_COPY)
            val transferable = dtde.transferable

            // File drops: insert a whole-file chip per dropped file.
            if (transferable.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) {
                @Suppress("UNCHECKED_CAST") // DataFlavor API returns Object
                val files = transferable.getTransferData(
                    java.awt.datatransfer.DataFlavor.javaFileListFlavor
                ) as List<java.io.File>
                for (file in files) {
                    val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                        .findFileByIoFile(file) ?: continue
                    val doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                        .getDocument(vf) ?: continue
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
                dtde.dropComplete(true)
                return
            }

            // Text drops: treat like smart paste — create a file-reference chip if the
            // dragged text matches a selection in an open project editor, otherwise create
            // a scratch file.
            if (transferable.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor)) {
                val text = transferable.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
                if (!text.isNullOrBlank()) {
                    handleTextDrop(text, editor)
                    dtde.dropComplete(true)
                    return
                }
            }

            dtde.dropComplete(false)
        } catch (_: Exception) {
            dtde.dropComplete(false)
        }
    }

    private fun handleTextDrop(text: String, editor: EditorEx) {
        val chatInputSettings = com.github.catatafishen.agentbridge.settings.ChatInputSettings.getInstance()
        val minLines = chatInputSettings.smartPasteMinLines
        val minChars = chatInputSettings.smartPasteMinChars

        if (text.lines().size <= minLines && text.length <= minChars) {
            // Below threshold: insert as plain text.
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                val offset = editor.caretModel.offset
                editor.document.insertString(offset, text)
                editor.caretModel.moveToOffset(offset + text.length)
            }
            return
        }

        val projectSource = contextManager.findTextSourceInOpenEditors(text)
        ApplicationManager.getApplication().invokeLater {
            if (editor.isDisposed) return@invokeLater
            if (projectSource != null) {
                contextManager.insertInlineChip(editor, projectSource)
            } else {
                pasteToScratchHandler.handlePasteToScratch(text)
            }
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
        broadcastPanel.setDomMessageLimit(histSettings.domMessageLimit)
        conversationReplayer.loadAndSplit(entries, histSettings.recentTurnsOnRestore, hasMoreOnDisk)
        broadcastPanel.appendEntries(
            conversationReplayer.recentEntries(),
            conversationReplayer.totalPromptCount()
        )
        showDeferredRestoreCount()
        restoreTurnStats(entries.filterIsInstance<EntryData.TurnStats>())
        persistedEntryCount = conversationReplayer.totalLoadedCount()
    }

    private fun showDeferredRestoreCount() {
        val deferred = conversationReplayer.remainingPromptCount()
        if (deferred > 0) broadcastPanel.showLoadMore(deferred)
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
        // Always clear pause state when the user sends a message — a blocked MCP thread must be
        // unblocked regardless of whether the pause feature is currently enabled in settings.
        pausedByInputFocus = false
        McpPauseService.getInstance(project).setPaused(false)
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
        if (batch.isNotEmpty()) broadcastPanel.prependEntries(batch)
        val remaining = conversationReplayer.remainingPromptCount()
        if (remaining > 0) {
            broadcastPanel.showLoadMore(remaining)
        } else {
            if (conversationReplayer.hasOlderHistoryOnDisk) {
                LOG.info("Older history exists on disk but was not loaded (session too large for tail-read budget)")
            }
            broadcastPanel.hideLoadMore()
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
        conversationStore.archive()
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
                // Antipattern (DESIGN-PRINCIPLES.md): Thread.sleep blocks a thread. Kept here because
                // this retry backoff runs on a pooled thread (executeOnPooledThread), not EDT.
                // An Alarm or coroutine delay would add complexity without benefit for a simple retry loop.
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
