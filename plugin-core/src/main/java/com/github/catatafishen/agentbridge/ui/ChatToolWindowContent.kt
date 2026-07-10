package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.bridge.EntryData
import com.github.catatafishen.agentbridge.bridge.NudgeSource
import com.github.catatafishen.agentbridge.client.acp.KiroClient
import com.github.catatafishen.agentbridge.client.claude.ClaudeClient
import com.github.catatafishen.agentbridge.client.codex.CodexClient
import com.github.catatafishen.agentbridge.model.Model
import com.github.catatafishen.agentbridge.model.SessionUpdate
import com.github.catatafishen.agentbridge.psi.review.AgentEditSession
import com.github.catatafishen.agentbridge.services.*
import com.github.catatafishen.agentbridge.session.db.ConversationService
import com.github.catatafishen.agentbridge.settings.ChatInputSettings
import com.intellij.icons.AllIcons
import com.intellij.ide.ActivityTracker
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
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
        const val CARD_CONNECT = "connect"
        const val CARD_CHAT = "chat"
        private const val PREF_SIDE_PANEL_OPEN = "agentbridge.sidePanelOpen"
        private const val PREF_INPUT_PANEL_HEIGHT = "agentbridge.inputPanelHeight"

        private val instances = java.util.concurrent.ConcurrentHashMap<Project, ChatToolWindowContent>()

        fun getInstance(project: Project): ChatToolWindowContent? = instances[project]
    }

    /**
     * Inserts an inline context chip at the current caret in the prompt input — the same
     * mechanism used by the "Attach file" / "Attach selection" actions. Used by, e.g.,
     * [com.github.catatafishen.agentbridge.ui.side.HistoryContextWindow]'s "Reference in
     * chat" button to attach a historical turn to the next prompt.
     *
     * Brings the chat tool window to the front and focuses the prompt editor so the user
     * sees the chip land and can continue typing.
     */
    fun insertContextChip(data: ContextItemData) {
        ApplicationManager.getApplication().invokeLater {
            if (!::contextManager.isInitialized) return@invokeLater
            val editor = (promptTextArea.editor as? com.intellij.openapi.editor.ex.EditorEx) ?: return@invokeLater
            com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                .getToolWindow("AgentBridge")?.activate(null, true)
            contextManager.insertInlineChip(editor, data)
            promptTextArea.requestFocusInWindow()
        }
    }

    private val cardLayout = CardLayout()
    private val mainPanel = JBPanel<JBPanel<*>>(cardLayout)

    // Splitter wrapping the card layout: side panel on LEFT, chat on RIGHT.
    // Collapsed by default (proportion 0.0f). The user can drag, double-click, or use
    // the title-bar toggle to expand. The side panel is built lazily the first time the
    // user opens it.
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
        it.addPropertyChangeListener("proportion") { syncTabsIfNeeded() }
        it.secondComponent = mainPanel
        it.setHonorComponentsMinimumSize(false)
        // When the tool window is resized (by dragging its border), keep the chat pane
        // at its current width and let the sidebar absorb the size change.
        it.dividerPositionStrategy = com.intellij.openapi.ui.Splitter.DividerPositionStrategy.KEEP_SECOND_SIZE
    }

    /** Proportion used when expanding the review panel after it was collapsed. */
    private val defaultReviewProportion = 0.3f

    /** True iff the side panel tab strip is currently open (proportion > 0 and tab strip visible). */
    private var sidePanelOpen = false
    private val agentManager = ActiveAgentManager.getInstance(project)
    private lateinit var connectPanel: AcpConnectPanel
    private var chatPanel: JComponent? = null
    private var chatSessionInitialized = false

    // Model management (delegated to ModelSelectorService)
    private lateinit var modelSelector: ModelSelectorService

    // Delegating properties — existing code accesses these; they route to modelSelector.
    private val loadedModels get() = modelSelector.loadedModels
    private var selectedModelIndex
        get() = modelSelector.selectedModelIndex
        set(value) {
            modelSelector.selectedModelIndex = value
        }
    private val modelsStatusText get() = modelSelector.modelsStatusText
    private lateinit var controlsToolbar: ActionToolbar
    private lateinit var innerInputToolbar: ActionToolbar
    private var restartSessionGroup: RestartSessionGroup? = null
    private lateinit var promptTextArea: EditorTextField
    private val shortcutHintGroup = DefaultActionGroup()
    private lateinit var shortcutHintToolbar: ActionToolbar
    // Keyed by (stroke, label) so the same action instance is reused across refreshShortcutHints()
    // calls. ActionToolbarImpl warns (and creates broken UI) when new instances appear every update.
    private val shortcutHintActionCache = HashMap<Pair<KeyStroke, String>, ShortcutHintAction>()
    private val queuedTexts = ArrayDeque<String>()

    /** Tracks whether the current pause was triggered by typing in the input box. */
    @Volatile
    private var pausedByTyping = false

    /**
     * Set when the user explicitly resumes after an auto-pause triggered by typing.
     * While true, document changes will not re-trigger auto-pause — the agent should keep running.
     * Reset to false when the input is cleared, so the next draft starts fresh.
     */
    @Volatile
    private var userResumedWhileTyping = false

    @Volatile
    private var isSending = false

    /** True when the agent sent turn-complete but ACP messages are still arriving (background work). */
    @Volatile
    private var isBackgroundAgentRunning = false

    @Volatile
    private var activeBubbleId: String? = null

    /** Balloon shown when the agent is paused due to typing; null when not visible. */
    private var pauseTypingBalloon: com.intellij.openapi.ui.popup.Balloon? = null

    private val pauseTypingBubbleListener = McpPauseService.PauseListener { state ->
        ApplicationManager.getApplication().invokeLater({
            when (state) {
                McpPauseService.PauseState.PAUSED -> if (pausedByTyping) showPauseTypingBubble()
                McpPauseService.PauseState.RUNNING,
                McpPauseService.PauseState.PENDING -> dismissPauseTypingBubble()
            }
        }, com.intellij.openapi.util.Condition<Any?> { project.isDisposed })
    }

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
    private lateinit var broadcastPanel: BroadcastChatPanel
    private lateinit var responsePanelContainer: JBPanel<JBPanel<*>>
    private var copilotBanner: AuthSetupBanner? = null
    private var statusBanner: StatusBanner? = null
    private var inlineAuthProcess: Process? = null

    private val persistenceManager = ConversationPersistenceManager(project, ConversationService.getInstance(project))

    private lateinit var contextManager: PromptContextManager
    private lateinit var promptEditorSetup: PromptEditorSetup

    init {
        instances[project] = this
        registerReviewPanelHandlers()
        setupUI()
        subscribeToFocusRestoreEvents()
        subscribeToToolWindowFocus()
        // Initialise the session store's agent name from the currently active profile.
        persistenceManager.setCurrentAgent(agentManager.activeProfile.displayName)
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
            ws.setOnConnect { profileId ->
                ApplicationManager.getApplication().invokeLater { connectToAgent(profileId, null) }
            }
            ws.setOnDisconnect {
                ApplicationManager.getApplication().invokeLater { disconnectFromAgent() }
            }
            ws.setProfilesJson(buildProfilesJson())
        }
    }

    private fun setupUI() {
        modelSelector = ModelSelectorService(project, agentManager, object : ModelSelectorService.Callbacks {
            override fun onModelSelected(modelId: String) {
                consolePanel.setCurrentModel(modelId)
                ApplicationManager.getApplication().executeOnPooledThread {
                    try {
                        // Prefer the orchestrator's session (already established via a prompt).
                        // Fall back to the client's eagerly-created session so that model-specific
                        // config options (e.g. "extra high" reasoning effort) are refreshed
                        // immediately after selection, without waiting for the first prompt.
                        val sessionId = promptOrchestrator.currentSessionId
                            ?: agentManager.client.activeSessionId
                        if (sessionId != null) agentManager.client.setModel(sessionId, modelId)
                    } catch (e: Exception) {
                        LOG.warn("Failed to set model $modelId via web", e)
                    }
                }
            }

            override fun onModelsLoadFailed(error: Exception) {
                // Derive a single non-null display message: message → cause message → fallback.
                val msg = error.message
                    ?: error.cause?.message
                    ?: MSG_UNKNOWN_ERROR
                // Log at ERROR level so the full stack trace is always visible in IDE logs.
                LOG.error("Failed to connect to ${agentManager.activeProfile.displayName}: $msg", error)
                agentManager.isConnected = false
                restartSessionGroup?.updateIconForDisconnect()
                // Always surface the exact error message and return to the connect screen so
                // the user can see what went wrong and retry. Never silently stay on the chat
                // panel with a broken state.
                connectPanel.showError(msg)
                cardLayout.show(mainPanel, CARD_CONNECT)
            }
        })
        setupTitleBarActions()
        wireUpWebServerCallbacks()

        connectPanel = AcpConnectPanel(project) { profileId, customCommand, freshSession ->
            connectToAgent(profileId, customCommand, freshSession)
        }
        mainPanel.add(connectPanel, CARD_CONNECT)

        // Always start on connect panel; auto-connect will proceed automatically
        cardLayout.show(mainPanel, CARD_CONNECT)
        if (agentManager.isAutoConnect) {
            // Show "Connecting…" state and trigger auto-connect flow
            connectPanel.showConnecting()
            modelSelector.loadModelsAsync(
                onSuccess = { models ->
                    buildAndShowChatPanel()
                    modelSelector.restoreModelSelection(models)
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
            StatisticsAction(),
            SettingsAction()
        )
        toolWindow.setTitleActions(actions)
        (toolWindow as? com.intellij.openapi.wm.ex.ToolWindowEx)?.setTabActions(SidePanelToggleAction())
    }

    private fun buildAndShowChatPanel(freshSession: Boolean = false) {
        val addSeparatorNow = {
            val ts = java.time.Instant.now().toString()
            consolePanel.setCurrentAgent(
                agentManager.activeProfile.displayName,
                agentManager.activeProfile.id,
                agentManager.activeProfile.clientCssClass
            )
            consolePanel.addSessionSeparator(ts, agentManager.activeProfile.displayName)
            persistenceManager.appendNewEntries()
        }
        ensureSidePanelAvailable()
        if (!chatSessionInitialized) {
            persistenceManager.archiveConversation()
            // Set agent color immediately so it is queued in pendingJs before the browser loads.
            // Without this there is a race: the browser becomes ready (pendingJs flushed empty) before
            // addSeparatorNow runs, so a message sent in that window shows the default color.
            consolePanel.setCurrentAgent(
                agentManager.activeProfile.displayName,
                agentManager.activeProfile.id,
                agentManager.activeProfile.clientCssClass
            )
            chatSessionInitialized = true
            persistenceManager.restoreConversation(onComplete = addSeparatorNow)
        } else {
            // On reconnect: if the user explicitly chose "None" (fresh session), clear any
            // messages left from the previous connection so the chat window matches the agent context.
            if (freshSession) {
                consolePanel.clear()
            }
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
     *
     * @param freshSession true when the user selected "None" (no session to resume) — causes
     *                     the chat panel to be cleared if it already contains messages from a
     *                     previous connection.
     */
    private fun connectToAgent(profileId: String, customCommand: String?, freshSession: Boolean = false) {
        if (customCommand != null) {
            agentManager.setCustomAcpCommand(customCommand)
        }
        if (agentManager.activeProfileId != profileId) {
            agentManager.switchAgent(profileId)
        }
        // Always sync the session store agent name on connect — switchAgent only fires
        // the listener when the profile changes, so reconnecting to the same profile
        // after a disconnect would leave currentAgent stale.
        persistenceManager.setCurrentAgent(agentManager.activeProfile.displayName)
        if (::promptOrchestrator.isInitialized) resetSessionState()
        // Stay on connect panel while spinner shows "Connecting…"
        // loadModelsAsync triggers agent.start() via getClient() — wait for it to complete
        modelSelector.loadModelsAsync(
            onSuccess = { models ->
                buildAndShowChatPanel(freshSession)
                modelSelector.restoreModelSelection(models)
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
        modelSelector.invalidateLoads()
        try {
            agentManager.stop()
        } catch (e: Exception) {
            LOG.warn("Error stopping agent", e)
        }
        agentManager.isConnected = false
        modelSelector.reset()
        connectPanel.resetConnectButton()
        connectPanel.refreshMcpStatus()
        cardLayout.show(mainPanel, CARD_CONNECT)
        // Reset toolbar icon to default when disconnecting
        restartSessionGroup?.updateIconForDisconnect()
        notifyWebServerDisconnected()
    }

    // ── Web server state helpers ──────────────────────────────────────────────

    private fun buildProfilesJson(): String {
        val profiles = agentManager.availableProfiles.toList()
        if (profiles.isEmpty()) return "[]"
        return "[" + profiles.joinToString(",") { p ->
            val g = com.google.gson.Gson()
            "{\"id\":${g.toJson(p.id)},\"name\":${g.toJson(p.displayName)}}"
        } + "]"
    }

    private fun notifyWebServerConnected() {
        val ws = ChatWebServer.getInstance(project) ?: return
        val modelsJson = modelSelector.buildModelsJson()
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
            com.github.catatafishen.agentbridge.ui.side.SidePanel(project, broadcastPanel, sessionStatsPanel).apply {
                border = JBUI.Borders.empty(4)
            }
        com.intellij.openapi.util.Disposer.register(toolWindow.disposable, side)
        sidePanel = side
        side.setOnPlanTitleChanged { newTitle ->
            sidePanel?.updatePlanTabText(newTitle)
            ActivityTracker.getInstance().inc()
        }
        rootSplitter.firstComponent = side
        restoreSidePanelOpenState()

        // When the agent calls query_conversation_history with follow-agent enabled, open the side panel.
        PromptDbService.getInstance(project).registerShowPanelCallback {
            if (rootSplitter.proportion < 0.01f) {
                rootSplitter.proportion = defaultReviewProportion
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
        }
    }

    private fun createPromptTopPanel(): JBPanel<JBPanel<*>> {
        val topPanel = JBPanel<JBPanel<*>>(BorderLayout())
        val northStack = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        fun loadModels() {
            modelSelector.loadModelsAsync(onSuccess = {})
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
            persistenceManager.setCurrentAgent(agentManager.activeProfile.displayName)
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
        val nDragZone = JBUI.scale(8)
        val wDragZone = JBUI.scale(8)
        val nwCornerSize = JBUI.scale(20)
        val nwExtendedY = JBUI.scale(12)

        val state = ResizeDragState(bottomSection, splitPanel, props)

        installNResizeAdapter(inputSection, state, nDragZone, nwCornerSize)
        installWSideResizeAdapter(sideButtonsPanel, bottomSection, state, wDragZone, nwExtendedY)
        installWBottomResizeAdapter(bottomSection, state, wDragZone, nDragZone)
    }

    private inner class ResizeDragState(
        val bottomSection: JComponent,
        val splitPanel: JComponent,
        val props: com.intellij.ide.util.PropertiesComponent
    ) {
        var heightDragStart: Pair<Int, Int>? = null
        var widthDragStart: Pair<Int, Int>? = null

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

        fun saveHeightAndReset() {
            if (heightDragStart != null) props.setValue(PREF_INPUT_PANEL_HEIGHT, bottomSection.height, 0)
            heightDragStart = null
            widthDragStart = null
        }
    }

    private fun installNResizeAdapter(
        inputSection: JComponent,
        state: ResizeDragState,
        nDragZone: Int,
        nwCornerSize: Int
    ) {
        val handler = object : java.awt.event.MouseAdapter() {
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
                state.heightDragStart = Pair(e.locationOnScreen.y, state.bottomSection.height)
                if (e.x <= nwCornerSize) {
                    state.startWidthDrag(e.locationOnScreen.x)
                }
            }

            override fun mouseDragged(e: java.awt.event.MouseEvent) {
                state.applyHeightDrag(e.locationOnScreen.y)
                state.applyWidthDrag(e.locationOnScreen.x)
            }

            override fun mouseReleased(e: java.awt.event.MouseEvent) {
                state.saveHeightAndReset()
            }
        }
        inputSection.addMouseMotionListener(handler)
        inputSection.addMouseListener(handler)
    }

    private fun installWSideResizeAdapter(
        sideButtonsPanel: JComponent,
        bottomSection: JComponent,
        state: ResizeDragState,
        wDragZone: Int,
        nwExtendedY: Int
    ) {
        val handler = object : java.awt.event.MouseAdapter() {
            override fun mouseMoved(e: java.awt.event.MouseEvent) {
                sideButtonsPanel.cursor = when {
                    e.x > wDragZone -> Cursor.getDefaultCursor()
                    e.y <= nwExtendedY -> Cursor.getPredefinedCursor(Cursor.NW_RESIZE_CURSOR)
                    else -> Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR)
                }
            }

            override fun mousePressed(e: java.awt.event.MouseEvent) {
                if (e.x > wDragZone) return
                state.startWidthDrag(e.locationOnScreen.x)
                if (e.y <= nwExtendedY) {
                    state.heightDragStart = Pair(e.locationOnScreen.y, bottomSection.height)
                }
            }

            override fun mouseDragged(e: java.awt.event.MouseEvent) {
                state.applyWidthDrag(e.locationOnScreen.x)
                state.applyHeightDrag(e.locationOnScreen.y)
            }

            override fun mouseReleased(e: java.awt.event.MouseEvent) {
                state.saveHeightAndReset()
            }

            override fun mouseExited(e: java.awt.event.MouseEvent) {
                if (state.widthDragStart == null) sideButtonsPanel.cursor = Cursor.getDefaultCursor()
            }
        }
        sideButtonsPanel.addMouseMotionListener(handler)
        sideButtonsPanel.addMouseListener(handler)
    }

    private fun installWBottomResizeAdapter(
        bottomSection: JComponent,
        state: ResizeDragState,
        wDragZone: Int,
        nDragZone: Int
    ) {
        val handler = object : java.awt.event.MouseAdapter() {
            override fun mouseMoved(e: java.awt.event.MouseEvent) {
                if (e.x > wDragZone) {
                    if (state.widthDragStart == null) bottomSection.cursor = Cursor.getDefaultCursor()
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
                state.startWidthDrag(e.locationOnScreen.x)
                if (e.y <= nDragZone) {
                    state.heightDragStart = Pair(e.locationOnScreen.y, bottomSection.height)
                }
            }

            override fun mouseDragged(e: java.awt.event.MouseEvent) {
                state.applyWidthDrag(e.locationOnScreen.x)
                state.applyHeightDrag(e.locationOnScreen.y)
            }

            override fun mouseReleased(e: java.awt.event.MouseEvent) {
                state.saveHeightAndReset()
            }

            override fun mouseExited(e: java.awt.event.MouseEvent) {
                if (state.widthDragStart == null) bottomSection.cursor = Cursor.getDefaultCursor()
            }
        }
        bottomSection.addMouseMotionListener(handler)
        bottomSection.addMouseListener(handler)
    }

    /**
     * Called whenever [rootSplitter]'s proportion changes. Detects open/closed transitions
     * and updates the side panel tab strip visibility accordingly.
     */
    private fun syncTabsIfNeeded() {
        val isOpen = rootSplitter.proportion >= 0.01f
        val wasOpen = sidePanelOpen
        if (isOpen == wasOpen) return
        updateSideTabContents(isOpen)
        com.intellij.ide.util.PropertiesComponent.getInstance(project).setValue(PREF_SIDE_PANEL_OPEN, isOpen)
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
        appendNewEntries = { persistenceManager.appendNewEntries() },
        notifyIfUnfocused = ::notifyIfUnfocused,
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
        onTurnMineEntries = { sessionId, agentName -> persistenceManager.mineEntriesAfterTurn(sessionId, agentName) },
        onQueuedMessageConsumed = { text ->
            // Remove the LAST matching entry so that when the same text was queued multiple
            // times, "recall most recent queued message" ordering remains intact (Up-arrow
            // restores the oldest copies first, newest copies last).
            val lastMatchingIndex = queuedTexts.lastIndexOf(text)
            if (lastMatchingIndex >= 0) queuedTexts.removeAt(lastMatchingIndex)
            ApplicationManager.getApplication().invokeLater { refreshShortcutHints() }
        },
        onPostTurnBackgroundDetected = { setBackgroundAgentRunning(true) },
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
        promptEditorSetup = PromptEditorSetup(
            project, promptTextArea, contextManager,
            pasteToScratchHandler, pasteAttachmentHandler, agentManager,
            object : PromptEditorSetup.Callbacks {
                override fun onSendOrStop() = onSendStopClicked()
                override fun onNudge() = onNudgeClicked()
                override fun onQueue() = onQueueMessageClicked()
                override fun onForceStopAndSend() = this@ChatToolWindowContent.onForceStopAndSend()
                override fun onNewConversation() {
                    promptOrchestrator.currentSessionId = null
                    consolePanel.addSessionSeparator(
                        java.time.Instant.now().toString(),
                        agentManager.activeProfile.displayName
                    )
                    updateSessionInfo()
                }

                override fun clearAndRemoveNudge(id: String) = this@ChatToolWindowContent.clearAndRemoveNudge(id)
                override fun refreshShortcutHints() = this@ChatToolWindowContent.refreshShortcutHints()
                override val isSending: Boolean get() = this@ChatToolWindowContent.isSending
                override val activeBubbleId: String? get() = this@ChatToolWindowContent.activeBubbleId
                override val queuedTexts: ArrayDeque<String> get() = this@ChatToolWindowContent.queuedTexts
                override val consolePanel: ChatPanelApi get() = this@ChatToolWindowContent.consolePanel
                override val authPendingError: Any? get() = authService.pendingAuthError
            }
        )
        promptOrchestrator = PromptOrchestrator(
            project, agentManager, billing, contextManager, authService,
            { consolePanel }, { copilotBanner }, { statusBanner },
            createOrchestratorCallbacks()
        )

        // Shortcut hint bar — initialized here so input wiring below can reference it.
        shortcutHintToolbar = ActionManager.getInstance()
            .createActionToolbar("AgentShortcutHints", shortcutHintGroup, true)
        // isReservePlaceAutoPopupIcon = true restores the native >> overflow chevron when hints
        // don't fit. Do NOT set NOWRAP_STRATEGY — that disables the chevron entirely.
        shortcutHintToolbar.isReservePlaceAutoPopupIcon = true
        shortcutHintToolbar.targetComponent = shortcutHintToolbar.component
        shortcutHintToolbar.component.isOpaque = false
        shortcutHintToolbar.component.border = JBUI.Borders.empty()

        promptTextArea.addSettingsProvider { editor ->
            promptEditorSetup.setupDragDrop(editor)
            promptEditorSetup.setupKeyBindings(editor)
            promptEditorSetup.setupContextMenu(editor)
            editor.setPlaceholder(promptPlaceholder())
            editor.setShowPlaceholderWhenFocused(true)
            editor.settings.isUseSoftWraps =
                ChatInputSettings.getInstance().isSoftWrapsEnabled
            editor.setBorder(null)
            editor.scrollPane.verticalScrollBar.preferredSize =
                Dimension(JBUI.scale(10), editor.scrollPane.verticalScrollBar.preferredSize.height)
        }

        promptTextArea.addDocumentListener(object : com.intellij.openapi.editor.event.DocumentListener {
            override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                handlePromptDocumentChanged(event.document.textLength == 0)
            }
        })

        row.add(promptTextArea, BorderLayout.CENTER)
        row.add(createInputFooterPanel(), BorderLayout.SOUTH)

        refreshShortcutHints()

        return row
    }

    private fun handlePromptDocumentChanged(isEmpty: Boolean) {
        com.github.catatafishen.agentbridge.psi.PsiBridgeService.notifyChatInputChanged(project, isEmpty)
        if (isEmpty) {
            handleInputCleared()
        } else {
            handleInputNotEmpty()
        }
        ApplicationManager.getApplication().invokeLater {
            promptTextArea.revalidate()
            promptEditorSetup.checkSlashCommandAutocomplete()
            refreshToolbarsAfterKeystroke()
        }
    }

    private fun handleInputCleared() {
        // Input cleared — if the pause was triggered by typing, auto-resume now.
        if (pausedByTyping) {
            pausedByTyping = false
            userResumedWhileTyping = false
            McpPauseService.getInstance(project).setPaused(false)
        } else {
            userResumedWhileTyping = false
        }
    }

    private fun handleInputNotEmpty() {
        // First keystroke with text in the input — auto-pause if not already paused.
        // Skip when no turn is in flight: pausing the agent is meaningless if it isn't
        // running, and the resulting "Agent is paused while you type" bubble would be
        // misleading (there's nothing to pause).
        if (!isSending) return
        if (!pausedByTyping && !userResumedWhileTyping
            && ChatInputSettings.getInstance().isPauseOnInputFocus
        ) {
            val pauseService = McpPauseService.getInstance(project)
            if (!pauseService.isPaused) {
                pausedByTyping = true
                pauseService.setPaused(true)
            }
        }
    }

    private fun refreshToolbarsAfterKeystroke() {
        if (::innerInputToolbar.isInitialized) {
            innerInputToolbar.updateActionsAsync()
        }
        if (::controlsToolbar.isInitialized) {
            controlsToolbar.updateActionsAsync()
        }
    }

    private fun createInputFooterPanel(): JComponent {
        val footerGroup = DefaultActionGroup()
        footerGroup.add(ModelSelectorAction())
        footerGroup.add(SendAction())

        innerInputToolbar = ActionManager.getInstance().createActionToolbar("AgentInputFooter", footerGroup, true)
        innerInputToolbar.layoutStrategy = ToolbarLayoutStrategy.NOWRAP_STRATEGY
        innerInputToolbar.isReservePlaceAutoPopupIcon = true
        innerInputToolbar.component.isOpaque = false
        innerInputToolbar.component.border = JBUI.Borders.empty()

        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            val hintWrapper = JBPanel<JBPanel<*>>(GridBagLayout()).apply {
                isOpaque = false
                add(shortcutHintToolbar.component, GridBagConstraints().apply {
                    fill = GridBagConstraints.HORIZONTAL
                    weightx = 1.0
                })
            }
            add(hintWrapper, BorderLayout.CENTER)
            add(innerInputToolbar.component, BorderLayout.EAST)
        }
    }

    private fun onSendStopClicked() {
        val rawText = promptTextArea.text.trim()
        if (isSending) {
            stopAgent()
            return
        }
        if (rawText.isEmpty()) {
            showEmptyPromptWarning()
            return
        }
        // Intercept built-in slash commands locally (e.g. /session-restart, /session-clear)
        if (rawText.startsWith("/") && executeBuiltInSlashCommand(rawText)) {
            promptTextArea.text = ""
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
        persistenceManager.appendNewEntries()
        promptTextArea.text = ""

        val selectedModelId = modelSelector.resolveSelectedModelId()
        // Always clear pause state when the user sends a message — a blocked MCP thread must be
        // unblocked regardless of whether the pause feature is currently enabled in settings.
        pausedByTyping = false
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

        // If a prompt_user request is pending, route the typed text to it instead of nudging.
        if (consolePanel.resolvePendingAskUser(text)) {
            promptTextArea.text = ""
            return
        }

        promptTextArea.text = ""
        submitNudge(text)
    }

    /** Submits a human nudge to the pending queue, which triggers the nudge listener to show the bubble. */
    private fun submitNudge(text: String) {
        McpPauseService.getInstance(project).setPaused(false)
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
        if (!::shortcutHintToolbar.isInitialized) return
        refreshShortcutHints()
    }

    private class ShortcutHintAction(
        private val stroke: KeyStroke,
        private val label: String,
    ) : AnAction(), CustomComponentAction {
        init {
            templatePresentation.text = KeyBadge.formatKeystroke(stroke) + " " + label
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = false
        }

        override fun actionPerformed(e: AnActionEvent) = Unit
        override fun createCustomComponent(presentation: Presentation, place: String): JComponent =
            PromptShortcutHintPanel.createHintCell(stroke, label)
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
        if (!::shortcutHintToolbar.isInitialized) return
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
        shortcutHintGroup.removeAll()
        list.forEach { (stroke, label) ->
            shortcutHintGroup.add(shortcutHintActionCache.getOrPut(stroke to label) { ShortcutHintAction(stroke, label) })
        }
        shortcutHintToolbar.component.isVisible = ChatInputSettings.getInstance().isShowShortcutHints
        shortcutHintToolbar.updateActionsAsync()
    }

    private fun setSendingState(sending: Boolean) {
        isSending = sending
        if (sending) {
            // New turn starting — clear any lingering background-agent state
            isBackgroundAgentRunning = false
        }
        ChatWebServer.getInstance(project)?.setAgentRunning(sending)
        if (!sending) {
            // Clear typing-pause state so a stale bubble doesn't linger after the turn ends,
            // and so the next keystroke starts fresh rather than seeing a stuck flag.
            pausedByTyping = false
            userResumedWhileTyping = false
            McpPauseService.getInstance(project).setPaused(false)
            ApplicationManager.getApplication().invokeLater { dismissPauseTypingBubble() }
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

    private fun setBackgroundAgentRunning(active: Boolean) {
        isBackgroundAgentRunning = active
        if (active) {
            consolePanel.addInfoEntry(
                "⚠ Agent signalled turn-complete but is still sending messages " +
                    "(background sub-agent in progress). Press Stop to cancel, or wait for it to finish."
            )
        }
        ApplicationManager.getApplication().invokeLater {
            controlsToolbar.updateActionsAsync()
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
        controlsToolbar.targetComponent = controlsToolbar.component
        controlsToolbar.component.border = JBUI.Borders.empty(8, 4, 4, 0)
        controlsToolbar.component.isOpaque = false

        val pauseService = McpPauseService.getInstance(project)
        pauseService.addListener(pauseTypingBubbleListener)
        com.intellij.openapi.util.Disposer.register(toolWindow.disposable) {
            pauseService.removeListener(pauseTypingBubbleListener)
        }

        return controlsToolbar.component
    }

    private fun showPauseTypingBubble() {
        if (pauseTypingBalloon != null) return
        val settings = ChatInputSettings.getInstance()
        if (settings.isPauseTypingBubbleDismissed) return
        if (!controlsToolbar.component.isShowing) return

        val msgLabel = JBLabel("<html>Agent is paused while you type a prompt.</html>")

        val disableLink = com.intellij.ui.HyperlinkLabel("Disable feature")
        val dontShowLink = com.intellij.ui.HyperlinkLabel("Don't show again")

        val actionsRow = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(disableLink)
            add(Box.createHorizontalStrut(JBUI.scale(8)))
            add(dontShowLink)
            add(Box.createHorizontalGlue())
        }

        val content = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(2, 4, 4, 8)
            msgLabel.alignmentX = JComponent.LEFT_ALIGNMENT
            actionsRow.alignmentX = JComponent.LEFT_ALIGNMENT
            add(msgLabel)
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(actionsRow)
        }

        val balloon = JBPopupFactory.getInstance()
            .createBalloonBuilder(content)
            .setHideOnClickOutside(false)
            .setHideOnAction(false)
            .setHideOnKeyOutside(false)
            .setBlockClicksThroughBalloon(false)
            .setAnimationCycle(150)
            .createBalloon()

        pauseTypingBalloon = balloon

        disableLink.addHyperlinkListener {
            settings.isPauseOnInputFocus = false
            pauseTypingBalloon = null
            balloon.hide()
            pausedByTyping = false
            McpPauseService.getInstance(project).setPaused(false)
        }

        dontShowLink.addHyperlinkListener {
            settings.isPauseTypingBubbleDismissed = true
            pauseTypingBalloon = null
            balloon.hide()
        }

        balloon.show(
            com.intellij.ui.awt.RelativePoint(
                controlsToolbar.component,
                Point(controlsToolbar.component.width / 2, 0)
            ),
            com.intellij.openapi.ui.popup.Balloon.Position.above
        )
    }

    private fun dismissPauseTypingBubble() {
        pauseTypingBalloon?.let { balloon ->
            pauseTypingBalloon = null
            balloon.hide()
        }
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
            if (isSending || isBackgroundAgentRunning) {
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
            if (isSending || isBackgroundAgentRunning) {
                stopAgent()
            } else {
                val inputEvent = e.inputEvent ?: return
                val component = inputEvent.source as? Component ?: return
                val group = DefaultActionGroup()
                addSessionManagementSection(group)
                val popup = JBPopupFactory.getInstance()
                    .createActionGroupPopup(
                        null, group, e.dataContext,
                        JBPopupFactory.ActionSelectionAid.MNEMONICS, false
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
            if (service.isPaused && pausedByTyping) {
                // User is explicitly resuming an auto-pause triggered by typing.
                // Remember this so document changes don't re-pause while input still has text.
                pausedByTyping = false
                userResumedWhileTyping = promptTextArea.document.textLength > 0
            }
            service.setPaused(!service.isPaused)
        }
    }

    private inner class SendAction : AnAction(), CustomComponentAction {
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
                if (!isSending) {
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
            if (isSending) {
                e.presentation.text = ""
                e.presentation.description = "Nudge, queue, or stop and send"
            } else {
                e.presentation.text = ""
                val isLoggedIn = authService.pendingAuthError == null
                e.presentation.description = if (isLoggedIn) "Send prompt (Enter)" else "Sign in to Copilot first"
            }
        }

        override fun actionPerformed(e: AnActionEvent) {
            if (!isSending) {
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
                    JBPopupFactory.ActionSelectionAid.MNEMONICS,
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
            val popup = JBPopupFactory.getInstance().createActionGroupPopup(
                null, group, e.dataContext,
                JBPopupFactory.ActionSelectionAid.MNEMONICS, false
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
                persistenceManager.setCurrentAgent(agentManager.activeProfile.displayName)
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
            val popup = JBPopupFactory.getInstance().createActionGroupPopup(
                null, group, e.dataContext,
                JBPopupFactory.ActionSelectionAid.MNEMONICS, false
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
                group.add(createSessionOptionValueAction(option, value, current))
            }
        }
        return true
    }

    private fun createSessionOptionValueAction(
        option: com.github.catatafishen.agentbridge.bridge.SessionOption,
        value: String,
        current: String
    ): AnAction = object : AnAction(option.labelFor(value)) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun update(e: AnActionEvent) {
            e.presentation.icon = if (value == current) AllIcons.Actions.Checked else null
        }

        override fun actionPerformed(e: AnActionEvent) {
            agentManager.settings.setSessionOptionValue(option.key, value)
            if (option.key == "agent") {
                agentManager.settings.setSelectedAgent(value)
            }
            applySessionOptionRemotely(option.key, value)
        }
    }

    private fun applySessionOptionRemotely(key: String, value: String) {
        val sessionId = promptOrchestrator.currentSessionId ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                agentManager.client.setSessionOption(sessionId, key, value)
            } catch (ex: Exception) {
                LOG.warn("Failed to set session option $key=$value", ex)
            }
        }
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
                    agentId == ClaudeClient.PROFILE_ID
                        || agentId == CodexClient.PROFILE_ID
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

    private inner class SidePanelToggleAction : AnAction(
        "Side Panel",
        "Show or hide the side panel (Review, Project Files, Prompts)",
        AllIcons.General.ChevronLeft
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val isOpen = sidePanelOpen
            e.presentation.icon = if (isOpen) AllIcons.General.ChevronRight else AllIcons.General.ChevronLeft
            e.presentation.text = if (isOpen) "Hide Side Panel" else "Show Side Panel"
        }

        override fun actionPerformed(e: AnActionEvent) {
            val isOpen = sidePanelOpen
            val props = com.intellij.ide.util.PropertiesComponent.getInstance(project)
            if (!isOpen) {
                ensureSidePanelAvailable()
                val chatWidth = rootSplitter.width
                updateSideTabContents(true)
                rootSplitter.proportion = defaultReviewProportion
                props.setValue(PREF_SIDE_PANEL_OPEN, true)
                if (chatWidth > 0) {
                    val stretchAmount = (chatWidth * defaultReviewProportion / (1.0 - defaultReviewProportion)).toInt()
                    (toolWindow as? com.intellij.openapi.wm.ex.ToolWindowEx)?.stretchWidth(stretchAmount)
                }
            } else {
                val sideWidth = rootSplitter.firstComponent?.width ?: 0
                updateSideTabContents(false)
                rootSplitter.proportion = 0.0f
                props.setValue(PREF_SIDE_PANEL_OPEN, false)
                if (sideWidth > 0) {
                    (toolWindow as? com.intellij.openapi.wm.ex.ToolWindowEx)?.stretchWidth(-sideWidth)
                }
            }
        }
    }

    /**
     * Shows or hides the custom tab strip inside [SidePanel]. No ContentManager contents are
     * added or removed — [rootSplitter] remains parented in the single ContentManager content
     * created at startup by [ChatToolWindowFactory] for the lifetime of the tool window.
     *
     * Must be called on the EDT.
     */
    private fun updateSideTabContents(open: Boolean) {
        sidePanel?.setCustomTabStripVisible(open)
        sidePanelOpen = open
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
            if (::broadcastPanel.isInitialized) broadcastPanel.nativePanel.setAutoScroll(state)
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
                val cost = if (supportsMultiplier) modelSelector.getModelMultiplier(model.id()) else null
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
                return JBPopupFactory.getInstance().createComponentPopupBuilder(
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
                    modelSelector.selectModelById(model.id())
                    LOG.debug("Model selected via picker: ${model.id()} (index=$index)")
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
            e.presentation.text = modelSelector.currentDisplayText
            e.presentation.isEnabled = modelsStatusText == null && loadedModels.isNotEmpty()
            // Hide entirely when models loaded successfully but list is empty
            // (agent uses configOptions for model selection instead)
            e.presentation.isVisible = modelsStatusText != null || loadedModels.isNotEmpty()
        }
    }

    private fun createModelSelectionAction(model: Model, index: Int, cost: String?): AnAction {
        val label = if (cost != null) "${model.name()}  ($cost)" else model.name()
        return object : AnAction(label) {
            override fun actionPerformed(e: AnActionEvent) {
                if (index == selectedModelIndex) return
                modelSelector.selectModelById(model.id())
                LOG.debug("Model selected via action: ${model.id()} (index=$index)")
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
        modelSelector.loadModelsAsync(
            onSuccess = { models ->
                buildAndShowChatPanel()
                modelSelector.restoreModelSelection(models)
                statusBanner?.showInfo("Switched to agent: $slug")
            },
            onFailure = { error ->
                statusBanner?.showError(error.message ?: "Failed to restart with agent $slug")
            }
        )
    }

    private fun createResponsePanel(): JComponent {
        val nativeChatPanel = NativeChatPanel(project)
        val bp = BroadcastChatPanel(project, nativeChatPanel)
        broadcastPanel = bp
        consolePanel = bp
        bp.onLoadMoreRequested = { persistenceManager.onLoadMoreHistory() }
        persistenceManager.setEntryStore(bp.entryStore)
        persistenceManager.setCallbacks(createPersistenceCallbacks())
        nativeChatPanel.onCancelNudge = { id ->
            val text = AgentNudgeService.getInstance(project).getPendingNudgesText()
            if (!text.isNullOrEmpty()) promptTextArea.text = text
            clearAndRemoveNudge(id)
            refreshShortcutHints()
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
                sendQuickReply(text)
            }
        }
        nativeChatPanel.onRestoreQueuedMessage = { _, text ->
            ApplicationManager.getApplication().invokeLater {
                promptTextArea.text = text
                val idx = queuedTexts.lastIndexOf(text)
                if (idx >= 0) queuedTexts.removeAt(idx)
                refreshShortcutHints()
            }
        }
        com.intellij.openapi.util.Disposer.register(project, consolePanel)

        setupNudgeLifecycleListener()

        ChatWebServer.getInstance(project)?.also { ws ->
            setupWebServerCallbacks(ws)
        }

        return consolePanel.component
    }

    private fun createPersistenceCallbacks(): ConversationPersistenceManager.Callbacks =
        object : ConversationPersistenceManager.Callbacks {
            override fun appendEntries(entries: List<EntryData>, totalPromptCount: Int) =
                broadcastPanel.appendEntries(entries, totalPromptCount)

            override fun prependEntries(entries: List<EntryData>) =
                broadcastPanel.prependEntries(entries)

            override fun showLoadMore(remaining: Int) = broadcastPanel.showLoadMore(remaining)

            override fun hideLoadMore() = broadcastPanel.hideLoadMore()

            override fun restoreTurnStats(
                stats: ConversationPersistenceManager.RestoredSessionStats,
                lastTurn: ConversationPersistenceManager.RestoredLastTurnStats
            ) {
                processingTimerPanel.restoreSessionStats(
                    ProcessingTimerPanel.RestoredSessionStats(
                        totalTimeMs = stats.totalTimeMs,
                        totalInputTokens = stats.totalInputTokens,
                        totalOutputTokens = stats.totalOutputTokens,
                        totalCostUsd = stats.totalCostUsd,
                        totalToolCalls = stats.totalToolCalls,
                        totalLinesAdded = stats.totalLinesAdded,
                        totalLinesRemoved = stats.totalLinesRemoved,
                        turnCount = stats.turnCount
                    )
                )
                processingTimerPanel.restoreLastTurnStats(
                    ProcessingTimerPanel.RestoredLastTurnStats(
                        elapsedSec = lastTurn.elapsedSec,
                        inputTokens = lastTurn.inputTokens,
                        outputTokens = lastTurn.outputTokens,
                        costUsd = lastTurn.costUsd,
                        toolCalls = lastTurn.toolCalls,
                        linesAdded = lastTurn.linesAdded,
                        linesRemoved = lastTurn.linesRemoved,
                        multiplier = lastTurn.multiplier
                    )
                )
            }

            override fun restoreBillingCounters(turnCount: Int, totalPremiumMultiplier: Double) {
                billing.restoreSessionCounters(turnCount, totalPremiumMultiplier)
            }

            override fun getAgentDisplayName(): String = agentManager.activeProfile.displayName
        }

    private fun setupNudgeLifecycleListener() {
        val nudgeService = AgentNudgeService.getInstance(project)
        nudgeService.addListener(object : AgentNudgeService.Listener {
            override fun onNudgeAdded(entry: AgentNudgeService.NudgeEntry) {
                if (entry.source() == NudgeSource.HUMAN) {
                    pendingHumanText = AgentNudgeService.mergeNudges(pendingHumanText, entry.text())
                }
                if (!entry.showBubble()) return

                ApplicationManager.getApplication().invokeLater {
                    val existingId = activeBubbleId
                    if (existingId != null) {
                        consolePanel.removeNudgeBubble(existingId)
                    }
                    activeBubbleId = entry.id()
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
                    persistenceManager.appendNewEntries()
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
    }

    private fun setupWebServerCallbacks(ws: ChatWebServer) {
        ws.setOnSendPrompt { prompt ->
            ApplicationManager.getApplication().invokeLater { sendPromptDirectly(prompt) }
        }
        ws.setOnQuickReply { text ->
            ApplicationManager.getApplication().invokeLater { sendQuickReply(text) }
        }
        ws.setOnNudge { text ->
            ApplicationManager.getApplication().invokeLater {
                if (isSending) submitNudge(text)
            }
        }
        ws.setOnStop {
            ApplicationManager.getApplication().invokeLater {
                if (isSending) {
                    stopAgent()
                }
            }
        }
        ws.setOnCancelNudge { id ->
            ApplicationManager.getApplication().invokeLater {
                broadcastPanel.nativePanel.onCancelNudge?.invoke(id)
            }
        }
        ws.setOnPermissionResponse { data ->
            ApplicationManager.getApplication().invokeLater {
                broadcastPanel.handleWebPermissionResponse(data)
            }
        }
        ws.setOnSelectModel { modelId ->
            ApplicationManager.getApplication().invokeLater { modelSelector.selectModelById(modelId) }
        }
        ws.setOnLoadMore {
            ApplicationManager.getApplication().invokeLater { persistenceManager.onLoadMoreHistory() }
        }
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

    /**
     * Centralised handling of a user Stop. Cancels the turn (agent + in-flight tool calls via
     * [PromptOrchestrator.stop]), resets the sending state, restores any queued follow-up
     * messages back into the input box (the queue is otherwise only drained on a successful
     * turn completion, so a message queued during a turn that is then stopped would hang
     * forever — issue #845), and informs the user about any terminal tabs the agent left
     * running. Must be called on the EDT.
     */
    private fun stopAgent() {
        if (isSending) {
            promptOrchestrator.stop()
        }
        if (isBackgroundAgentRunning) {
            promptOrchestrator.stopPostTurnBackground()
            isBackgroundAgentRunning = false
            statusBanner?.dismissCurrent()
        }
        setSendingState(false)
        restoreQueuedMessagesToInput()
        notifyLeftoverTerminals()
    }

    /**
     * Drains all queued follow-up messages from the service queue, the recall stack, and the UI,
     * then restores their text into the input box (prepended ahead of any current draft) so the
     * user does not lose what they typed. Restored messages keep their original FIFO order.
     */
    private fun restoreQueuedMessagesToInput() {
        val drained: List<String> = AgentNudgeService.getInstance(project).clearMessageQueue()
        if (drained.isEmpty() && queuedTexts.isEmpty()) return
        drained.forEach { consolePanel.removeQueuedMessageByText(it) }
        queuedTexts.clear()
        if (drained.isNotEmpty()) {
            val restored = drained.joinToString("\n\n")
            val current = promptTextArea.text
            promptTextArea.text = if (current.isEmpty()) restored else "$restored\n\n$current"
            promptTextArea.requestFocusInWindow()
        }
        refreshShortcutHints()
    }

    /**
     * Shows an info banner if the agent left terminal tabs open. Stop deliberately does not kill
     * fire-and-forget `run_in_terminal` commands (they may be long-running tasks the user wants to
     * keep), so this surfaces a non-intrusive reminder that those terminals are still alive.
     */
    private fun notifyLeftoverTerminals() {
        val openTabs = AgentTabTracker.getInstance(project).countOpenTerminalTabs()
        if (openTabs <= 0) return
        val noun = if (openTabs == 1) "terminal tab" else "terminal tabs"
        statusBanner?.showInfo("$openTabs $noun left running — Stop does not close terminals.")
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

    private fun sendQuickReply(text: String) {
        if (isSending) return
        consolePanel.disableQuickReplies()
        sendPromptDirectly(text)
    }

    /** Send a prompt string directly, bypassing the text area (used for quick-replies). */
    private fun sendPromptDirectly(prompt: String) {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) return

        // Intercept built-in slash commands locally (e.g. /session-restart, /session-clear)
        if (trimmed.startsWith("/") && executeBuiltInSlashCommand(trimmed)) return

        // Intercept Kiro slash commands
        val client = agentManager.getClient()
        if (client is KiroClient && trimmed.startsWith("/")) {
            statusBanner?.dismissCurrent()
            setSendingState(true)
            consolePanel.addPromptEntry(trimmed, null)
            persistenceManager.appendNewEntries()
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
        persistenceManager.appendNewEntries()
        val selectedModelId = modelSelector.resolveSelectedModelId()
        // Always clear pause state when the user sends a message — a blocked MCP thread must be
        // unblocked regardless of whether the pause feature is currently enabled in settings.
        pausedByTyping = false
        McpPauseService.getInstance(project).setPaused(false)
        ApplicationManager.getApplication().executeOnPooledThread {
            promptOrchestrator.execute(trimmed, emptyList(), selectedModelId, trimmed, entryId)
        }
    }

    private fun appendResponse(text: String) {
        consolePanel.appendText(text)
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
        persistenceManager.archiveConversation()
        // Delete .current-session-id so the next save creates a brand-new v2 session.
        // This is separate from archive() because archive() must NOT delete the ID during
        // agent switches — doExport still needs the session ID for subsequent export steps.
        persistenceManager.resetCurrentSessionId()
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

    /**
     * Execute a built-in slash command that should be handled locally rather than sent to the agent.
     * @return true if the command was handled (caller should return early)
     */
    private fun executeBuiltInSlashCommand(command: String): Boolean {
        return when {
            command.equals("/session-restart", ignoreCase = true) -> {
                resetSessionKeepingHistory()
                true
            }
            command.equals("/session-clear", ignoreCase = true) -> {
                resetSession()
                true
            }
            else -> false
        }
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

    /** Tree node for the Plans tab — display name is shown in the tree. */
    private class FileTreeNode(
        fileName: String
    ) : javax.swing.tree.DefaultMutableTreeNode("\uD83D\uDCC4 $fileName")
}
