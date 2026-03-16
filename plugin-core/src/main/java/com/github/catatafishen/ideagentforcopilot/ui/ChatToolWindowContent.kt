package com.github.catatafishen.ideagentforcopilot.ui

import com.github.catatafishen.ideagentforcopilot.bridge.*
import com.github.catatafishen.ideagentforcopilot.services.ActiveAgentManager
import com.github.catatafishen.ideagentforcopilot.settings.BillingSettings
import com.github.catatafishen.ideagentforcopilot.settings.ProjectFilesSettings
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
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*

/**
 * Main content for the IDE Agent for Copilot tool window.
 */
class ChatToolWindowContent(
    private val project: Project,
    private val toolWindow: com.intellij.openapi.wm.ToolWindow
) {

    private companion object {
        private val LOG =
            com.intellij.openapi.diagnostic.Logger.getInstance(ChatToolWindowContent::class.java)
        const val MSG_LOADING = "Loading..."
        const val MSG_UNKNOWN_ERROR = "Unknown error"
        const val AGENT_WORK_DIR = ".agent-work"
        const val CARD_CONNECT = "connect"
        const val CARD_CHAT = "chat"
    }

    private val cardLayout = CardLayout()
    private val mainPanel = JBPanel<JBPanel<*>>(cardLayout)
    private val agentManager = ActiveAgentManager.getInstance(project)
    private lateinit var connectPanel: AcpConnectPanel
    private var chatPanel: JComponent? = null

    // Shared model list (populated from ACP)
    private var loadedModels: List<Model> = emptyList()
    private var modelLoadGeneration = 0

    // Prompt tab fields
    private var selectedModelIndex = -1
    private var modelsStatusText: String? = MSG_LOADING
    private lateinit var controlsToolbar: ActionToolbar
    private lateinit var promptTextArea: EditorTextField
    private var isSending = false
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

    private val conversationStore = ConversationStore()
    private val conversationReplayer = ConversationReplayer()

    // Throttled incremental save during streaming (avoid data loss on crash)
    private val saveIntervalMs = 30_000L

    @Volatile
    private var lastIncrementalSaveMs = 0L

    private lateinit var contextManager: PromptContextManager

    init {
        setupUI()
        subscribeToFocusRestoreEvents()
    }

    /**
     * Subscribes to focus restore events published by PsiBridgeService after tool calls complete.
     * Restores keyboard focus to the chat input after files are opened in follow mode.
     */
    private fun subscribeToFocusRestoreEvents() {
        val connection = project.messageBus.connect()
        connection.subscribe(
            com.github.catatafishen.ideagentforcopilot.psi.PsiBridgeService.FOCUS_RESTORE_TOPIC,
            com.github.catatafishen.ideagentforcopilot.psi.PsiBridgeService.FocusRestoreListener {
                ApplicationManager.getApplication().invokeLater {
                    if (::promptTextArea.isInitialized) {
                        promptTextArea.requestFocusInWindow()
                    }
                }
            }
        )
    }

    private fun setupUI() {
        setupTitleBarActions()

        connectPanel = AcpConnectPanel(project) { profileId, customCommand ->
            connectToAgent(profileId, customCommand)
        }
        mainPanel.add(connectPanel, CARD_CONNECT)

        if (agentManager.isAutoConnect) {
            buildAndShowChatPanel()
            loadModelsAsync { models ->
                loadedModels = models
                restoreModelSelection(models)
                statusBanner?.showInfo("Connected to ${agentManager.activeProfile.displayName}")
            }
        } else {
            cardLayout.show(mainPanel, CARD_CONNECT)
        }
    }

    private fun setupTitleBarActions() {
        val actions = listOf(
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
            consolePanel.addSessionSeparator(ts, agentManager.activeProfile.displayName)
        }
        if (chatPanel == null) {
            val panel = createPromptTab()
            chatPanel = panel
            mainPanel.add(panel, CARD_CHAT)
            archiveConversation()
            restoreConversation(onComplete = addSeparatorNow)
        } else {
            addSeparatorNow()
        }
        cardLayout.show(mainPanel, CARD_CHAT)
        agentManager.isConnected = true
        updatePromptPlaceholder()
    }

    /**
     * Called from AcpConnectPanel when the user clicks Connect.
     * Switches the active agent, builds the chat panel, and loads models.
     */
    private fun connectToAgent(profileId: String, customCommand: String?) {
        if (customCommand != null) {
            agentManager.setCustomAcpCommand(customCommand)
        }
        if (agentManager.activeProfileId != profileId) {
            agentManager.switchAgent(profileId)
        }
        buildAndShowChatPanel()

        loadModelsAsync { models ->
            loadedModels = models
            restoreModelSelection(models)
            statusBanner?.showInfo("Connected to ${agentManager.activeProfile.displayName}")
        }
    }

    private fun promptPlaceholder(): String {
        val name = agentManager.activeProfile.displayName
        return "Ask $name... (Shift+Enter for new line)"
    }

    private fun updatePromptPlaceholder() {
        val editor = promptTextArea.editor as? EditorEx ?: return
        editor.setPlaceholder(promptPlaceholder())
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
    }

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
                        if (entry.priority().isNotEmpty()) " (${entry.priority()})" else ""
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
            banner.showSignInPending()
            inlineAuthProcess?.destroy()
            inlineAuthProcess = authService.startInlineAuth(
                onDeviceCode = { info ->
                    banner.showDeviceCode(info.code, info.url)
                },
                onAuthComplete = {
                    banner.hideDeviceCode()
                    inlineAuthProcess = null
                    authService.pendingAuthError = null
                    banner.triggerCheck()
                },
                onFallback = {
                    banner.hideDeviceCode()
                    inlineAuthProcess = null
                    authService.startCopilotLogin()
                },
            )
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
            loadModelsAsync { models -> loadedModels = models }
        }

        copilotBanner = createCopilotSetupBanner {
            authService.pendingAuthError = null
            promptOrchestrator.currentSessionId = null
            loadModels()
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
        val splitter = com.intellij.ui.OnePixelSplitter(true, "IdeAgent.InputSplitter", 0.78f)
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
        promptTextArea.border = null
        contextManager = PromptContextManager(project, promptTextArea) { text -> appendResponse(text) }

        pasteToScratchHandler = PasteToScratchHandler(project, promptTextArea, contextManager)
        promptOrchestrator = PromptOrchestrator(
            project, agentManager, billing, contextManager, authService,
            { consolePanel }, { copilotBanner }, { statusBanner },
            PromptOrchestratorCallbacks(
                onSendingStateChanged = ::setSendingState,
                saveConversation = ::saveConversation,
                saveConversationThrottled = ::saveConversationThrottled,
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
                onClientUpdate = ::handleClientUpdate,
            )
        )

        setupPromptDragDrop(promptTextArea)
        promptTextArea.addSettingsProvider { editor ->
            setupPromptKeyBindings(editor)
            setupPromptContextMenu(editor)
            editor.setPlaceholder(promptPlaceholder())
            editor.setShowPlaceholderWhenFocused(true)
            editor.settings.isUseSoftWraps = true
            editor.contentComponent.border = JBUI.Borders.empty(4, 6)
            editor.setBorder(null)

            // Auto-scroll the outer JBScrollPane to keep the caret visible while typing.
            // EditorTextField's internal JViewport swallows scrollRectToVisible — convert to
            // promptTextArea coords so the outer JViewport handles it.
            editor.caretModel.addCaretListener(object : com.intellij.openapi.editor.event.CaretListener {
                override fun caretPositionChanged(event: com.intellij.openapi.editor.event.CaretEvent) {
                    ApplicationManager.getApplication().invokeLater {
                        val caretOffset = editor.caretModel.offset
                        val caretPoint = editor.offsetToXY(caretOffset)
                        val lineHeight = editor.lineHeight
                        val converted = SwingUtilities.convertPoint(
                            editor.contentComponent, caretPoint, promptTextArea
                        )
                        promptTextArea.scrollRectToVisible(
                            Rectangle(converted.x, converted.y, 1, lineHeight)
                        )
                    }
                }
            })
        }

        promptTextArea.addDocumentListener(object : com.intellij.openapi.editor.event.DocumentListener {
            override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                ApplicationManager.getApplication().invokeLater { promptTextArea.revalidate() }
            }
        })

        val scrollPane = JBScrollPane(promptTextArea)
        scrollPane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        scrollPane.border = null
        scrollPane.viewportBorder = null
        row.border = JBUI.Borders.empty()
        row.add(scrollPane, BorderLayout.CENTER)

        return row
    }

    private fun onSendStopClicked() {
        if (isSending) {
            promptOrchestrator.stop()
            setSendingState(false)
            return
        }
        val rawText = promptTextArea.text.trim()
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
        consolePanel.addPromptEntry(prompt, ctxFiles, bubbleHtml)
        promptTextArea.text = ""

        val selectedModelId = loadedModels.getOrNull(selectedModelIndex)?.id ?: ""
        ApplicationManager.getApplication().executeOnPooledThread {
            promptOrchestrator.execute(prompt, contextItems, selectedModelId)
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

    private fun setSendingState(sending: Boolean) {
        isSending = sending
        ApplicationManager.getApplication().invokeLater {
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
        leftGroup.add(AgentSelectorAction())
        leftGroup.add(SessionOptionsGroup())
        leftGroup.addSeparator()
        leftGroup.add(RestartSessionGroup())

        controlsToolbar = ActionManager.getInstance().createActionToolbar(
            "AgentControls", leftGroup, true
        )
        controlsToolbar.targetComponent = row
        controlsToolbar.isReservePlaceAutoPopupIcon = false

        val rightGroup = DefaultActionGroup()
        rightGroup.add(ProcessingIndicatorAction())
        if (BillingSettings.getInstance().isShowCopilotUsage) {
            rightGroup.add(billing.createUsageGraphAction())
        }

        val rightToolbar = ActionManager.getInstance().createActionToolbar(
            "AgentRight", rightGroup, true
        )
        rightToolbar.targetComponent = row
        rightToolbar.isReservePlaceAutoPopupIcon = false

        row.add(controlsToolbar.component, BorderLayout.CENTER)
        row.add(rightToolbar.component, BorderLayout.EAST)

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
            group.addSeparator()

            val triggerGroup = DefaultActionGroup("File Search Trigger", true)
            triggerGroup.templatePresentation.icon = AllIcons.General.Settings
            for ((label, value) in listOf(
                "# (VS Code style)" to "#",
                "@ (AI Assistant style)" to "@",
                "Disabled" to ""
            )) {
                triggerGroup.add(object : ToggleAction(label) {
                    override fun getActionUpdateThread() = ActionUpdateThread.BGT
                    override fun isSelected(e: AnActionEvent) = ActiveAgentManager.getAttachTriggerChar() == value
                    override fun setSelected(e: AnActionEvent, state: Boolean) {
                        if (state) ActiveAgentManager.setAttachTriggerChar(value)
                    }
                })
            }
            group.add(triggerGroup)

            val popup = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createActionGroupPopup(
                null, group, e.dataContext,
                com.intellij.openapi.ui.popup.JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false
            )
            popup.showUnderneathOf(component)
        }
    }

    /** Dropdown toolbar button with restart and disconnect options. */
    private inner class RestartSessionGroup : DefaultActionGroup(
        "Restart Session", true
    ) {
        init {
            templatePresentation.icon = AllIcons.Actions.Restart
            templatePresentation.text = "Restart Session"
            templatePresentation.description = "Restart the agent session"

            add(object : AnAction(
                "Restart (Keep History)",
                "Start a new agent session while keeping the conversation visible",
                AllIcons.Actions.Restart
            ) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) = resetSessionKeepingHistory()
            })

            add(object : AnAction(
                "Clear and Restart",
                "Clear the conversation and start a completely fresh session",
                AllIcons.Actions.GC
            ) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) = resetSession()
            })

            addSeparator()

            add(object : AnAction(
                "Disconnect",
                "Stop the ACP process and return to the connection screen",
                AllIcons.Actions.Cancel
            ) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) = disconnectFromAgent()
            })
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    /** Toolbar button that opens the plugin settings. */
    private inner class SettingsAction : AnAction(
        "Settings", "Open AgentBridge settings",
        AllIcons.General.Settings
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            com.github.catatafishen.ideagentforcopilot.settings.PluginSettingsConfigurable.open(project)
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

    /** Open a project-root file in the editor if it exists. */
    private fun openProjectFile(fileName: String) {
        val base = project.basePath ?: return
        val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .findFileByPath("$base/$fileName") ?: return
        com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(vf, true)
    }

    /** Dropdown action for project configuration files: Instructions, task file, Agent Definitions, MCP Instructions. */
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
            val base = project.basePath

            val entries = ProjectFilesSettings.getInstance().entries
            for (entry in entries) {
                if (entry.isGlob) {
                    addGlobEntries(group, base, entry)
                } else {
                    addFileEntry(group, base, entry)
                }
            }

            group.addSeparator("MCP Server")
            val popup = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
                .createActionGroupPopup(
                    null, group, com.intellij.openapi.actionSystem.impl.SimpleDataContext.getProjectContext(project),
                    com.intellij.openapi.ui.popup.JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                    true
                )
            popup.showUnderneathOf(owner)
        }

        private fun addFileEntry(group: DefaultActionGroup, base: String?, entry: ProjectFilesSettings.FileEntry) {
            val file = if (base != null) java.io.File(base, entry.path) else null
            val exists = file?.exists() == true
            group.add(object : AnAction(
                entry.label,
                if (exists) "Open ${entry.path}" else "${entry.path} not found",
                if (exists) AllIcons.FileTypes.Text else AllIcons.Actions.IntentionBulbGrey
            ) {
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = exists
                }

                override fun actionPerformed(e: AnActionEvent) = openProjectFile(entry.path)
            })
        }

        private fun addGlobEntries(group: DefaultActionGroup, base: String?, entry: ProjectFilesSettings.FileEntry) {
            group.addSeparator(entry.label)
            if (base == null) return

            val globPath = entry.path
            val lastSlash = globPath.lastIndexOf('/')
            val dirPart = if (lastSlash >= 0) globPath.substring(0, lastSlash) else ""
            val patternPart = if (lastSlash >= 0) globPath.substring(lastSlash + 1) else globPath
            val regex = Regex("^" + patternPart.replace(".", "\\.").replace("*", ".*") + "$")

            val dir = java.io.File(base, dirPart)
            val matched = dir.listFiles { f -> f.isFile && regex.matches(f.name) }
                ?.sortedBy { it.name } ?: emptyList()

            if (matched.isNotEmpty()) {
                for (file in matched) {
                    val relPath = if (dirPart.isNotEmpty()) "$dirPart/${file.name}" else file.name
                    group.add(object : AnAction(
                        file.nameWithoutExtension,
                        "Open ${file.name}",
                        AllIcons.General.User
                    ) {
                        override fun getActionUpdateThread() = ActionUpdateThread.BGT
                        override fun actionPerformed(e: AnActionEvent) = openProjectFile(relPath)
                    })
                }
            } else {
                group.add(object : AnAction("None Found", null, null) {
                    override fun getActionUpdateThread() = ActionUpdateThread.BGT
                    override fun update(e: AnActionEvent) {
                        e.presentation.isEnabled = false
                    }

                    override fun actionPerformed(e: AnActionEvent) { /* disabled — no-op */
                    }
                })
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
                val cost = model.usage ?: "1x"
                val label = if (supportsMultiplier && cost != "1x") "${model.name}  ($cost)" else model.name
                group.add(object : AnAction(label) {
                    override fun actionPerformed(e: AnActionEvent) {
                        if (index == selectedModelIndex) return

                        selectedModelIndex = index
                        agentManager.settings.setSelectedModel(model.id)
                        LOG.info("Model selected: ${model.id} (index=$index)")
                        ApplicationManager.getApplication().invokeLater {
                            consolePanel.setCurrentModel(model.id)
                            if (supportsMultiplier) {
                                val multiplier = getModelMultiplier(model.id)
                                consolePanel.setPromptStats(model.id, multiplier)
                            }
                        }
                        ApplicationManager.getApplication().executeOnPooledThread {
                            try {
                                val client = agentManager.client
                                val sessionId = promptOrchestrator.currentSessionId
                                if (sessionId != null) {
                                    client.setModel(sessionId, model.id)
                                    LOG.info("Model switched to ${model.id} on session $sessionId")
                                } else {
                                    LOG.info("No active session; model ${model.id} will be used on next session")
                                }
                            } catch (ex: Exception) {
                                LOG.warn("Failed to set model ${model.id} via session/set_model", ex)
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
                ?: loadedModels.getOrNull(selectedModelIndex)?.name
                ?: MSG_LOADING
            e.presentation.text = text
            e.presentation.isEnabled = modelsStatusText == null && loadedModels.isNotEmpty()
        }
    }

    private inner class AgentSelectorAction : ComboBoxAction() {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun createPopupActionGroup(button: JComponent, context: DataContext): DefaultActionGroup {
            val group = DefaultActionGroup()
            group.add(object : AnAction("Default") {
                override fun actionPerformed(e: AnActionEvent) {
                    agentManager.settings.setSelectedAgent("")
                }

                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
            for (agentName in discoverAgentNames()) {
                group.add(object : AnAction(agentName) {
                    override fun actionPerformed(e: AnActionEvent) {
                        agentManager.settings.setSelectedAgent(agentName)
                    }

                    override fun getActionUpdateThread() = ActionUpdateThread.BGT
                })
            }
            return group
        }

        override fun update(e: AnActionEvent) {
            val agentsDir = agentManager.config.agentsDirectory
            if (agentsDir.isNullOrBlank()) {
                e.presentation.isVisible = false
                return
            }
            e.presentation.isVisible = true
            val selected = agentManager.settings.selectedAgent
            e.presentation.text = selected.ifEmpty { "Default" }
        }

        private fun discoverAgentNames(): List<String> {
            val agentsDir = agentManager.config.agentsDirectory ?: return emptyList()
            val basePath = project.basePath ?: return emptyList()
            val dir = java.io.File(basePath, agentsDir)
            if (!dir.isDirectory) return emptyList()
            return dir.listFiles { f -> f.isFile && f.extension == "md" }
                ?.map { it.nameWithoutExtension }
                ?.sorted()
                ?: emptyList()
        }
    }

    /**
     * Inline action group that dynamically renders one [SessionOptionSelectorAction] dropdown
     * per option declared by the active client's [AgentClient.listSessionOptions].
     * Invisible when the client exposes no options or the agent is disconnected.
     *
     * Extends [DefaultActionGroup] with popup=false so children appear inline in the toolbar.
     */
    private inner class SessionOptionsGroup : DefaultActionGroup() {
        init {
            isPopup = false
        }

        override fun getActionUpdateThread() = ActionUpdateThread.BGT

        private var cachedOptions: List<SessionOption> = emptyList()
        private var cachedChildren: Array<AnAction> = EMPTY_ARRAY

        override fun getChildren(e: AnActionEvent?): Array<AnAction> {
            if (!agentManager.isConnected) return EMPTY_ARRAY
            val options = try {
                agentManager.client.listSessionOptions()
            } catch (ex: Exception) {
                LOG.warn("Failed to retrieve session options for toolbar", ex)
                return EMPTY_ARRAY
            }
            if (options.isEmpty()) return EMPTY_ARRAY
            if (options != cachedOptions) {
                cachedOptions = options
                cachedChildren = options.map { SessionOptionSelectorAction(it) }.toTypedArray()
            }
            return cachedChildren
        }
    }

    /** ComboBox dropdown for a single [SessionOption] — mirrors the pattern of [ModelSelectorAction]. */
    private inner class SessionOptionSelectorAction(
        private val option: SessionOption
    ) : ComboBoxAction() {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT

        override fun createPopupActionGroup(button: JComponent, context: DataContext): DefaultActionGroup {
            val group = DefaultActionGroup()
            for (value in option.values) {
                val label = option.labelFor(value)
                group.add(object : AnAction(label) {
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

                    override fun getActionUpdateThread() = ActionUpdateThread.BGT
                })
            }
            return group
        }

        override fun update(e: AnActionEvent) {
            val stored = agentManager.settings.getSessionOptionValue(option.key)
            e.presentation.text = "${option.displayName}: ${option.labelFor(stored)}"
        }
    }

    private fun createResponsePanel(): JComponent {
        chatConsolePanel = ChatConsolePanel(project)
        consolePanel = chatConsolePanel
        chatConsolePanel.onLoadMoreRequested = ::onLoadMoreHistory
        consolePanel.onQuickReply = { text -> ApplicationManager.getApplication().invokeLater { sendQuickReply(text) } }
        com.intellij.openapi.util.Disposer.register(project, consolePanel)
        return consolePanel.component
    }

    private fun appendResponse(text: String) {
        consolePanel.appendText(text)
    }

    private fun setupPromptKeyBindings(editor: EditorEx) {
        val contentComponent = editor.contentComponent
        registerEnterSend(contentComponent)
        registerShiftEnterNewLine(editor, contentComponent)
        registerPasteIntercept(editor, contentComponent)
        registerTriggerCharDetection(editor)
    }

    // Use IntelliJ's action system so the shortcut takes priority over the editor's built-in Enter handler.
    private fun registerEnterSend(contentComponent: JComponent) {
        object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) {
                if (promptTextArea.text.isNotBlank() && !isSending && authService.pendingAuthError == null) {
                    onSendStopClicked()
                }
            }
        }.registerCustomShortcutSet(
            CustomShortcutSet(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0)),
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
            CustomShortcutSet(
                KeyStroke.getKeyStroke(
                    java.awt.event.KeyEvent.VK_ENTER,
                    java.awt.event.InputEvent.SHIFT_DOWN_MASK
                )
            ),
            contentComponent
        )
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

        val clipText = contextManager.getClipboardText()
        if (clipText == null || (clipText.lines().size <= 3 && clipText.length <= 500)) return false

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

    private fun saveConversation() {
        lastIncrementalSaveMs = System.currentTimeMillis()
        conversationStore.saveAsync(project.basePath, ConversationSerializer.serialize(chatConsolePanel.getEntries()))
    }

    /**
     * Saves conversation if at least [saveIntervalMs] elapsed since the last save.
     * Called after each tool-call completion during streaming so that long-running turns
     * are periodically persisted and survive IDE crashes.
     */
    private fun saveConversationThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastIncrementalSaveMs >= saveIntervalMs) {
            saveConversation()
        }
    }

    private fun restoreConversation(onComplete: () -> Unit = {}) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val json = conversationStore.loadJson(project.basePath)
            ApplicationManager.getApplication().invokeLater {
                if (json != null) {
                    conversationReplayer.loadAndSplit(json)
                    chatConsolePanel.appendEntries(conversationReplayer.recentEntries())
                    val deferred = conversationReplayer.deferredCount()
                    if (deferred > 0) chatConsolePanel.showLoadMore(deferred)
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
        statusBanner?.dismissCurrent()
        setSendingState(true)
        consolePanel.addPromptEntry(trimmed, null)
        val selectedModelId = loadedModels.getOrNull(selectedModelIndex)?.id ?: ""
        ApplicationManager.getApplication().executeOnPooledThread {
            promptOrchestrator.execute(trimmed, emptyList(), selectedModelId)
        }
    }

    private fun onLoadMoreHistory() {
        val batch = conversationReplayer.loadNextBatch()
        if (batch.isNotEmpty()) chatConsolePanel.prependEntries(batch)
        val remaining = conversationReplayer.deferredCount()
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
        com.github.catatafishen.ideagentforcopilot.psi.PsiBridgeService.getInstance(project).clearSessionAllowedTools()
    }

    fun resetSession() {
        resetSessionState()
        consolePanel.clear()
        consolePanel.showPlaceholder("New conversation started.")
        updateSessionInfo()
        archiveConversation()
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
                            "1x"
                        }
                        addProperty("multiplier", multiplier)
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
    }

    private fun getModelMultiplier(modelId: String): String {
        return try {
            agentManager.client.getModelMultiplier(modelId)
        } catch (_: Exception) {
            "1x"
        }
    }

    private fun restoreModelSelection(models: List<Model>) {
        val savedModel = agentManager.settings.selectedModel
        LOG.info("Restoring model selection: saved='$savedModel', available=${models.map { it.id }}")
        if (savedModel != null) {
            val idx = models.indexOfFirst { it.id == savedModel }
            if (idx >= 0) {
                selectedModelIndex = idx; LOG.info("Restored model index=$idx"); return
            }
            LOG.info("Saved model '$savedModel' not found in available models")
        }
        if (models.isNotEmpty()) selectedModelIndex = 0
    }

    private fun loadModelsAsync(onSuccess: (List<Model>) -> Unit) {
        val generation = ++modelLoadGeneration
        ApplicationManager.getApplication().invokeLater {
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
                    if (generation == modelLoadGeneration) onModelsLoadFailed(e)
                }
            }
        }
    }

    private fun fetchModelsWithRetry(): List<Model> {
        var lastError: Exception? = null
        for (attempt in 1..3) {
            if (attempt > 1) Thread.sleep(2000L)
            try {
                return agentManager.client.listModels().toList()
            } catch (e: Exception) {
                lastError = e
                if (authService.isAuthenticationError(e.message ?: "") || isCLINotFoundError(e)) break
            }
        }
        throw lastError ?: RuntimeException(MSG_UNKNOWN_ERROR)
    }

    private fun onModelsLoaded(models: List<Model>, onSuccess: (List<Model>) -> Unit) {
        modelsStatusText = null
        restoreModelSelection(models)
        onSuccess(models)
    }

    private fun onModelsLoadFailed(lastError: Exception) {
        val errorMsg = lastError.message ?: MSG_UNKNOWN_ERROR
        modelsStatusText = "Unavailable"
        if (isCLINotFoundError(lastError)) {
            agentManager.isConnected = false
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
            if (cause is AcpException && !cause.isRecoverable) return true
            cause = cause.cause
        }
        return false
    }

    /** Tree node for the Plans tab — display name is shown in the tree. */
    private class FileTreeNode(
        fileName: String
    ) : javax.swing.tree.DefaultMutableTreeNode("\uD83D\uDCC4 $fileName")
}
