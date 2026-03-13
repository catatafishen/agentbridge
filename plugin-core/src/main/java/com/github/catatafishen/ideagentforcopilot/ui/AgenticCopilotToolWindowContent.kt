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
import com.intellij.openapi.ui.Messages
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*

/**
 * Main content for the IDE Agent for Copilot tool window.
 * Uses Kotlin UI DSL for cleaner, more maintainable UI code.
 */
class AgenticCopilotToolWindowContent(
    private val project: Project,
    private val toolWindow: com.intellij.openapi.wm.ToolWindow
) {

    private companion object {
        private val LOG =
            com.intellij.openapi.diagnostic.Logger.getInstance(AgenticCopilotToolWindowContent::class.java)
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

    // Current conversation session — reused for multi-turn
    private var currentSessionId: String? = null

    // Prompt tab fields (promoted from local variables for footer layout)
    private var selectedModelIndex = -1
    private var modelsStatusText: String? = MSG_LOADING
    private lateinit var controlsToolbar: ActionToolbar
    private lateinit var promptTextArea: EditorTextField
    private var currentPromptThread: Thread? = null
    private var isSending = false
    private lateinit var processingTimerPanel: ProcessingTimerPanel

    // Plans tree (populated from ACP plan updates)
    private lateinit var planTreeModel: javax.swing.tree.DefaultTreeModel
    private lateinit var planRoot: javax.swing.tree.DefaultMutableTreeNode
    private lateinit var planDetailsArea: JBTextArea
    private lateinit var sessionInfoLabel: JBLabel

    // Billing/usage management (extracted to BillingManager)
    private val billing = BillingManager()
    private val authService = AuthLoginService(project)
    private lateinit var consolePanel: ChatPanelApi
    private lateinit var responsePanelContainer: JBPanel<JBPanel<*>>
    private var copilotBanner: AuthSetupBanner? = null
    private var statusBanner: StatusBanner? = null
    private var inlineAuthProcess: Process? = null

    // Per-turn tracking
    private var turnToolCallCount = 0
    private var turnModelId = ""

    // Throttled incremental save during streaming (avoid data loss on crash)
    private val saveIntervalMs = 30_000L

    @Volatile
    private var lastIncrementalSaveMs = 0L

    private var conversationSummaryInjected = false
    private lateinit var contextManager: PromptContextManager

    init {
        setupUI()
    }

    private fun setupUI() {
        // Title bar actions (always visible regardless of ACP connection)
        setupTitleBarActions()

        // Connect panel — always created
        connectPanel = AcpConnectPanel(project) { profileId, customCommand ->
            connectToAgent(profileId, customCommand)
        }
        mainPanel.add(connectPanel, CARD_CONNECT)

        // Chat panel — created lazily on first connect
        if (agentManager.isAutoConnect) {
            buildAndShowChatPanel()
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
            // Archive the previous session on first connect so each IDE launch gets its own entry.
            archiveConversation()
            restoreConversation(onComplete = addSeparatorNow)
        } else {
            addSeparatorNow()
        }
        cardLayout.show(mainPanel, CARD_CHAT)
        agentManager.isAcpConnected = true
        updatePromptPlaceholder()

        // If called from auto-connect, kick off model loading
        if (loadedModels.isEmpty() && modelsStatusText == MSG_LOADING) {
            loadModelsAsync { models ->
                loadedModels = models
                restoreModelSelection(models)
                val agentName = agentManager.activeProfile.displayName
                statusBanner?.showInfo("Connected to $agentName")
            }
        }
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

        // Load models — on success show the chat; on failure return to connect panel
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
        agentManager.isAcpConnected = false
        connectPanel.resetConnectButton()
        connectPanel.refreshMcpStatus()
        cardLayout.show(mainPanel, CARD_CONNECT)
    }

    private fun updateSessionInfo() {
        ApplicationManager.getApplication().invokeLater {
            if (!::sessionInfoLabel.isInitialized) return@invokeLater
            val sid = currentSessionId
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
    private val toolCallTitles = mutableMapOf<String, String>() // toolCallId -> display title
    private var activeSubAgentId: String? = null // non-null while a sub-agent is running

    /** Handle ACP session/update notifications — routes to timeline and session tab. */
    private fun handleAcpUpdate(update: com.google.gson.JsonObject) {
        val updateType = update["sessionUpdate"]?.asString ?: return

        when (updateType) {
            "tool_call" -> handleToolCall(update)
            "tool_call_update" -> handleToolCallUpdate(update)
            "plan" -> handlePlanUpdate(update)
        }
    }

    private fun handleToolCall(update: com.google.gson.JsonObject) {
        val title = update["title"]?.asString ?: "Unknown tool"
        val toolCallId = update["toolCallId"]?.asString ?: ""

        val filePath = extractFilePath(update, title)
        if (filePath != null && toolCallId.isNotEmpty()) {
            toolCallFiles[toolCallId] = filePath
        }
    }

    private fun extractFilePath(update: com.google.gson.JsonObject, title: String): String? {
        val locations = if (update.has("locations")) update.getAsJsonArray("locations") else null
        if (locations != null && locations.size() > 0) {
            val path = locations[0].asJsonObject["path"]?.asString
            if (path != null) return path
        }
        val pathMatch = Regex("""(?:Creating|Writing|Editing|Reading)\s+(.+\.\w+)""").find(title)
        return pathMatch?.groupValues?.get(1)
    }

    private fun handleToolCallUpdate(update: com.google.gson.JsonObject) {
        val status = update["status"]?.asString ?: ""
        val toolCallId = update["toolCallId"]?.asString ?: ""
        if (status != "completed" && status != "failed") return

        val filePath = toolCallFiles[toolCallId]
        if (status == "completed" && filePath != null) {
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

    private fun handlePlanUpdate(update: com.google.gson.JsonObject) {
        val entries = update.getAsJsonArray("entries") ?: return
        ApplicationManager.getApplication().invokeLater {
            // Remove existing plan group, but keep Files group
            val toRemove = mutableListOf<javax.swing.tree.DefaultMutableTreeNode>()
            for (i in 0 until planRoot.childCount) {
                val child = planRoot.getChildAt(i) as javax.swing.tree.DefaultMutableTreeNode
                if (child.userObject == "Plan") toRemove.add(child)
            }
            toRemove.forEach { planRoot.remove(it) }

            val planNode = javax.swing.tree.DefaultMutableTreeNode("Plan")
            for (entry in entries) {
                val obj = entry.asJsonObject
                val content = obj["content"]?.asString ?: "Step"
                val entryStatus = obj["status"]?.asString ?: "pending"
                val priority = obj["priority"]?.asString ?: ""
                val label = "$content [$entryStatus]${if (priority.isNotEmpty()) " ($priority)" else ""}"
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
            val isClaudeCli = agentManager.activeProfile.transportType ==
                TransportType.CLAUDE_CLI
            val isCLINotFound = "copilot cli not found" in diag.lowercase() ||
                ("not found" in diag.lowercase() && ("copilot" in diag.lowercase() || "claude" in diag.lowercase()))
            when {
                isCLINotFound && isClaudeCli -> {
                    val installUrl = "https://code.claude.com"
                    updateState(
                        "Claude CLI is not installed \u2014 install from $installUrl and run 'claude auth login'",
                        showInstall = true,
                    )
                }

                isCLINotFound -> {
                    val cmd = if (System.getProperty("os.name").lowercase().contains("win"))
                        "winget install GitHub.Copilot" else "npm install -g @github/copilot-cli"
                    updateState("Copilot CLI is not installed \u2014 install with: $cmd", showInstall = true)
                }

                isClaudeCli && authService.isAuthenticationError(diag) ->
                    updateState(
                        "Not signed in to Claude \u2014 run 'claude auth login' in a terminal, then click Retry.",
                        showSignIn = false,
                    )

                authService.isAuthenticationError(diag) ->
                    updateState("Not signed in to Copilot \u2014 click Sign In, then click Retry.", showSignIn = true)

                else -> updateState(if (isClaudeCli) "Claude CLI unavailable" else "Copilot CLI unavailable")
            }
        }
        banner.installHandler = {
            if (agentManager.activeProfile.transportType == TransportType.CLAUDE_CLI) {
                com.intellij.ide.BrowserUtil.browse("https://code.claude.com")
            } else {
                com.intellij.ide.BrowserUtil.browse("https://github.com/github/copilot-cli#installation")
            }
        }
        // Clear pending auth error on Retry so diagnostics re-verifies from scratch
        banner.retryHandler = { authService.clearPendingAuthError() }
        banner.signInHandler = {
            banner.showSignInPending()
            // Try inline auth first (captures device code from CLI stdout)
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
                    // Inline auth failed to parse — open terminal as fallback
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

        // Response/chat history area (top of splitter)
        val responsePanel = createResponsePanel()
        responsePanelContainer = JBPanel<JBPanel<*>>(BorderLayout())
        responsePanelContainer.add(responsePanel, BorderLayout.CENTER)
        val topPanel = JBPanel<JBPanel<*>>(BorderLayout())
        val northStack = JBPanel<JBPanel<*>>()
        northStack.layout = BoxLayout(northStack, BoxLayout.Y_AXIS)

        // Load models
        fun loadModels() {
            loadModelsAsync { models -> loadedModels = models }
        }

        // Setup banners: Copilot CLI / auth, GH CLI / auth
        copilotBanner = createCopilotSetupBanner {
            authService.pendingAuthError = null
            currentSessionId = null
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

        // Toggle a top border on responsePanelContainer: grey when no banners, none when a banner is showing
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

        // Splitter between chat area (top) and resizable input box (bottom).
        // OnePixelSplitter gives the thin, IDE-native 1px divider; proportion is persisted.
        val inputRow = createInputRow()
        val splitter = com.intellij.ui.OnePixelSplitter(true, "IdeAgent.InputSplitter", 0.78f)
        splitter.firstComponent = topPanel
        splitter.secondComponent = inputRow
        panel.add(splitter, BorderLayout.CENTER)

        // Fixed footer (toolbar) always stays at the very bottom
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

        // Single row: controls + usage (wraps on narrow windows)
        val controlsRow = createControlsRow()
        controlsRow.alignmentX = Component.LEFT_ALIGNMENT
        footer.add(controlsRow)

        return footer
    }

    private fun createInputRow(): JBPanel<JBPanel<*>> {
        val row = JBPanel<JBPanel<*>>(BorderLayout())
        val minHeight = JBUI.scale(48)
        row.minimumSize = JBUI.size(100, minHeight)
        // No maximumSize — the OnePixelSplitter controls the height
        // Use EditorTextFieldProvider for PsiFile-backed document (enables spell checking)
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
        // Property access forbidden: isOneLineMode getter is protected in EditorTextField
        @Suppress("UsePropertyAccessSyntax")
        promptTextArea.setOneLineMode(false)
        promptTextArea.border = null
        contextManager = PromptContextManager(project, promptTextArea) { text -> appendResponse(text) }

        // Drag-drop works on the EditorTextField wrapper (no editor needed)
        setupPromptDragDrop(promptTextArea)
        // Key bindings and context menu need the editor's content component.
        // addSettingsProvider runs when the editor is actually created,
        // unlike invokeLater which may fire before the editor exists.
        promptTextArea.addSettingsProvider { editor ->
            setupPromptKeyBindings(editor)
            setupPromptContextMenu(editor)
            // Use EditorEx built-in placeholder (visual-only, doesn't set actual text)
            editor.setPlaceholder(promptPlaceholder())
            editor.setShowPlaceholderWhenFocused(true)
            editor.settings.isUseSoftWraps = true
            editor.contentComponent.border = JBUI.Borders.empty(4, 6)
            editor.setBorder(null)

            // Auto-scroll the outer JBScrollPane to keep the caret visible while typing.
            // EditorTextField has an internal JViewport that swallows scrollRectToVisible calls
            // made on editor.contentComponent — they never reach the outer scroll pane.
            // Fix: convert the caret rect to promptTextArea coordinates so the call is handled
            // by the outer JViewport (promptTextArea is its direct view child).
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

        // Auto-revalidate on document changes
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
            handleStopRequest(currentPromptThread)
            setSendingState(false)
        } else {
            val rawText = promptTextArea.text.trim()
            if (rawText.isEmpty()) return
            consolePanel.disableQuickReplies()
            statusBanner?.dismissCurrent()
            setSendingState(true)

            // Collect context items from inline inlays BEFORE clearing the editor
            val contextItems = contextManager.collectInlineContextItems()
            // Agent sees inline text refs: "refactor `AuthLoginService.kt:116-170` please"
            val prompt = contextManager.replaceOrcsWithTextRefs(rawText, contextItems)
            val ctxFiles = if (contextItems.isNotEmpty()) {
                contextItems.map { item ->
                    Triple(item.name, item.path, if (item.isSelection) item.startLine else 0)
                }
            } else null
            // Chat bubble gets HTML with inline chip links at ORC positions
            val bubbleHtml = buildBubbleHtml(rawText, contextItems)
            consolePanel.addPromptEntry(prompt, ctxFiles, bubbleHtml)
            promptTextArea.text = ""

            ApplicationManager.getApplication().executeOnPooledThread {
                currentPromptThread = Thread.currentThread()
                executePrompt(prompt, contextItems)
                currentPromptThread = null
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
        leftGroup.addSeparator()
        leftGroup.add(RestartSessionGroup())

        controlsToolbar = ActionManager.getInstance().createActionToolbar(
            "CopilotControls", leftGroup, true
        )
        controlsToolbar.targetComponent = row
        controlsToolbar.isReservePlaceAutoPopupIcon = false

        val rightGroup = DefaultActionGroup()
        rightGroup.add(ProcessingIndicatorAction())
        if (BillingSettings.getInstance().isShowCopilotUsage) {
            rightGroup.add(billing.createUsageGraphAction())
        }

        val rightToolbar = ActionManager.getInstance().createActionToolbar(
            "CopilotRight", rightGroup, true
        )
        rightToolbar.targetComponent = row
        rightToolbar.isReservePlaceAutoPopupIcon = false

        row.add(controlsToolbar.component, BorderLayout.CENTER)
        row.add(rightToolbar.component, BorderLayout.EAST)

        return row
    }

    /** Toolbar action showing a native processing timer while the agent works */
    private inner class ProcessingIndicatorAction : AnAction("Processing"), CustomComponentAction {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) { /* No action needed — UI-only toolbar widget */
        }

        override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
            processingTimerPanel = ProcessingTimerPanel()
            com.intellij.openapi.util.Disposer.register(project, processingTimerPanel)
            return processingTimerPanel
        }
    }

    /**
     * Native Swing panel that shows a small animated spinner + elapsed-time counter
     * plus tool call count and requests used. Hidden when idle; visible once [start] is called.
     * On [stop], spinner changes to checkmark and stats remain visible until next [start].
     * Click to toggle between per-turn and session-wide stats.
     */
    private inner class ProcessingTimerPanel : JBPanel<ProcessingTimerPanel>(), com.intellij.openapi.Disposable {
        private val spinner = AsyncProcessIcon("CopilotProcessing")
        private val doneIcon = JBLabel(AllIcons.Actions.Checked)
        private val timerLabel = JBLabel("")
        private val toolsLabel = JBLabel("")
        private val requestsLabel = JBLabel("")
        private var startedAt = 0L
        private var toolCallCount = 0
        private val ticker = Timer(1000) { refreshDisplay() }

        // Session-wide accumulators
        private var sessionTotalTimeMs = 0L
        private var sessionTotalToolCalls = 0
        private var sessionTurnCount = 0
        private var isRunning = false

        private val modeTurn = 0
        private val modeSession = 1
        private var displayMode = modeTurn

        init {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            border = JBUI.Borders.emptyRight(6)
            val smallGray = JBUI.Fonts.smallFont()
            spinner.isVisible = false
            doneIcon.isVisible = false
            doneIcon.font = smallGray
            timerLabel.foreground = JBUI.CurrentTheme.Label.disabledForeground(); timerLabel.font =
                smallGray; timerLabel.isVisible = false
            toolsLabel.foreground = JBUI.CurrentTheme.Label.disabledForeground(); toolsLabel.font =
                smallGray; toolsLabel.isVisible = false
            requestsLabel.foreground = JBUI.CurrentTheme.Label.disabledForeground(); requestsLabel.font =
                smallGray; requestsLabel.isVisible = false
            add(spinner)
            add(Box.createHorizontalStrut(JBUI.scale(4)))
            add(doneIcon)
            add(Box.createHorizontalStrut(JBUI.scale(4)))
            add(timerLabel)
            add(Box.createHorizontalStrut(JBUI.scale(4)))
            add(toolsLabel)
            add(Box.createHorizontalStrut(JBUI.scale(4)))
            add(requestsLabel)
            isVisible = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Click to toggle turn/session stats"
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    displayMode = if (displayMode == modeTurn) modeSession else modeTurn
                    refreshDisplay()
                }
            })
        }

        fun start() {
            startedAt = System.currentTimeMillis()
            toolCallCount = 0
            isRunning = true
            displayMode = modeTurn
            timerLabel.text = "0s"
            toolsLabel.text = ""
            requestsLabel.text = ""
            spinner.isVisible = true
            spinner.resume()
            doneIcon.isVisible = false
            timerLabel.isVisible = true
            toolsLabel.isVisible = false
            requestsLabel.isVisible = false
            isVisible = true
            ticker.start()
            revalidate(); repaint()
        }

        fun stop() {
            ticker.stop()
            isRunning = false
            // Accumulate into session totals
            sessionTotalTimeMs += System.currentTimeMillis() - startedAt
            sessionTotalToolCalls += toolCallCount
            sessionTurnCount++
            refreshDisplay()
            spinner.suspend()
            spinner.isVisible = false
            doneIcon.isVisible = true
            revalidate(); repaint()
        }

        fun resetSession() {
            sessionTotalTimeMs = 0L
            sessionTotalToolCalls = 0
            sessionTurnCount = 0
            displayMode = modeTurn
        }

        override fun dispose() {
            ticker.stop()
            com.intellij.openapi.util.Disposer.dispose(spinner)
        }

        fun incrementToolCalls() {
            toolCallCount++
            refreshDisplay()
        }

        private fun refreshDisplay() {
            when (displayMode) {
                modeTurn -> refreshTurnMode()
                modeSession -> refreshSessionMode()
            }
            revalidate(); repaint()
        }

        private fun refreshTurnMode() {
            toolTipText = "Turn stats · Click for session"
            updateLabel()
            toolsLabel.text = if (toolCallCount > 0) "\u2022 $toolCallCount tools" else ""
            toolsLabel.isVisible = toolCallCount > 0
            requestsLabel.isVisible = false
            if (!isRunning) {
                doneIcon.icon = AllIcons.Actions.Checked; doneIcon.text = null
            }
        }

        private fun refreshSessionMode() {
            val totalMs =
                sessionTotalTimeMs + if (isRunning) (System.currentTimeMillis() - startedAt) else 0
            val totalSec = totalMs / 1000
            timerLabel.text = if (totalSec < 60) "${totalSec}s" else "${totalSec / 60}m ${totalSec % 60}s"
            val totalTools = sessionTotalToolCalls + if (isRunning) toolCallCount else 0
            toolsLabel.text = if (totalTools > 0) "\u2022 $totalTools tools" else ""
            toolsLabel.isVisible = totalTools > 0
            // Session requests: local counter (no API polling needed)
            val sessionReqs = billing.localSessionRequests
            requestsLabel.text = if (sessionReqs > 0) "\u2022 $sessionReqs req" else "\u2022 0 req"
            requestsLabel.isVisible = true
            toolTipText = "Session totals · Click for turn"
            doneIcon.icon = null; doneIcon.text = "\u2211"
        }

        private fun updateLabel() {
            val elapsed = (System.currentTimeMillis() - startedAt) / 1000
            timerLabel.text = if (elapsed < 60) "${elapsed}s" else "${elapsed / 60}m ${elapsed % 60}s"
        }
    }

    // Send/Stop toggle action for the toolbar
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

    // Unified attach dropdown: current file, selection, or search project files
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
                override fun actionPerformed(ev: AnActionEvent) = handleCreateScratch()
            })
            group.addSeparator()

            // Trigger character sub-menu
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

    /** Open a project-root file in the editor if it exists */
    private fun openProjectFile(fileName: String) {
        val base = project.basePath ?: return
        val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .findFileByPath("$base/$fileName") ?: return
        com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(vf, true)
    }

    /** Dropdown action for project configuration files: Instructions, project todo file, Agent Definitions, MCP Instructions */
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

            // Build menu from configured project file entries
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

    // ComboBoxAction for model selection — matches Run panel dropdown style
    private inner class ModelSelectorAction : ComboBoxAction() {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun createPopupActionGroup(button: JComponent, context: DataContext): DefaultActionGroup {
            val group = DefaultActionGroup()
            loadedModels.forEachIndexed { index, model ->
                val cost = model.usage ?: "1x"
                group.add(object : AnAction("${model.name}  ($cost)") {
                    override fun actionPerformed(e: AnActionEvent) {
                        if (index == selectedModelIndex) return

                        selectedModelIndex = index
                        agentManager.settings.setSelectedModel(model.id)
                        LOG.info("Model selected: ${model.id} (index=$index)")
                        ApplicationManager.getApplication().invokeLater {
                            consolePanel.setCurrentModel(model.id)
                            consolePanel.setPromptStats(model.id, getModelMultiplier(model.id))
                        }
                        ApplicationManager.getApplication().executeOnPooledThread {
                            try {
                                val client = agentManager.client
                                val sessionId = currentSessionId
                                if (sessionId != null) {
                                    // Switch model on current session (no restart needed)
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

    private fun createResponsePanel(): JComponent {
        consolePanel = ChatConsolePanel(project)
        consolePanel.onQuickReply = { text -> ApplicationManager.getApplication().invokeLater { sendQuickReply(text) } }
        // Register for proper JCEF browser disposal
        com.intellij.openapi.util.Disposer.register(project, consolePanel)
        // Placeholder only shown if no conversation is restored (set after restore check)
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

    // Use IntelliJ's action system (not Swing InputMap) so the shortcut takes priority
    // over the editor's built-in Enter handler (ACTION_EDITOR_ENTER).
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

    private fun registerPasteIntercept(editor: EditorEx, contentComponent: JComponent) {
        val pasteStrokes = setOf(
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_V, java.awt.event.InputEvent.CTRL_DOWN_MASK),
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_V, java.awt.event.InputEvent.META_DOWN_MASK),
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_INSERT, java.awt.event.InputEvent.SHIFT_DOWN_MASK)
        )

        // Use IdeEventQueue preprocessor (runs before IdeKeyEventDispatcher) so we consume the
        // event before any other handler sees it — avoiding the double-paste that occurred when
        // popup.cancel() restored focus to contentComponent mid-dispatch and IdeKeyEventDispatcher
        // fired our shortcut again for the now-focused component.
        com.intellij.ide.IdeEventQueue.getInstance().addPreprocessor(
            com.intellij.ide.IdeEventQueue.EventDispatcher { event ->
                if (event !is java.awt.event.KeyEvent) return@EventDispatcher false
                if (editor.isDisposed) return@EventDispatcher false
                if (event.id != java.awt.event.KeyEvent.KEY_PRESSED) return@EventDispatcher false
                if (KeyStroke.getKeyStrokeForEvent(event) !in pasteStrokes) return@EventDispatcher false
                // Only intercept when our prompt editor is in the focus hierarchy
                val focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                if (!SwingUtilities.isDescendingFrom(
                        focused,
                        contentComponent
                    )
                ) return@EventDispatcher false

                val clipText = contextManager.getClipboardText()
                if (clipText != null && (clipText.lines().size > 3 || clipText.length > 500)) {
                    val projectSource = contextManager.findClipboardSourceInProject(clipText)
                    if (projectSource != null) {
                        contextManager.insertInlineChip(editor, projectSource)
                    } else {
                        handlePasteToScratch(clipText)
                    }
                    true
                } else {
                    // Small text: let the editor's own paste handler run normally.
                    // Manually calling handler.execute here would double-paste because the
                    // event still reaches the editor's InputMap on some IntelliJ versions.
                    false
                }
            },
            project
        )
    }

    // Trigger character detection: when the configured char (# or @) is typed,
    // remove it and open the file search popup instead
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
        // Build a combined action group: native editor menu + our custom items
        val group = DefaultActionGroup().apply {
            // Include the standard editor popup menu (Cut, Copy, Paste, Select All, etc.)
            val editorPopup = ActionManager.getInstance().getAction("EditorPopupMenu")
            if (editorPopup != null) {
                add(editorPopup)
            }

            addSeparator()

            // Attach actions
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

            // Conversation actions
            add(object : AnAction("New Conversation", null, AllIcons.General.Add) {
                override fun actionPerformed(e: AnActionEvent) {
                    currentSessionId = null
                    consolePanel.addSessionSeparator(
                        java.time.Instant.now().toString(),
                        agentManager.activeProfile.displayName
                    )
                    updateSessionInfo()
                }
            })
        }

        // Install via the IntelliJ editor popup handler (replaces the default EditorPopupMenu with our combined group)
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

    private fun handleStopRequest(promptThread: Thread?) {
        val sessionId = currentSessionId
        if (sessionId != null) {
            try {
                agentManager.client.cancelSession(sessionId)
            } catch (_: Exception) {
                // Best-effort cancellation
            }
        }
        promptThread?.interrupt()
        consolePanel.cancelAllRunning()
        consolePanel.addErrorEntry("Stopped by user")
    }

    private fun ensureSessionCreated(client: AgentClient): String {
        if (currentSessionId == null) {
            currentSessionId = client.createSession(project.basePath)
            updateSessionInfo()
            val savedModel = agentManager.settings.selectedModel
            if (!savedModel.isNullOrEmpty()) {
                try {
                    client.setModel(currentSessionId!!, savedModel)
                } catch (ex: Exception) {
                    LOG.warn("Failed to set model $savedModel on new session", ex)
                }
            }
        }
        return currentSessionId!!
    }

    private fun buildEffectivePrompt(prompt: String): String {
        var effective = prompt

        val selectedAgent = agentManager.settings.selectedAgent
        if (selectedAgent.isNotEmpty()) {
            effective = "@$selectedAgent $effective"
        }

        if (!conversationSummaryInjected) {
            conversationSummaryInjected = true
            val summary = consolePanel.getCompressedSummary()
            if (summary.isNotEmpty()) {
                effective = "$summary\n\n$effective"
            }
        }
        return effective
    }

    /**
     * Build the prompt sent to the agent, optionally including referenced file content inline.
     *
     * <p>ACP {@code ResourceReference} objects are always sent as structured content blocks
     * in the prompt array. However, some agents (e.g., GitHub Copilot) surface them only as
     * metadata (path + line count) without inlining the actual content for the model. For
     * those agents, the referenced file content is also appended as plain text so the model
     * sees it. This behaviour is controlled by
     * {@link com.github.catatafishen.ideagentforcopilot.bridge.AgentConfig#requiresResourceContentDuplication()}.</p>
     */
    private fun buildEffectivePromptWithContent(
        client: AgentClient,
        prompt: String,
        references: List<ResourceReference>,
        contextItems: List<ContextItemData>
    ): String {
        val base = buildEffectivePrompt(prompt)
        if (references.isEmpty() || !client.requiresResourceContentDuplication()) return base

        val contentBlocks = references.mapIndexed { i, ref ->
            val label = contextItems.getOrNull(i)?.name ?: ref.uri().substringAfterLast("/")
            "--- $label ---\n${ref.text()}"
        }
        return "$base\n\n${contentBlocks.joinToString("\n\n")}"
    }

    private fun handlePromptCompletion(prompt: String) {
        com.github.catatafishen.ideagentforcopilot.psi.PsiBridgeService.getInstance(project).flushPendingAutoFormat()
        com.github.catatafishen.ideagentforcopilot.psi.PsiBridgeService.getInstance(project).clearFileAccessTracking()
        consolePanel.finishResponse(turnToolCallCount, turnModelId, getModelMultiplier(turnModelId))
        notifyIfUnfocused(turnToolCallCount)
        saveTurnStatistics(prompt, turnToolCallCount, turnModelId)
        saveConversation()
        billing.recordTurnCompleted(getModelMultiplier(turnModelId))

        val lastResponse = consolePanel.getLastResponseText()
        val quickReplies = detectQuickReplies(lastResponse)
        if (quickReplies.isNotEmpty()) {
            ApplicationManager.getApplication().invokeLater { consolePanel.showQuickReplies(quickReplies) }
        }

        // Force Swing repaint so the JCEF panel reflects final state
        ApplicationManager.getApplication().invokeLater {
            consolePanel.component.revalidate()
            consolePanel.component.repaint()
            if (ActiveAgentManager.getFollowAgentFiles(project)) {
                promptTextArea.requestFocusInWindow()
            }
        }
    }

    private fun executePrompt(prompt: String, contextItems: List<ContextItemData> = emptyList()) {
        try {
            if (isBlockedByAuth()) return

            val client = agentManager.client
            val sessionId = ensureSessionCreated(client)
            wirePermissionListener(client)

            val modelId = prepareModelAndTurnState()

            val references = contextManager.buildContextReferences(contextItems.ifEmpty { null })
            val effectivePrompt = buildEffectivePromptWithContent(client, prompt, references, contextItems)
            addContextEntries(references, contextItems)

            dispatchPromptWithRetry(client, sessionId, effectivePrompt, modelId, references)

            handlePromptCompletion(prompt)
        } catch (e: Exception) {
            handlePromptError(e)
        } finally {
            setSendingState(false)
        }
    }

    private fun dispatchPromptWithRetry(
        client: AgentClient,
        initialSessionId: String,
        effectivePrompt: String,
        modelId: String,
        references: List<ResourceReference>
    ) {
        val refs = references.ifEmpty { null }
        val onChunk = createStreamingChunkHandler()
        val onUpdate = java.util.function.Consumer<com.google.gson.JsonObject> { update ->
            handlePromptStreamingUpdate(update)
        }
        // Pass the session ID as a parameter so the retry uses the freshly created session,
        // not the stale ID captured in the closure.
        val sendPromptCall: (String) -> Unit = { sid ->
            client.sendPrompt(sid, effectivePrompt, modelId, refs, onChunk, onUpdate, null)
        }
        sendWithSessionRetry(client, initialSessionId, sendPromptCall) {}
    }

    private fun isBlockedByAuth(): Boolean {
        if (authService.pendingAuthError == null) return false
        ApplicationManager.getApplication().invokeLater {
            consolePanel.addErrorEntry("Not signed in to Copilot. Use the Sign In button in the banner above.")
            copilotBanner?.triggerCheck()
        }
        return true
    }

    private fun wirePermissionListener(client: AgentClient) {
        client.setPermissionRequestListener { req: PermissionRequest ->
            ApplicationManager.getApplication().invokeLater {
                consolePanel.showPermissionRequest(
                    req.reqId.toString(), req.displayName, req.description
                ) { response -> req.respond(response) }
                notifyPermissionRequestIfUnfocused(req.displayName)
            }
        }
    }

    private fun prepareModelAndTurnState(): String {
        val selectedModelObj =
            if (selectedModelIndex >= 0 && selectedModelIndex < loadedModels.size) loadedModels[selectedModelIndex] else null
        val modelId = selectedModelObj?.id ?: ""
        turnToolCallCount = 0
        activeSubAgentId = null
        turnModelId = modelId
        ApplicationManager.getApplication().invokeLater {
            consolePanel.setCurrentProfile(agentManager.activeProfileId)
            consolePanel.setCurrentModel(modelId)
            consolePanel.setPromptStats(modelId, getModelMultiplier(modelId))
        }
        return modelId
    }

    private fun addContextEntries(references: List<ResourceReference>, contextItems: List<ContextItemData>) {
        if (references.isNotEmpty() && contextItems.isNotEmpty()) {
            val contextFiles = contextItems.map { Pair(it.name, it.path) }
            consolePanel.addContextFilesEntry(contextFiles)
        }
    }

    private fun createStreamingChunkHandler(): java.util.function.Consumer<String> {
        return java.util.function.Consumer { chunk -> appendResponse(chunk) }
    }

    /**
     * Attempts to call [sendCall] with [initialSessionId]. If it fails with a "not found" session error,
     * invalidates the current session, creates a fresh one, resets state via [onRetry],
     * and retries once with the new session ID. Returns the (possibly new) session ID.
     */
    private fun sendWithSessionRetry(
        client: AgentClient,
        initialSessionId: String,
        sendCall: (String) -> Unit,
        onRetry: () -> Unit
    ): String {
        try {
            sendCall(initialSessionId)
            return initialSessionId
        } catch (e: AcpException) {
            if (e.message != null && e.message!!.contains("not found", ignoreCase = true)) {
                LOG.info("Session expired ('not found'), creating new session and retrying")
                currentSessionId = null
                val newSessionId = ensureSessionCreated(client)
                onRetry()
                sendCall(newSessionId)
                return newSessionId
            }
            throw e
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

        // Quick-replies don't carry context items
        consolePanel.addPromptEntry(trimmed, null)

        ApplicationManager.getApplication().executeOnPooledThread {
            currentPromptThread = Thread.currentThread()
            executePrompt(trimmed)
            currentPromptThread = null
        }
    }

    /**
     * Extract quick-reply options from `[quick-reply: A | B | C]` tags in the response.
     */
    private fun detectQuickReplies(responseText: String): List<String> {
        val match = QUICK_REPLY_TAG_REGEX.findAll(responseText).lastOrNull() ?: return emptyList()
        return match.groupValues[1].split("|").map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun handlePromptStreamingUpdate(update: com.google.gson.JsonObject) {
        val updateType = update["sessionUpdate"]?.asString ?: ""
        when (updateType) {
            "tool_call" -> handleStreamingToolCall(update)
            "tool_call_update" -> handleStreamingToolCallUpdate(update)
            "agent_thought_chunk" -> handleStreamingAgentThought(update)
        }
        handleAcpUpdate(update)
    }

    private fun extractJsonElementText(element: com.google.gson.JsonElement): String? {
        return when {
            element.isJsonObject -> com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(element)
            element.isJsonPrimitive -> element.asString
            else -> null
        }
    }

    private fun extractJsonArguments(update: com.google.gson.JsonObject): String? {
        return update["arguments"]?.let { extractJsonElementText(it) }
            ?: update["input"]?.let { extractJsonElementText(it) }
            ?: update["rawInput"]?.let { extractJsonElementText(it) }
    }

    private fun handleStreamingToolCall(update: com.google.gson.JsonObject) {
        val title = update["title"]?.asString ?: "tool"
        val status = update["status"]?.asString ?: ""
        val toolCallId = update["toolCallId"]?.asString ?: ""
        val kind = update["kind"]?.asString ?: "other"
        val arguments = extractJsonArguments(update)
        if (status != "completed" && toolCallId.isNotEmpty()) {
            // Detect sub-agent calls by checking for agent_type in arguments
            val agentType = extractJsonField(arguments, "agent_type")
            if (agentType != null) {
                turnToolCallCount++
                if (::processingTimerPanel.isInitialized) processingTimerPanel.incrementToolCalls()
                toolCallTitles[toolCallId] = "task" // mark as sub-agent for update routing
                activeSubAgentId = toolCallId
                agentManager.client.setSubAgentActive(true)
                agentManager.settings.setActiveAgentLabel(agentType)
                val description = title.ifBlank { extractJsonField(arguments, "description") ?: "Sub-agent task" }
                val prompt = extractJsonField(arguments, "prompt")
                consolePanel.addSubAgentEntry(toolCallId, agentType, description, prompt)
            } else if (activeSubAgentId != null) {
                // Internal tool call from a running sub-agent — show on sub-agent's message
                turnToolCallCount++
                if (::processingTimerPanel.isInitialized) processingTimerPanel.incrementToolCalls()
                toolCallTitles[toolCallId] = "subagent_internal"
                consolePanel.addSubAgentToolCall(activeSubAgentId!!, toolCallId, title, arguments, kind)
            } else {
                turnToolCallCount++
                if (::processingTimerPanel.isInitialized) processingTimerPanel.incrementToolCalls()
                toolCallTitles[toolCallId] = title
                consolePanel.addToolCallEntry(toolCallId, title, arguments, kind)
            }
        }
    }

    private fun extractJsonField(json: String?, key: String): String? {
        if (json.isNullOrBlank()) return null
        return try {
            com.google.gson.JsonParser.parseString(json).asJsonObject[key]?.asString
        } catch (_: Exception) {
            null
        }
    }

    private fun handleStreamingToolCallUpdate(update: com.google.gson.JsonObject) {
        val status = update["status"]?.asString ?: ""
        val toolCallId = update["toolCallId"]?.asString ?: ""
        val result = update["result"]?.asString
            ?: update["content"]?.let { extractContentText(it) }
        val callType = toolCallTitles[toolCallId]
        val isSubAgent = callType == "task"
        val isInternal = callType == "subagent_internal"
        if (status == "completed") {
            if (isSubAgent) {
                activeSubAgentId = null
                agentManager.client.setSubAgentActive(false)
                agentManager.settings.setActiveAgentLabel(null)
                consolePanel.updateSubAgentResult(toolCallId, "completed", result)
            } else if (isInternal) {
                consolePanel.updateSubAgentToolCall(toolCallId, "completed", result)
            } else {
                consolePanel.updateToolCall(toolCallId, "completed", result)
            }
        } else if (status == "failed") {
            val error = update["error"]?.asString
                ?: result
                ?: update.toString().take(500)
            if (isSubAgent) {
                activeSubAgentId = null
                agentManager.client.setSubAgentActive(false)
                agentManager.settings.setActiveAgentLabel(null)
                consolePanel.updateSubAgentResult(toolCallId, "failed", error)
            } else if (isInternal) {
                consolePanel.updateSubAgentToolCall(toolCallId, "failed", error)
            } else {
                consolePanel.updateToolCall(toolCallId, "failed", error)
            }
        }
        if (status == "completed" || status == "failed") {
            saveConversationThrottled()
        }
    }

    private fun extractContentText(element: com.google.gson.JsonElement): String? {
        return try {
            when {
                element.isJsonArray -> {
                    element.asJsonArray.mapNotNull { block ->
                        extractContentBlockText(block)
                    }.joinToString("\n").ifEmpty { null }
                }

                element.isJsonObject -> element.asJsonObject["text"]?.asString
                element.isJsonPrimitive -> element.asString
                else -> null
            }
        } catch (_: Exception) {
            element.toString()
        }
    }

    private fun extractContentBlockText(block: com.google.gson.JsonElement): String? {
        if (!block.isJsonObject) return if (block.isJsonPrimitive) block.asString else block.toString()
        val obj = block.asJsonObject
        return obj["content"]?.let { inner ->
            if (inner.isJsonObject) inner.asJsonObject["text"]?.asString
            else if (inner.isJsonPrimitive) inner.asString
            else null
        } ?: obj["text"]?.asString
    }

    private fun handleStreamingAgentThought(update: com.google.gson.JsonObject) {
        val content = update["content"]?.asJsonObject
        val text = content?.get("text")?.asString
        if (text != null) {
            consolePanel.appendThinkingText(text)
        }
    }

    private fun handlePromptError(e: Exception) {
        val isCancelled = e is InterruptedException || e.cause is InterruptedException
        val msg = if (isCancelled) "Request cancelled" else e.message ?: MSG_UNKNOWN_ERROR

        // Persist whatever was collected so far — partial turns survive disconnects and crashes.
        saveConversation()

        // Show the auth banner immediately when an auth error is detected
        if (authService.isAuthenticationError(msg)) {
            authService.markAuthError(msg)
            copilotBanner?.triggerCheck()
            consolePanel.addErrorEntry("Error: $msg")
            e.printStackTrace()
            return
        }

        val isRecoverable = isCancelled || (e is AcpException && e.isRecoverable)
        if (!isRecoverable) {
            currentSessionId = null
            updateSessionInfo()
        }

        // ACP/stream errors may leave the JCEF panel unresponsive, so show the error in the
        // always-visible Swing status banner with a Reconnect action. Also add an entry in the
        // chat panel so it is visible in the conversation history if the browser is alive.
        consolePanel.addErrorEntry("Error: $msg")
        if (!isCancelled) {
            statusBanner?.showError(msg, "Reconnect") { reconnectAfterError() }
        }

        e.printStackTrace()
    }

    private fun reconnectAfterError() {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                agentManager.restart()
                ApplicationManager.getApplication().invokeLater {
                    statusBanner?.showInfo("Reconnected — ready for a new message.")
                }
            } catch (ex: Exception) {
                LOG.warn("Reconnect failed", ex)
                ApplicationManager.getApplication().invokeLater {
                    statusBanner?.showError("Reconnect failed: ${ex.message ?: "unknown error"}")
                }
            }
        }
    }

    private fun getModelMultiplier(modelId: String): String {
        return try {
            agentManager.client.getModelMultiplier(modelId)
        } catch (_: Exception) {
            "1x"
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
                    addProperty("multiplier", getModelMultiplier(modelId))
                    addProperty("toolCalls", toolCalls)
                }
                statsFile.appendText(entry.toString() + "\n")
            } catch (_: Exception) { /* best-effort */
            }
        }
    }

    private fun conversationFile(): java.io.File {
        val dir = java.io.File(project.basePath ?: "", AGENT_WORK_DIR)
        dir.mkdirs()
        return java.io.File(dir, "conversation.json")
    }

    /** Move conversation.json to conversations/conversation-<timestamp>.json for future restore. */
    private fun archiveConversation() {
        try {
            val src = conversationFile()
            if (!src.exists() || src.length() < 10) return
            val archiveDir = java.io.File(src.parentFile, "conversations")
            archiveDir.mkdirs()
            val stamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss"))
            val renamed = src.renameTo(java.io.File(archiveDir, "conversation-$stamp.json"))
            if (!renamed) LOG.debug("Could not archive conversation file")
        } catch (_: Exception) { /* best-effort */
        }
    }

    private fun notifyIfUnfocused(toolCallCount: Int) {
        val frame = com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project) ?: return
        if (frame.isActive) return
        val title = "Copilot Response Ready"
        val content =
            if (toolCallCount > 0) "Turn completed with $toolCallCount tool call${if (toolCallCount != 1) "s" else ""}"
            else "Turn completed"
        // Balloon attached to the Copilot tool window tab (same style as build/test notifications)
        com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            .notifyByBalloon(
                "AgentBridge",
                com.intellij.openapi.ui.MessageType.INFO,
                "<b>$title</b><br>$content"
            )
        // OS-native notification with sound
        com.intellij.ui.SystemNotifications.getInstance().notify("AgentBridge Notifications", title, content)
        // Flash the taskbar icon
        com.intellij.ui.AppIcon.getInstance().requestAttention(project, false)
    }

    private fun notifyPermissionRequestIfUnfocused(toolName: String) {
        val frame = com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project) ?: return
        if (frame.isActive) return
        val title = "Agent Needs Approval"
        val content = "Permission requested for: $toolName"
        com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            .notifyByBalloon(
                "AgentBridge",
                com.intellij.openapi.ui.MessageType.WARNING,
                "<b>$title</b><br>$content"
            )
        com.intellij.ui.SystemNotifications.getInstance().notify("AgentBridge Notifications", title, content)
        com.intellij.ui.AppIcon.getInstance().requestAttention(project, true)
    }

    private fun saveConversation() {
        lastIncrementalSaveMs = System.currentTimeMillis()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                conversationFile().writeText(consolePanel.serializeEntries())
            } catch (_: Exception) { /* best-effort */
            }
        }
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
            try {
                // Primary: current session file. Fallback: most recent archive (e.g. after startup archive).
                val file = conversationFile().takeIf { it.exists() && it.length() >= 10 }
                    ?: run {
                        val archiveDir = java.io.File(
                            java.io.File(project.basePath ?: return@executeOnPooledThread, AGENT_WORK_DIR),
                            "conversations"
                        )
                        archiveDir.listFiles { _, n -> n.startsWith("conversation-") && n.endsWith(".json") }
                            ?.maxByOrNull { it.name }
                    }
                if (file == null) {
                    ApplicationManager.getApplication().invokeLater { onComplete() }
                    return@executeOnPooledThread
                }
                val json = file.readText()
                ApplicationManager.getApplication().invokeLater {
                    consolePanel.restoreEntries(json)
                    onComplete()
                }
            } catch (_: Exception) {
                ApplicationManager.getApplication().invokeLater { onComplete() }
            }
        }
    }

    private fun handlePasteToScratch(text: String) {
        val settings = com.github.catatafishen.ideagentforcopilot.settings.ScratchTypeSettings.getInstance()
        val enabledLanguages = settings.enabledLanguages

        if (enabledLanguages.isEmpty()) {
            Messages.showInfoMessage(
                project,
                "No languages are enabled. Configure them in Settings → Tools → Scratch File Types.",
                "No Scratch Languages"
            )
            return
        }

        // Build ordered list: detected language first, then Plain Text, then the rest
        val detected = com.github.catatafishen.ideagentforcopilot.psi.PlatformApiCompat
            .detectLanguageFromContent(text)
        val detectedMatch = if (detected != null) enabledLanguages.find { it.id == detected.id } else null
        val plainText = enabledLanguages.find { it.id == com.intellij.openapi.fileTypes.PlainTextLanguage.INSTANCE.id }

        val orderedLanguages = buildList {
            if (detectedMatch != null) add(detectedMatch)
            if (plainText != null && plainText != detectedMatch) add(plainText)
            for (lang in enabledLanguages) {
                if (lang != detectedMatch && lang != plainText) add(lang)
            }
        }

        val popup = com.intellij.ide.scratch.LRUPopupBuilder
            .languagePopupBuilder(project, "Paste as Scratch File (Paste Again to Skip)") { lang ->
                lang.associatedFileType?.icon ?: AllIcons.FileTypes.Any_type
            }
            .forValues(orderedLanguages)
            .onChosen { lang ->
                val ext = lang.associatedFileType?.defaultExtension ?: return@onChosen
                createAndAttachScratch(ext, text)
            }
            .buildPopup()

        registerPasteToSkip(popup, text)

        popup.showCenteredInCurrentWindow(project)
    }

    private fun registerPasteToSkip(popup: com.intellij.openapi.ui.popup.JBPopup, text: String) {
        val pasteStrokes = setOf(
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_V, java.awt.event.InputEvent.CTRL_DOWN_MASK),
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_V, java.awt.event.InputEvent.META_DOWN_MASK),
            KeyStroke.getKeyStroke(
                java.awt.event.KeyEvent.VK_INSERT, java.awt.event.InputEvent.SHIFT_DOWN_MASK
            )
        )

        var swallowFollowUp = false
        var pasteIntercepted = false

        val disposable = com.intellij.openapi.util.Disposer.newDisposable("pasteToSkip")
        com.intellij.ide.IdeEventQueue.getInstance().addPreprocessor(
            com.intellij.ide.IdeEventQueue.EventDispatcher { event ->
                if (event !is java.awt.event.KeyEvent) return@EventDispatcher false
                if (swallowFollowUp) {
                    return@EventDispatcher handleFollowUpEvent(event, disposable) { swallowFollowUp = false }
                }
                if (!popup.isVisible) return@EventDispatcher false
                if (event.id != java.awt.event.KeyEvent.KEY_PRESSED) return@EventDispatcher false
                if (KeyStroke.getKeyStrokeForEvent(event) !in pasteStrokes) return@EventDispatcher false
                swallowFollowUp = true
                pasteIntercepted = true
                executePasteFromPopup(popup, text)
                true
            },
            disposable
        )

        popup.addListener(object : com.intellij.openapi.ui.popup.JBPopupListener {
            override fun onClosed(event: com.intellij.openapi.ui.popup.LightweightWindowEvent) {
                if (!pasteIntercepted) {
                    com.intellij.openapi.util.Disposer.dispose(disposable)
                }
            }
        })
    }

    /**
     * Handles KEY_TYPED and KEY_RELEASED follow-up events after a paste stroke was consumed.
     * Returns true to swallow the event. Self-disposes on KEY_RELEASED via [clearSwallow].
     */
    private fun handleFollowUpEvent(
        event: java.awt.event.KeyEvent,
        disposable: com.intellij.openapi.Disposable,
        clearSwallow: () -> Unit
    ): Boolean {
        if (event.id == java.awt.event.KeyEvent.KEY_RELEASED) {
            clearSwallow()
            ApplicationManager.getApplication().invokeLater {
                com.intellij.openapi.util.Disposer.dispose(disposable)
            }
        }
        return true
    }

    private fun executePasteFromPopup(popup: com.intellij.openapi.ui.popup.JBPopup, text: String) {
        popup.cancel()
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            val editor = promptTextArea.editor ?: return@runWriteCommandAction
            val offset = editor.caretModel.offset
            editor.document.insertString(offset, text)
            editor.caretModel.moveToOffset(offset + text.length)
        }
        // Return focus to prompt so user can keep typing
        ApplicationManager.getApplication().invokeLater {
            promptTextArea.editor?.contentComponent?.requestFocusInWindow()
        }
    }

    private fun handleCreateScratch() {
        val settings = com.github.catatafishen.ideagentforcopilot.settings.ScratchTypeSettings.getInstance()
        val enabledLanguages = settings.enabledLanguages

        if (enabledLanguages.isEmpty()) {
            Messages.showInfoMessage(
                project,
                "No languages are enabled. Configure them in Settings → Tools → Scratch File Types.",
                "No Scratch Languages"
            )
            return
        }

        com.intellij.ide.scratch.LRUPopupBuilder
            .languagePopupBuilder(project, "New Scratch File") { lang ->
                lang.associatedFileType?.icon ?: AllIcons.FileTypes.Any_type
            }
            .forValues(enabledLanguages)
            .onChosen { lang ->
                val ext = lang.associatedFileType?.defaultExtension ?: return@onChosen
                createAndAttachScratch(ext)
            }
            .buildPopup()
            .showCenteredInCurrentWindow(project)
    }

    private fun createAndAttachScratch(ext: String, initialContent: String? = null) {
        ApplicationManager.getApplication().invokeLater {
            try {
                val scratchService = com.intellij.ide.scratch.ScratchFileService.getInstance()
                val scratchRoot = com.intellij.ide.scratch.ScratchRootType.getInstance()
                val name = "scratch.$ext"

                @Suppress("RedundantCast") // Explicit Computable needed: runWriteAction is overloaded
                val file = ApplicationManager.getApplication().runWriteAction(
                    com.intellij.openapi.util.Computable<com.intellij.openapi.vfs.VirtualFile?> {
                        try {
                            val vf = scratchService.findFile(
                                scratchRoot, name,
                                com.intellij.ide.scratch.ScratchFileService.Option.create_new_always
                            )
                            if (vf != null && !initialContent.isNullOrEmpty()) {
                                vf.setBinaryContent(initialContent.toByteArray(Charsets.UTF_8))
                            }
                            vf
                        } catch (e: java.io.IOException) {
                            LOG.warn("Failed to create scratch file", e)
                            null
                        }
                    }
                )

                if (file != null) {
                    com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                        .openFile(file, true)
                    val promptEditor = promptTextArea.editor as? EditorEx
                    if (promptEditor != null) {
                        contextManager.insertInlineChip(
                            promptEditor,
                            ContextItemData(
                                path = file.path, name = file.name,
                                startLine = 1, endLine = 0,
                                fileTypeName = file.fileType.name, isSelection = false
                            )
                        )
                    }
                    // openFile(focus=true) steals focus; schedule a second invokeLater so
                    // this runs after the file editor has finished grabbing focus.
                    ApplicationManager.getApplication().invokeLater {
                        promptTextArea.editor?.contentComponent?.requestFocusInWindow()
                    }
                }
            } catch (e: Exception) {
                LOG.warn("Failed to create scratch file from attach menu", e)
            }
        }
    }

    fun getComponent(): JComponent = mainPanel

    fun resetSession() {
        currentSessionId = null
        conversationSummaryInjected = false
        billing.billingCycleStartUsed = -1
        billing.resetLocalCounter()
        if (::processingTimerPanel.isInitialized) processingTimerPanel.resetSession()
        com.github.catatafishen.ideagentforcopilot.psi.PsiBridgeService.getInstance(project).clearSessionAllowedTools()
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

    /** Restart the agent session but keep the conversation history visible in the chat panel. */
    fun resetSessionKeepingHistory() {
        currentSessionId = null
        conversationSummaryInjected = false  // allow summary re-injection on next prompt
        billing.billingCycleStartUsed = -1
        billing.resetLocalCounter()
        if (::processingTimerPanel.isInitialized) processingTimerPanel.resetSession()
        com.github.catatafishen.ideagentforcopilot.psi.PsiBridgeService.getInstance(project).clearSessionAllowedTools()
        updateSessionInfo()
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
        ApplicationManager.getApplication().invokeLater {
            modelsStatusText = MSG_LOADING
            selectedModelIndex = -1
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val models = fetchModelsWithRetry()
                ApplicationManager.getApplication().invokeLater { onModelsLoaded(models, onSuccess) }
            } catch (e: Exception) {
                val errorMsg = e.message ?: MSG_UNKNOWN_ERROR
                LOG.warn("Failed to load models: $errorMsg")
                ApplicationManager.getApplication().invokeLater { onModelsLoadFailed(e) }
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
            // Non-recoverable: return to connect panel with the error
            agentManager.isAcpConnected = false
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
