package com.github.catatafishen.ideagentforcopilot.ui

import com.github.catatafishen.ideagentforcopilot.bridge.CopilotAcpClient
import com.github.catatafishen.ideagentforcopilot.services.CopilotService
import com.github.catatafishen.ideagentforcopilot.services.CopilotSettings
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
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.datatransfer.StringSelection
import javax.swing.*

/**
 * Main content for the IDE Agent for Copilot tool window.
 * Uses Kotlin UI DSL for cleaner, more maintainable UI code.
 */
class AgenticCopilotToolWindowContent(private val project: Project) {

    // UI String Constants
    private companion object {
        private val LOG =
            com.intellij.openapi.diagnostic.Logger.getInstance(AgenticCopilotToolWindowContent::class.java)
        const val MSG_LOADING = "Loading..."
        const val MSG_THINKING = "Thinking..."
        const val MSG_UNKNOWN_ERROR = "Unknown error"
        const val PROMPT_PLACEHOLDER = "Ask Copilot... (Shift+Enter for new line)"
        const val AGENT_WORK_DIR = ".agent-work"

        /** Theme-aware error color — uses IDE's error foreground or a sensible red fallback. */
        private const val OS_NAME_PROPERTY = "os.name"
        private val ERROR_COLOR: JBColor
            get() = UIManager.getColor("Label.errorForeground") as? JBColor
                ?: JBColor(Color(0xC7, 0x22, 0x22), Color(0xE0, 0x60, 0x60))
    }

    private val mainPanel = JBPanel<JBPanel<*>>(BorderLayout())

    // Shared context list across tabs
    private val contextListModel = DefaultListModel<ContextItem>()

    // Shared model list (populated from ACP)
    private var loadedModels: List<CopilotAcpClient.Model> = emptyList()

    // Current conversation session — reused for multi-turn
    private var currentSessionId: String? = null

    // Prompt tab fields (promoted from local variables for footer layout)
    private var selectedModelIndex = -1
    private var modelsStatusText: String? = MSG_LOADING
    private lateinit var controlsToolbar: ActionToolbar
    private lateinit var promptTextArea: EditorTextField
    private lateinit var loadingSpinner: AsyncProcessIcon
    private var currentPromptThread: Thread? = null
    private var isSending = false
    private lateinit var processingTimerPanel: ProcessingTimerPanel
    private lateinit var attachmentsPanel: JBPanel<JBPanel<*>>

    // Timeline events (populated from ACP session/update notifications)
    private val timelineModel = DefaultListModel<TimelineEvent>()
    private val debugPanel = DebugPanel(project, timelineModel)

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
    private var inlineAuthProcess: Process? = null

    // Per-turn tracking
    private var turnToolCallCount = 0
    private var turnModelId = ""

    private var conversationSummaryInjected = false

    init {
        setupUI()
        restoreConversation()
    }

    private fun setupUI() {
        mainPanel.add(createPromptTab(), BorderLayout.CENTER)
    }

    /** Record an event in the Timeline tab. Thread-safe. */
    private fun addTimelineEvent(type: EventType, message: String) {
        SwingUtilities.invokeLater {
            timelineModel.addElement(TimelineEvent(type, message, java.util.Date()))
        }
    }

    private fun updateSessionInfo() {
        SwingUtilities.invokeLater {
            if (!::sessionInfoLabel.isInitialized) return@invokeLater
            val sid = currentSessionId
            if (sid != null) {
                val shortId = sid.take(8) + "..."
                val cwd = project.basePath ?: "unknown"
                sessionInfoLabel.text = "Session: $shortId  ·  $cwd"
                sessionInfoLabel.foreground = JBColor.foreground()
            } else {
                sessionInfoLabel.text = "No active session"
                sessionInfoLabel.foreground = JBColor.GRAY
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
        val status = update["status"]?.asString ?: "pending"
        val toolCallId = update["toolCallId"]?.asString ?: ""
        addTimelineEvent(EventType.TOOL_CALL, "$title ($status)")

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

        addTimelineEvent(EventType.TOOL_CALL, "Tool $toolCallId $status")

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
                    SwingUtilities.invokeLater {
                        if (!::planRoot.isInitialized) return@invokeLater
                        val fileNode = FileTreeNode(file.name, filePath, content)
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
        SwingUtilities.invokeLater {
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
            addTimelineEvent(EventType.TOOL_CALL, "Plan updated (${entries.size()} steps)")
        }
    }

    /** Creates a banner for Copilot CLI setup issues (not installed / not authenticated). */
    private fun createCopilotSetupBanner(onFixed: () -> Unit): AuthSetupBanner {
        val banner = AuthSetupBanner(
            retryTooltip = "Re-check Copilot CLI status",
            pollIntervalDown = 30,
            pollIntervalUp = 60,
            diagnosticsFn = { authService.copilotSetupDiagnostics() },
            onFixed = onFixed,
        ) { diag ->
            val isCLINotFound = "copilot cli not found" in diag.lowercase() ||
                ("not found" in diag.lowercase() && "copilot" in diag.lowercase())
            when {
                isCLINotFound -> {
                    val cmd = if (System.getProperty("os.name").lowercase().contains("win"))
                        "winget install GitHub.Copilot" else "npm install -g @github/copilot-cli"
                    textLabel.text = "<html><b>Copilot CLI is not installed</b> — install with: <tt>$cmd</tt></html>"
                    installButton.isVisible = true
                    actionButton.isVisible = false
                }

                authService.isAuthenticationError(diag) -> {
                    textLabel.text = "<html><b>Not signed in to Copilot</b> — click Sign In, then click Retry.</html>"
                    installButton.isVisible = false
                    actionButton.isVisible = true
                }

                else -> {
                    textLabel.text = "<html><b>Copilot CLI unavailable</b></html>"
                    installButton.isVisible = false
                    actionButton.isVisible = false
                }
            }
        }
        banner.installButton.addActionListener {
            com.intellij.ide.BrowserUtil.browse("https://github.com/github/copilot-cli#installation")
        }
        // Clear pending auth error on Retry so diagnostics re-verifies from scratch
        banner.retryButton.addActionListener { authService.clearPendingAuthError() }
        banner.actionButton.addActionListener {
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
            retryTooltip = "Re-check GitHub CLI status",
            pollIntervalDown = 30,
            pollIntervalUp = 120,
            diagnosticsFn = { authService.ghSetupDiagnostics(billing) },
            onFixed = onFixed,
        ) { diag ->
            when {
                "not installed" in diag.lowercase() -> {
                    textLabel.text =
                        "<html><b>GitHub CLI (gh) is not installed</b> — needed for billing info. Install from <tt>cli.github.com</tt>.</html>"
                    installButton.isVisible = true
                    actionButton.isVisible = false
                }

                else -> {
                    textLabel.text =
                        "<html><b>Not signed in to GitHub CLI (gh)</b> — needed for billing info. Click Sign In.</html>"
                    installButton.isVisible = false
                    actionButton.isVisible = true
                }
            }
        }
        banner.installButton.addActionListener {
            com.intellij.ide.BrowserUtil.browse("https://cli.github.com")
        }
        banner.actionButton.addActionListener {
            banner.showSignInPending()
            authService.startGhLogin()
        }
        return banner
    }

    /** Creates a warning banner shown when the PSI bridge HTTP server is not reachable. Hidden by default. */
    private fun createPsiBridgeBanner(): JBPanel<JBPanel<*>> {
        val banner = JBPanel<JBPanel<*>>(BorderLayout())
        banner.isVisible = false
        banner.border = JBUI.Borders.compound(
            com.intellij.ui.SideBorder(
                JBColor(Color(0xE5, 0xA0, 0x00), Color(0x99, 0x75, 0x00)),
                com.intellij.ui.SideBorder.BOTTOM
            ),
            JBUI.Borders.empty(4, 8)
        )
        banner.background = JBColor(Color(0xFF, 0xF3, 0xCD), Color(0x3D, 0x36, 0x20))
        banner.isOpaque = true

        val icon = JBLabel(com.intellij.icons.AllIcons.General.Warning)
        icon.border = JBUI.Borders.emptyRight(6)

        val text = JBLabel(
            "<html><b>IntelliJ code tools unavailable</b> — PSI bridge is not running. " +
                "Make sure a project is open and the IDE Agent for Copilot plugin is active, then restart IntelliJ.</html>"
        )
        text.foreground = JBColor(Color(0x5C, 0x45, 0x00), Color(0xE0, 0xC0, 0x60))

        val detailsButton = JButton("Details…")
        detailsButton.border = JBUI.Borders.emptyLeft(8)
        detailsButton.isOpaque = false
        detailsButton.isBorderPainted = false
        detailsButton.foreground = JBColor(Color(0x5C, 0x45, 0x00), Color(0xE0, 0xC0, 0x60))
        detailsButton.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        detailsButton.toolTipText = "Show diagnostic information"

        val recheckButton = JButton("Recheck")
        recheckButton.border = JBUI.Borders.emptyLeft(4)
        recheckButton.isOpaque = false
        recheckButton.isBorderPainted = false
        recheckButton.foreground = JBColor(Color(0x5C, 0x45, 0x00), Color(0xE0, 0xC0, 0x60))
        recheckButton.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        recheckButton.toolTipText = "Re-run the PSI bridge check"

        val buttons = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 0, 0))
        buttons.isOpaque = false
        buttons.add(detailsButton)
        buttons.add(recheckButton)

        val content = JBPanel<JBPanel<*>>(BorderLayout())
        content.isOpaque = false
        content.add(icon, BorderLayout.WEST)
        content.add(text, BorderLayout.CENTER)
        content.add(buttons, BorderLayout.EAST)
        banner.add(content, BorderLayout.CENTER)

        // Runs check on a pooled thread, then updates the banner on the EDT.
        // lastDiag is stored so Details… always shows the freshest result.
        var lastDiag: String? = null

        // Scheduler used for adaptive polling: 5 s while bridge is down, 30 s while healthy.
        // scheduledFuture tracks the next pending check so Recheck can cancel it (avoids duplicate chains).
        val scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "psi-bridge-poll").also { it.isDaemon = true }
        }
        var scheduledFuture: java.util.concurrent.ScheduledFuture<*>? = null

        fun scheduleNext(bridgeWasDown: Boolean) {
            val delay = if (bridgeWasDown) 5L else 30L
            scheduledFuture = scheduler.schedule(
                {
                    val diag = psiBridgeDiagnostics()
                    SwingUtilities.invokeLater {
                        lastDiag = diag
                        banner.isVisible = diag != null
                        recheckButton.isEnabled = true
                    }
                    scheduleNext(diag != null)
                },
                delay, java.util.concurrent.TimeUnit.SECONDS
            )
        }

        fun runCheck() {
            // Cancel any pending poll so we don't end up with two parallel chains.
            scheduledFuture?.cancel(false)
            recheckButton.isEnabled = false
            ApplicationManager.getApplication().executeOnPooledThread {
                val diag = psiBridgeDiagnostics()
                SwingUtilities.invokeLater {
                    lastDiag = diag
                    banner.isVisible = diag != null
                    recheckButton.isEnabled = true
                }
                scheduleNext(diag != null)
            }
        }

        detailsButton.addActionListener {
            // Always run a fresh check so the dialog never shows stale data.
            detailsButton.isEnabled = false
            ApplicationManager.getApplication().executeOnPooledThread {
                val diag = psiBridgeDiagnostics()
                SwingUtilities.invokeLater {
                    detailsButton.isEnabled = true
                    lastDiag = diag
                    banner.isVisible = diag != null
                    com.intellij.openapi.ui.Messages.showMessageDialog(
                        project,
                        diag ?: "PSI bridge is healthy.",
                        "PSI Bridge Diagnostics",
                        if (diag != null) com.intellij.openapi.ui.Messages.getWarningIcon()
                        else com.intellij.openapi.ui.Messages.getInformationIcon()
                    )
                }
            }
        }
        recheckButton.addActionListener { runCheck() }

        // First check after 5 s so the bridge has time to start.
        scheduledFuture = scheduler.schedule(
            {
                val diag = psiBridgeDiagnostics()
                SwingUtilities.invokeLater {
                    lastDiag = diag
                    banner.isVisible = diag != null
                }
                scheduleNext(diag != null)
            },
            5, java.util.concurrent.TimeUnit.SECONDS
        )

        banner.addAncestorListener(object : javax.swing.event.AncestorListener {
            override fun ancestorAdded(e: javax.swing.event.AncestorEvent?) = Unit
            override fun ancestorMoved(e: javax.swing.event.AncestorEvent?) = Unit
            override fun ancestorRemoved(e: javax.swing.event.AncestorEvent?) {
                scheduler.shutdownNow()
            }
        })

        return banner
    }

    /**
     * Checks PSI bridge availability. Returns a multi-line diagnostic string on failure, or null if healthy.
     * Safe to call on a background thread.
     */
    private fun psiBridgeDiagnostics(): String? {
        val bridgeFile = java.nio.file.Path.of(System.getProperty("user.home"), ".copilot", "psi-bridge.json")
        if (!java.nio.file.Files.exists(bridgeFile)) {
            return "PSI bridge file not found.\n\n" +
                "Expected: ${bridgeFile.toAbsolutePath()}\n\n" +
                "This file is written by the plugin when a project opens.\nPossible causes:\n" +
                "  • No project is open (open a project, not the Welcome Screen)\n" +
                "  • The plugin failed to initialize — check Help → Show Log for errors\n" +
                "  • Write permission denied on ~/.copilot/"
        }
        return try {
            val content = java.nio.file.Files.readString(bridgeFile)
            val json = com.google.gson.JsonParser.parseString(content).asJsonObject

            val port: Int
            val bridgeProject: String

            if (json.has("port")) {
                // Legacy single-entry format
                port = json.get("port")?.asInt
                    ?: return "PSI bridge file found but has no 'port' field.\n\nFile content:\n$content"
                bridgeProject = json.get("projectPath")?.asString ?: "(unknown)"
            } else {
                // New multi-project registry — find this project's entry
                val ourPath = project.basePath?.replace('\\', '/') ?: ""
                val entry = json.entrySet().firstOrNull { (key, _) ->
                    val k = key.replace('\\', '/')
                    ourPath == k || ourPath.startsWith("$k/") || k.startsWith("$ourPath/")
                }
                if (entry == null) {
                    val others = json.keySet().joinToString("\n") { "  • $it" }.ifEmpty { "  (none)" }
                    return "No PSI bridge entry found for this project.\n\n" +
                        "Looking for: ${project.basePath}\n\n" +
                        "Projects with active bridges:\n$others\n\n" +
                        "Try closing and reopening this project."
                }
                port = entry.value.asJsonObject.get("port")?.asInt
                    ?: return "Bridge entry for this project has no 'port' field."
                bridgeProject = entry.key
            }

            val url = java.net.URI.create("http://127.0.0.1:$port/health").toURL()
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            conn.requestMethod = "GET"
            val code = try {
                conn.responseCode
            } catch (e: Exception) {
                return "PSI bridge file found (port $port, project: $bridgeProject)\n" +
                    "but the HTTP server is not responding.\n\n" +
                    "Connection error: ${e.javaClass.simpleName}: ${e.message}\n\n" +
                    "The bridge may have crashed. Check Help → Show Log,\nthen close and reopen the project."
            }
            if (code == 200) null else "PSI bridge returned HTTP $code from /health (port $port)."
        } catch (e: Exception) {
            "Failed to read PSI bridge file:\n${e.javaClass.simpleName}: ${e.message}\n\nFile: $bridgeFile"
        }
    }

    private fun createPromptTab(): JComponent {
        val panel = JBPanel<JBPanel<*>>(BorderLayout())

        // PSI bridge status banner (shown when bridge is not reachable)
        val psiBridgeBanner = createPsiBridgeBanner()

        // Response/chat history area (top of splitter)
        val responsePanel = createResponsePanel()
        responsePanelContainer = JBPanel<JBPanel<*>>(BorderLayout())
        responsePanelContainer.add(responsePanel, BorderLayout.CENTER)
        val topPanel = JBPanel<JBPanel<*>>(BorderLayout())
        val northStack = JBPanel<JBPanel<*>>()
        northStack.layout = BoxLayout(northStack, BoxLayout.Y_AXIS)
        northStack.add(psiBridgeBanner)

        // Load models
        fun loadModels() {
            loadModelsAsync { models -> loadedModels = models }
        }

        // Setup banners: Copilot CLI / auth, GH CLI / auth
        copilotBanner = createCopilotSetupBanner {
            authService.pendingAuthError = null
            // Invalidate the UI session so the next prompt creates a fresh one
            // with the new auth credentials (avoids "Session not found" errors).
            currentSessionId = null
            loadModels()
        }
        northStack.add(copilotBanner)
        northStack.add(createGhSetupBanner { billing.loadBillingData() })
        northStack.add(GitWarningBanner(project))
        topPanel.add(northStack, BorderLayout.NORTH)
        topPanel.add(responsePanelContainer, BorderLayout.CENTER)

        // Input row (bottom of splitter — resizable)
        val inputRow = createInputRow()

        // Splitter between output and input only
        val splitter = OnePixelSplitter(true, 0.75f)
        splitter.firstComponent = topPanel
        splitter.secondComponent = inputRow
        splitter.setHonorComponentsMinimumSize(true)
        panel.add(splitter, BorderLayout.CENTER)

        // Fixed footer: controls + usage (not resized by splitter)
        loadingSpinner = AsyncProcessIcon("loading-models")
        loadingSpinner.preferredSize = JBUI.size(16, 16)
        val fixedFooter = createFixedFooter()
        panel.add(fixedFooter, BorderLayout.SOUTH)

        billing.loadBillingData()
        loadModels()

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
        row.minimumSize = JBUI.size(100, 40)

        // Attachments chip panel (shown above input when files attached)
        attachmentsPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 2))
        attachmentsPanel.isOpaque = false
        attachmentsPanel.isVisible = false
        attachmentsPanel.border = JBUI.Borders.emptyBottom(2)

        // Use EditorTextFieldProvider for PsiFile-backed document (enables spell checking)
        val editorCustomizations = mutableListOf<com.intellij.ui.EditorCustomization>()
        try {
            val spellCheck = com.intellij.openapi.editor.SpellCheckingEditorCustomizationProvider
                .getInstance().getEnabledCustomization()
            if (spellCheck != null) editorCustomizations.add(spellCheck)
        } catch (_: Exception) {
            // Spellchecker plugin not available
        }
        promptTextArea = com.intellij.ui.EditorTextFieldProvider.getInstance()
            .getEditorField(com.intellij.openapi.fileTypes.PlainTextLanguage.INSTANCE, project, editorCustomizations)
        promptTextArea.setOneLineMode(false)
        promptTextArea.border = null

        // Drag-drop works on the EditorTextField wrapper (no editor needed)
        setupPromptDragDrop(promptTextArea)
        // Key bindings and context menu need the editor's content component.
        // addSettingsProvider runs when the editor is actually created,
        // unlike invokeLater which may fire before the editor exists.
        promptTextArea.addSettingsProvider { editor ->
            setupPromptKeyBindings(promptTextArea, editor)
            setupPromptContextMenu(editor)
            // Use EditorEx built-in placeholder (visual-only, doesn't set actual text)
            editor.setPlaceholder(PROMPT_PLACEHOLDER)
            editor.setShowPlaceholderWhenFocused(true)
            editor.settings.isUseSoftWraps = true
            editor.contentComponent.border = JBUI.Borders.empty(4, 6)
            editor.setBorder(null)
        }

        // Auto-revalidate on document changes
        promptTextArea.addDocumentListener(object : com.intellij.openapi.editor.event.DocumentListener {
            override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                SwingUtilities.invokeLater { promptTextArea.revalidate() }
            }
        })

        val inputWrapper = JBPanel<JBPanel<*>>(BorderLayout())
        inputWrapper.add(attachmentsPanel, BorderLayout.NORTH)
        val scrollPane = JBScrollPane(promptTextArea)
        scrollPane.horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        scrollPane.border = null
        scrollPane.viewportBorder = null
        inputWrapper.add(scrollPane, BorderLayout.CENTER)
        row.border = JBUI.Borders.empty()
        row.add(inputWrapper, BorderLayout.CENTER)

        // Refresh attachment chips when list changes
        contextListModel.addListDataListener(object : javax.swing.event.ListDataListener {
            override fun intervalAdded(e: javax.swing.event.ListDataEvent?) = refreshAttachmentChips()
            override fun intervalRemoved(e: javax.swing.event.ListDataEvent?) = refreshAttachmentChips()
            override fun contentsChanged(e: javax.swing.event.ListDataEvent?) = refreshAttachmentChips()
        })

        return row
    }

    private fun refreshAttachmentChips() {
        SwingUtilities.invokeLater {
            attachmentsPanel.removeAll()
            for (i in 0 until contextListModel.size()) {
                val item = contextListModel.getElementAt(i)
                val chip = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 2, 0))
                chip.isOpaque = true
                chip.background = UIManager.getColor("ActionButton.hoverBackground")
                    ?: JBColor(Color(0xDF, 0xE1, 0xE5), Color(0x35, 0x3B, 0x48))
                chip.border = JBUI.Borders.empty(1, 6, 1, 2)
                val icon = if (item.isSelection) "\u2702" else "\uD83D\uDCC4"
                val label = JBLabel("$icon ${item.name}")
                label.font = JBUI.Fonts.smallFont()
                chip.add(label)
                val removeBtn = JBLabel("\u2715")
                removeBtn.font = JBUI.Fonts.smallFont()
                removeBtn.foreground = JBColor.GRAY
                removeBtn.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                removeBtn.addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                        val idx = (0 until contextListModel.size()).firstOrNull { contextListModel[it] === item }
                        if (idx != null) contextListModel.remove(idx)
                    }
                })
                chip.add(removeBtn)
                attachmentsPanel.add(chip)
            }
            attachmentsPanel.isVisible = contextListModel.size() > 0
            attachmentsPanel.revalidate()
            attachmentsPanel.repaint()
        }
    }

    private fun onSendStopClicked() {
        if (isSending) {
            handleStopRequest(currentPromptThread)
            setSendingState(false)
        } else {
            val prompt = promptTextArea.text.trim()
            if (prompt.isEmpty()) return
            consolePanel.disableQuickReplies()
            setSendingState(true)
            setResponseStatus(MSG_THINKING)

            // Add session separator before new prompt if old content exists and no active session
            if (currentSessionId == null && consolePanel.hasContent()) {
                val ts = java.text.SimpleDateFormat("MMM d, yyyy h:mm a").format(java.util.Date())
                consolePanel.addSessionSeparator(ts)
            }

            val ctxFiles = if (contextListModel.size() > 0) {
                (0 until contextListModel.size()).map { i ->
                    val item = contextListModel.getElementAt(i)
                    Triple(item.name, item.path, if (item.isSelection) item.startLine else 0)
                }
            } else null
            consolePanel.addPromptEntry(prompt, ctxFiles)
            promptTextArea.text = ""

            ApplicationManager.getApplication().executeOnPooledThread {
                currentPromptThread = Thread.currentThread()
                executePrompt(prompt)
                currentPromptThread = null
            }
        }
    }

    private fun setSendingState(sending: Boolean) {
        isSending = sending
        SwingUtilities.invokeLater {
            controlsToolbar.updateActionsAsync()
            if (::processingTimerPanel.isInitialized) {
                if (sending) processingTimerPanel.start() else processingTimerPanel.stop()
            }
        }
    }

    private fun createControlsRow(): JBPanel<JBPanel<*>> {
        val row = JBPanel<JBPanel<*>>(BorderLayout())

        // Left toolbar — grouped logically:
        // [Send/Stop] | [Attach File, Attach Selection] | [Model, Mode] | [Follow Agent] | [Project Files ▾] | [Restart ▾] | [Export, Permissions, Help]
        val leftGroup = DefaultActionGroup()
        leftGroup.add(SendStopAction())
        leftGroup.addSeparator()
        leftGroup.add(AttachFileAction())
        leftGroup.add(AttachSelectionAction())
        leftGroup.addSeparator()
        leftGroup.add(ModelSelectorAction())
        leftGroup.add(ModeSelectorAction())
        leftGroup.addSeparator()
        leftGroup.add(FollowAgentFilesToggleAction())
        leftGroup.addSeparator()
        leftGroup.add(ProjectFilesDropdownAction())
        leftGroup.addSeparator()
        leftGroup.add(RestartSessionGroup())
        leftGroup.addSeparator()
        leftGroup.add(CopyConversationAction())
        leftGroup.add(SettingsAction())
        leftGroup.add(HelpAction(project))

        controlsToolbar = ActionManager.getInstance().createActionToolbar(
            "CopilotControls", leftGroup, true
        )
        controlsToolbar.targetComponent = row
        controlsToolbar.setReservePlaceAutoPopupIcon(false)

        // Right toolbar: processing indicator + usage graph (always right-aligned)
        val rightGroup = DefaultActionGroup()
        rightGroup.add(ProcessingIndicatorAction())
        rightGroup.add(billing.UsageGraphAction())

        val usageToolbar = ActionManager.getInstance().createActionToolbar(
            "CopilotUsage", rightGroup, true
        )
        usageToolbar.targetComponent = row
        usageToolbar.setReservePlaceAutoPopupIcon(false)

        row.add(controlsToolbar.component, BorderLayout.CENTER)
        row.add(usageToolbar.component, BorderLayout.EAST)

        return row
    }

    /** Toolbar action showing a native processing timer while the agent works */
    private inner class ProcessingIndicatorAction : AnAction("Processing"), CustomComponentAction {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) { /* No action needed — UI-only toolbar widget */
        }

        override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
            processingTimerPanel = ProcessingTimerPanel()
            return processingTimerPanel
        }
    }

    /**
     * Native Swing panel that shows a small animated spinner + elapsed-time counter
     * plus tool call count and requests used. Hidden when idle; visible once [start] is called.
     * On [stop], spinner changes to checkmark and stats remain visible until next [start].
     * Click to toggle between per-turn and session-wide stats.
     */
    private inner class ProcessingTimerPanel : JBPanel<ProcessingTimerPanel>() {
        private val spinner = AsyncProcessIcon("CopilotProcessing")
        private val doneIcon = JBLabel("\u2705")
        private val timerLabel = JBLabel("")
        private val toolsLabel = JBLabel("")
        private val requestsLabel = JBLabel("")
        private var startedAt = 0L
        private var toolCallCount = 0
        private val ticker = javax.swing.Timer(1000) { refreshDisplay() }

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
            timerLabel.foreground = JBColor.GRAY; timerLabel.font = smallGray; timerLabel.isVisible = false
            toolsLabel.foreground = JBColor.GRAY; toolsLabel.font = smallGray; toolsLabel.isVisible = false
            requestsLabel.foreground = JBColor.GRAY; requestsLabel.font = smallGray; requestsLabel.isVisible = false
            add(Box.createHorizontalGlue())
            add(spinner)
            add(Box.createHorizontalStrut(4))
            add(doneIcon)
            add(Box.createHorizontalStrut(4))
            add(timerLabel)
            add(Box.createHorizontalStrut(4))
            add(toolsLabel)
            add(Box.createHorizontalStrut(4))
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

        fun incrementToolCalls() {
            toolCallCount++
            refreshDisplay()
        }

        private fun refreshDisplay() {
            SwingUtilities.invokeLater {
                when (displayMode) {
                    modeTurn -> refreshTurnMode()
                    modeSession -> refreshSessionMode()
                }
                revalidate(); repaint()
            }
        }

        private fun refreshTurnMode() {
            toolTipText = "Turn stats · Click for session"
            updateLabel()
            toolsLabel.text = if (toolCallCount > 0) "\u2022 $toolCallCount tools" else ""
            toolsLabel.isVisible = toolCallCount > 0
            requestsLabel.isVisible = false
            if (!isRunning) doneIcon.text = "\u2705"
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
            doneIcon.text = "\u2211"
        }

        private fun updateLabel() {
            val elapsed = (System.currentTimeMillis() - startedAt) / 1000
            timerLabel.text = if (elapsed < 60) "${elapsed}s" else "${elapsed / 60}m ${elapsed % 60}s"
        }
    }

    // Send/Stop toggle action for the toolbar
    private inner class SendStopAction : AnAction(
        "Send", "Send prompt (Enter)", com.intellij.icons.AllIcons.Actions.Execute
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            onSendStopClicked()
        }

        override fun update(e: AnActionEvent) {
            val isLoggedIn = authService.pendingAuthError == null
            if (isSending) {
                e.presentation.icon = com.intellij.icons.AllIcons.Actions.Suspend
                e.presentation.text = "Stop"
                e.presentation.description = "Stop"
                e.presentation.isEnabled = true
            } else {
                e.presentation.icon = com.intellij.icons.AllIcons.Actions.Execute
                e.presentation.text = "Send"
                e.presentation.description = if (isLoggedIn) "Send prompt (Enter)" else "Sign in to Copilot first"
                e.presentation.isEnabled = isLoggedIn
            }
        }
    }

    // Attach current file to the next prompt
    private inner class AttachFileAction : AnAction(
        "Attach File", "Attach current file to prompt", com.intellij.icons.AllIcons.Actions.AddFile
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            handleAddCurrentFile()
        }
    }

    // Attach current selection to the next prompt
    private inner class AttachSelectionAction : AnAction(
        "Attach Selection", "Attach selected text to prompt", com.intellij.icons.AllIcons.Actions.AddMulticaret
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            handleAddSelection()
        }
    }

    // Export conversation to clipboard (popup with Text / HTML options)
    private inner class CopyConversationAction : AnAction(
        "Export Chat", "Export conversation to clipboard", com.intellij.icons.AllIcons.ToolbarDecorator.Export
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            val group = DefaultActionGroup()
            group.add(object : AnAction("Copy as Text") {
                override fun actionPerformed(e: AnActionEvent) {
                    val text = consolePanel.getConversationText()
                    if (text.isNotBlank()) {
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
                    }
                }

                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
            group.add(object : AnAction("Copy as HTML") {
                override fun actionPerformed(e: AnActionEvent) {
                    val html = consolePanel.getConversationHtml()
                    if (html.isNotBlank()) {
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(html), null)
                    }
                }

                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
            val popup = ActionManager.getInstance()
                .createActionPopupMenu(ActionPlaces.TOOLWINDOW_CONTENT, group)
            val comp = e.inputEvent?.component ?: return
            popup.component.show(comp, 0, comp.height)
        }
    }

    /** Dropdown toolbar button with two restart options: keep history or clear everything. */
    private inner class RestartSessionGroup : DefaultActionGroup(
        "Restart Session", true
    ) {
        init {
            templatePresentation.icon = AllIcons.Actions.Restart
            templatePresentation.text = "Restart Session"
            templatePresentation.description = "Restart the agent session"

            add(object : AnAction(
                "Restart (keep history)",
                "Start a new agent session while keeping the conversation visible",
                AllIcons.Actions.Restart
            ) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) = resetSessionKeepingHistory()
            })

            add(object : AnAction(
                "Clear and restart",
                "Clear the conversation and start a completely fresh session",
                AllIcons.Actions.GC
            ) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) = resetSession()
            })
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    /** Toolbar button that opens the tool permissions dialog. */
    private inner class SettingsAction : AnAction(
        "Tool Permissions", "View and configure tool permissions",
        AllIcons.General.Settings
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) = debugPanel.openSettings()
    }

    private inner class FollowAgentFilesToggleAction : ToggleAction(
        "Follow agent",
        "Auto-open files in the editor as the agent reads or writes them",
        AllIcons.Actions.Preview
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT

        override fun isSelected(e: AnActionEvent): Boolean =
            CopilotSettings.getFollowAgentFiles()

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            CopilotSettings.setFollowAgentFiles(state)
        }

    }

    // HelpAction extracted to HelpDialog.kt

    /** Open a project-root file in the editor if it exists */
    private fun openProjectFile(fileName: String) {
        val base = project.basePath ?: return
        val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .findFileByPath("$base/$fileName") ?: return
        com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(vf, true)
    }

    /** Dropdown action for project configuration files: Instructions, TODO, Agent Definitions, MCP Instructions */
    private inner class ProjectFilesDropdownAction : AnAction(
        "Project Files", "Open project configuration files",
        com.intellij.icons.AllIcons.Nodes.Folder
    ), com.intellij.openapi.actionSystem.ex.CustomComponentAction {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun actionPerformed(e: AnActionEvent) {
            val inputEvent = e.inputEvent ?: return
            val component = inputEvent.source as? java.awt.Component ?: return
            showPopup(component)
        }

        override fun createCustomComponent(
            presentation: com.intellij.openapi.actionSystem.Presentation,
            place: String
        ): javax.swing.JComponent {
            val button = com.intellij.openapi.actionSystem.impl.ActionButtonWithText(
                this,
                presentation,
                place,
                com.intellij.openapi.actionSystem.ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE
            )
            return button
        }

        private fun showPopup(owner: java.awt.Component) {
            val group = DefaultActionGroup()
            val base = project.basePath

            // Instructions (detect copilot-instructions.md at root or .github/)
            val copilotInstructionsFile = if (base != null) {
                val dotGithub = java.io.File(base, ".github/copilot-instructions.md")
                val root = java.io.File(base, "copilot-instructions.md")
                when {
                    dotGithub.exists() -> dotGithub
                    root.exists() -> root
                    else -> null
                }
            } else null
            group.add(object : AnAction(
                "Instructions",
                copilotInstructionsFile?.let { "Open ${it.relativeTo(java.io.File(base!!))}" }
                    ?: "No copilot-instructions.md found",
                com.intellij.icons.AllIcons.Actions.IntentionBulb
            ) {
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = copilotInstructionsFile != null
                }

                override fun actionPerformed(e: AnActionEvent) {
                    val relPath = copilotInstructionsFile?.relativeTo(java.io.File(base!!))?.path ?: return
                    openProjectFile(relPath)
                }
            })

            // TODO
            group.add(object : AnAction("TODO", "Open TODO.md", com.intellij.icons.AllIcons.General.TodoDefault) {
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = base != null && java.io.File(base, "TODO.md").exists()
                }

                override fun actionPerformed(e: AnActionEvent) = openProjectFile("TODO.md")
            })

            group.addSeparator("Agent Definitions")

            // Agent definition files from .github/agents/
            val agentsDir = if (base != null) java.io.File(base, ".github/agents") else null
            val agentFiles = agentsDir?.listFiles { f -> f.isFile && f.extension == "md" }
                ?.sortedBy { it.name } ?: emptyList()
            if (agentFiles.isNotEmpty()) {
                for (agentFile in agentFiles) {
                    val name = agentFile.nameWithoutExtension
                    group.add(object :
                        AnAction(name, "Open ${agentFile.name}", com.intellij.icons.AllIcons.General.User) {
                        override fun getActionUpdateThread() = ActionUpdateThread.BGT
                        override fun actionPerformed(e: AnActionEvent) =
                            openProjectFile(".github/agents/${agentFile.name}")
                    })
                }
            } else {
                group.add(object : AnAction("No agents defined", null, null) {
                    override fun getActionUpdateThread() = ActionUpdateThread.BGT
                    override fun update(e: AnActionEvent) {
                        e.presentation.isEnabled = false
                    }

                    override fun actionPerformed(e: AnActionEvent) { /* no-op: placeholder for disabled menu item */
                    }
                })
            }

            group.addSeparator("MCP Server")

            // Startup Instructions — disabled because Copilot ignores MCP initialize instructions.
            // See: https://github.com/github/copilot-cli/issues/1486
            // Plugin instructions are now prepended to copilot-instructions.md instead.
            group.add(object : AnAction(
                "Startup Instructions",
                "Disabled: Copilot ignores MCP instructions. Use copilot-instructions.md instead. " +
                    "See github.com/github/copilot-cli/issues/1486",
                com.intellij.icons.AllIcons.Actions.IntentionBulbGrey
            ) {
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = false
                }

                override fun actionPerformed(e: AnActionEvent) {}
            })

            // Restore default — also disabled for the same reason
            group.add(object : AnAction(
                "Restore Default Instructions",
                "Disabled: Copilot ignores MCP instructions. Use copilot-instructions.md instead. " +
                    "See github.com/github/copilot-cli/issues/1486",
                com.intellij.icons.AllIcons.Actions.Rollback
            ) {
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = false
                }

                override fun actionPerformed(e: AnActionEvent) {}
            })

            val popup = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
                .createActionGroupPopup(
                    null, group, com.intellij.openapi.actionSystem.impl.SimpleDataContext.getProjectContext(project),
                    com.intellij.openapi.ui.popup.JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                    true
                )
            popup.showUnderneathOf(owner)
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
                        CopilotSettings.setSelectedModel(model.id)
                        LOG.info("Model selected: ${model.id} (index=$index)")
                        ApplicationManager.getApplication().executeOnPooledThread {
                            try {
                                val client = CopilotService.getInstance(project).getClient()
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

    // ComboBoxAction for mode selection — matches Run panel dropdown style
    private class ModeSelectorAction : ComboBoxAction() {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun createPopupActionGroup(button: JComponent, context: DataContext): DefaultActionGroup {
            val group = DefaultActionGroup()
            group.add(object : AnAction("Agent") {
                override fun actionPerformed(e: AnActionEvent) {
                    CopilotSettings.setSessionMode("agent")
                }

                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
            group.add(object : AnAction("Plan") {
                override fun actionPerformed(e: AnActionEvent) {
                    CopilotSettings.setSessionMode("plan")
                }

                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
            return group
        }

        override fun update(e: AnActionEvent) {
            e.presentation.text = if (CopilotSettings.getSessionMode() == "plan") "Plan" else "Agent"
        }
    }

    private fun createResponsePanel(): JComponent {
        consolePanel = ChatConsolePanel(project)
        consolePanel.onQuickReply = { text -> SwingUtilities.invokeLater { sendQuickReply(text) } }
        // Register for proper JCEF browser disposal
        com.intellij.openapi.util.Disposer.register(project, consolePanel)
        // Placeholder only shown if no conversation is restored (set after restore check)
        return consolePanel.component
    }

    private fun appendResponse(text: String) {
        consolePanel.appendText(text)
    }

    @Suppress("unused")
    private fun setResponseStatus(text: String, loading: Boolean = true) {
        // Status indicator removed from UI \u2192 kept as no-op to avoid call-site churn
    }

    private fun setupPromptKeyBindings(promptTextArea: EditorTextField, editor: EditorEx) {
        val contentComponent = editor.contentComponent

        // Use IntelliJ's action system (not Swing InputMap) so the shortcut takes priority
        // over the editor's built-in Enter handler (ACTION_EDITOR_ENTER).
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
            add(object : AnAction("Attach Current File", null, com.intellij.icons.AllIcons.Actions.AddFile) {
                override fun actionPerformed(e: AnActionEvent) = handleAddCurrentFile()
            })
            add(object : AnAction("Attach Editor Selection", null, com.intellij.icons.AllIcons.Actions.AddMulticaret) {
                override fun actionPerformed(e: AnActionEvent) = handleAddSelection()
            })
            add(object : AnAction("Clear Attachments", null, com.intellij.icons.AllIcons.Actions.GC) {
                override fun actionPerformed(e: AnActionEvent) {
                    contextListModel.clear()
                }

                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = contextListModel.size() > 0
                }
            })

            addSeparator()

            // Conversation actions
            add(object : AnAction("New Conversation", null, com.intellij.icons.AllIcons.General.Add) {
                override fun actionPerformed(e: AnActionEvent) {
                    currentSessionId = null
                    consolePanel.addSessionSeparator(
                        java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
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
                    try {
                        dtde.acceptDrop(java.awt.dnd.DnDConstants.ACTION_COPY)
                        val transferable = dtde.transferable
                        if (transferable.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) {
                            @Suppress("UNCHECKED_CAST")
                            val files = transferable.getTransferData(
                                java.awt.datatransfer.DataFlavor.javaFileListFlavor
                            ) as List<java.io.File>
                            for (file in files) {
                                val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                                    .findFileByIoFile(file) ?: continue
                                val doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                                    .getDocument(vf) ?: continue
                                val exists = (0 until contextListModel.size()).any {
                                    contextListModel[it].path == vf.path
                                }
                                if (!exists) {
                                    contextListModel.addElement(
                                        ContextItem(
                                            path = vf.path, name = vf.name,
                                            startLine = 1, endLine = doc.lineCount,
                                            fileType = vf.fileType, isSelection = false
                                        )
                                    )
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
            })
    }

    private fun handleStopRequest(promptThread: Thread?) {
        val sessionId = currentSessionId
        if (sessionId != null) {
            try {
                CopilotService.getInstance(project).getClient().cancelSession(sessionId)
            } catch (_: Exception) {
                // Best-effort cancellation
            }
        }
        promptThread?.interrupt()
        consolePanel.addErrorEntry("Stopped by user")
        setResponseStatus("Stopped", loading = false)
        addTimelineEvent(EventType.ERROR, "Prompt cancelled by user")
    }

    private fun ensureSessionCreated(client: CopilotAcpClient): String {
        if (currentSessionId == null) {
            currentSessionId = client.createSession(project.basePath)
            addTimelineEvent(EventType.SESSION_START, "Session created")
            updateSessionInfo()
            val savedModel = CopilotSettings.getSelectedModel()
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
        val snippetSuffix = buildSnippetSuffix()
        var effective = if (snippetSuffix.isNotEmpty()) "$prompt\n\n$snippetSuffix" else prompt

        if (CopilotSettings.getSessionMode() == "plan") {
            effective = "[[PLAN]] $effective"
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

    private fun handlePromptCompletion(prompt: String) {
        com.github.catatafishen.ideagentforcopilot.psi.PsiBridgeService.getInstance(project).flushPendingAutoFormat()
        consolePanel.finishResponse(turnToolCallCount, turnModelId, getModelMultiplier(turnModelId))
        notifyIfUnfocused(turnToolCallCount)
        setResponseStatus("Done", loading = false)
        addTimelineEvent(EventType.RESPONSE_RECEIVED, "Response received")
        saveTurnStatistics(prompt, turnToolCallCount, turnModelId)
        saveConversation()
        billing.recordTurnCompleted(getModelMultiplier(turnModelId))

        val lastResponse = consolePanel.getLastResponseText()
        val quickReplies = detectQuickReplies(lastResponse)
        if (quickReplies.isNotEmpty()) {
            SwingUtilities.invokeLater { consolePanel.showQuickReplies(quickReplies) }
        }

        // Force Swing repaint so the JCEF panel reflects final state
        SwingUtilities.invokeLater {
            consolePanel.component.revalidate()
            consolePanel.component.repaint()
        }
    }

    private fun executePrompt(prompt: String) {
        try {
            // Block prompts while signed out — banner must be visible for sign-in
            if (authService.pendingAuthError != null) {
                SwingUtilities.invokeLater {
                    consolePanel.addErrorEntry("Not signed in to Copilot. Use the Sign In button in the banner above.")
                    copilotBanner?.triggerCheck()
                }
                return
            }

            val service = CopilotService.getInstance(project)
            val client = service.getClient()
            val sessionId = ensureSessionCreated(client)

            // Wire permission request listener so ASK-mode tools show a bubble in chat
            client.setPermissionRequestListener { req ->
                ApplicationManager.getApplication().invokeLater {
                    consolePanel.showPermissionRequest(
                        req.reqId.toString(), req.displayName, req.description
                    ) { allowed -> req.respond(allowed) }
                    notifyPermissionRequestIfUnfocused(req.displayName)
                }
            }

            addTimelineEvent(
                EventType.MESSAGE_SENT,
                "Prompt: ${prompt.take(80)}${if (prompt.length > 80) "..." else ""}"
            )

            val selectedModelObj =
                if (selectedModelIndex >= 0 && selectedModelIndex < loadedModels.size) loadedModels[selectedModelIndex] else null
            val modelId = selectedModelObj?.id ?: ""
            turnToolCallCount = 0
            activeSubAgentId = null
            turnModelId = modelId

            SwingUtilities.invokeLater {
                consolePanel.setCurrentModel(modelId)
                consolePanel.setPromptStats(modelId, getModelMultiplier(modelId))
            }

            val references = buildContextReferences()
            val effectivePrompt = buildEffectivePrompt(prompt)

            if (references.isNotEmpty()) {
                val contextFiles = (0 until contextListModel.size()).map { i ->
                    val item = contextListModel.getElementAt(i)
                    Pair(item.name, item.path)
                }
                consolePanel.addContextFilesEntry(contextFiles)
            }
            SwingUtilities.invokeLater { contextListModel.clear() }

            var receivedContent = false
            client.sendPrompt(
                sessionId, effectivePrompt, modelId,
                references.ifEmpty { null },
                { chunk ->
                    if (!receivedContent) {
                        receivedContent = true
                        setResponseStatus("Responding...")
                    }
                    appendResponse(chunk)
                },
                { update -> handlePromptStreamingUpdate(update, receivedContent) },
                null // premium requests tracked via billing API, not per-turn
            )

            handlePromptCompletion(prompt)
        } catch (e: Exception) {
            handlePromptError(e)
        } finally {
            setSendingState(false)
        }
    }

    private fun buildContextReferences(): List<CopilotAcpClient.ResourceReference> {
        val references = mutableListOf<CopilotAcpClient.ResourceReference>()
        for (i in 0 until contextListModel.size()) {
            val item = contextListModel.getElementAt(i)
            try {
                val ref = buildSingleReference(item)
                if (ref != null) references.add(ref)
            } catch (_: Exception) {
                appendResponse("\u26a0 Could not read context: ${item.name}\n")
            }
        }
        return references
    }

    /** Send a quick-reply as if the user typed it. Called from the JS bridge on EDT. */
    private fun sendQuickReply(text: String) {
        if (isSending) return
        consolePanel.disableQuickReplies()
        promptTextArea.text = text
        onSendStopClicked()
    }

    /**
     * Extract quick-reply options from `[quick-reply: A | B | C]` tags in the response.
     */
    private fun detectQuickReplies(responseText: String): List<String> {
        val match = QUICK_REPLY_TAG_REGEX.findAll(responseText).lastOrNull() ?: return emptyList()
        return match.groupValues[1].split("|").map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Build inline snippet text for selections so the agent sees the code in the prompt itself */
    private fun buildSnippetSuffix(): String {
        val parts = mutableListOf<String>()
        for (i in 0 until contextListModel.size()) {
            val item = contextListModel.getElementAt(i)
            if (!item.isSelection || item.startLine <= 0) continue
            try {
                val file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(item.path)
                    ?: continue
                var doc: com.intellij.openapi.editor.Document? = null
                com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction {
                    doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file)
                }
                val document = doc ?: continue
                var snippet = ""
                com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction {
                    val s = document.getLineStartOffset((item.startLine - 1).coerceIn(0, document.lineCount - 1))
                    val e = document.getLineEndOffset((item.endLine - 1).coerceIn(0, document.lineCount - 1))
                    snippet = document.getText(com.intellij.openapi.util.TextRange(s, e))
                }
                val fileName = item.path.substringAfterLast("/")
                val ext = fileName.substringAfterLast(".", "")
                parts.add("Selected lines ${item.startLine}-${item.endLine} of `$fileName`:\n```$ext\n$snippet\n```")
            } catch (_: Exception) { /* skip */
            }
        }
        return parts.joinToString("\n\n")
    }

    private fun buildSingleReference(item: ContextItem): CopilotAcpClient.ResourceReference? {
        val file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(item.path)
            ?: return null
        var doc: com.intellij.openapi.editor.Document? = null
        com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction {
            doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file)
        }
        val document = doc ?: return null

        var text = ""
        com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction {
            if (item.isSelection && item.startLine > 0) {
                val startOffset = document.getLineStartOffset((item.startLine - 1).coerceIn(0, document.lineCount - 1))
                val endOffset = document.getLineEndOffset((item.endLine - 1).coerceIn(0, document.lineCount - 1))
                val snippet = document.getText(com.intellij.openapi.util.TextRange(startOffset, endOffset))
                val fileName = item.path.substringAfterLast("/")
                text = "// Selected lines ${item.startLine}-${item.endLine} of $fileName\n$snippet"
            } else {
                text = document.text
            }
        }

        val uri = buildString {
            append("file://")
            append(item.path.replace("\\", "/"))
            if (item.isSelection && item.startLine > 0) {
                append("#L${item.startLine}-L${item.endLine}")
            }
        }
        val mimeType = getMimeTypeForFileType(file.fileType.name.lowercase())
        val contextLog = com.intellij.openapi.diagnostic.Logger.getInstance("ContextSnippet")
        contextLog.info(
            "Context ref: uri=$uri, isSelection=${item.isSelection}, lines=${item.startLine}-${item.endLine}, textLength=${text.length}, textPreview=${
                text.take(
                    100
                )
            }"
        )
        return CopilotAcpClient.ResourceReference(uri, mimeType, text)
    }

    private fun getMimeTypeForFileType(fileTypeName: String): String {
        return when (fileTypeName) {
            "java" -> "text/x-java"
            "kotlin" -> "text/x-kotlin"
            "python" -> "text/x-python"
            "javascript" -> "text/javascript"
            "typescript" -> "text/typescript"
            "xml", "html" -> "text/$fileTypeName"
            else -> "text/plain"
        }
    }

    private fun handlePromptStreamingUpdate(update: com.google.gson.JsonObject, receivedContent: Boolean) {
        val updateType = update["sessionUpdate"]?.asString ?: ""
        when (updateType) {
            "tool_call" -> handleStreamingToolCall(update)
            "tool_call_update" -> handleStreamingToolCallUpdate(update)
            "agent_thought_chunk" -> handleStreamingAgentThought(update, receivedContent)
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
                CopilotService.getInstance(project).getClient().setSubAgentActive(true)
                CopilotSettings.setActiveAgentLabel(agentType)
                setResponseStatus("Running: $title")
                val description = title.ifBlank { extractJsonField(arguments, "description") ?: "Sub-agent task" }
                val prompt = extractJsonField(arguments, "prompt")
                consolePanel.addSubAgentEntry(toolCallId, agentType, description, prompt)
            } else if (activeSubAgentId != null) {
                // Internal tool call from a running sub-agent — show on sub-agent's message
                turnToolCallCount++
                if (::processingTimerPanel.isInitialized) processingTimerPanel.incrementToolCalls()
                toolCallTitles[toolCallId] = "subagent_internal"
                consolePanel.addSubAgentToolCall(activeSubAgentId!!, toolCallId, title, arguments)
            } else {
                turnToolCallCount++
                if (::processingTimerPanel.isInitialized) processingTimerPanel.incrementToolCalls()
                toolCallTitles[toolCallId] = title
                setResponseStatus("Running: $title")
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
            setResponseStatus(MSG_THINKING)
            if (isSubAgent) {
                activeSubAgentId = null
                CopilotService.getInstance(project).getClient().setSubAgentActive(false)
                CopilotSettings.setActiveAgentLabel(null)
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
                CopilotService.getInstance(project).getClient().setSubAgentActive(false)
                CopilotSettings.setActiveAgentLabel(null)
                consolePanel.updateSubAgentResult(toolCallId, "failed", error)
            } else if (isInternal) {
                consolePanel.updateSubAgentToolCall(toolCallId, "failed", error)
            } else {
                consolePanel.updateToolCall(toolCallId, "failed", error)
            }
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

    private fun handleStreamingAgentThought(update: com.google.gson.JsonObject, receivedContent: Boolean) {
        val content = update["content"]?.asJsonObject
        val text = content?.get("text")?.asString
        if (text != null) {
            consolePanel.appendThinkingText(text)
        }
        if (!receivedContent) {
            setResponseStatus(MSG_THINKING)
        }
    }

    private fun handlePromptError(e: Exception) {
        val msg = if (e is InterruptedException || e.cause is InterruptedException) {
            "Request cancelled"
        } else {
            e.message ?: MSG_UNKNOWN_ERROR
        }
        consolePanel.addErrorEntry("Error: $msg")
        setResponseStatus("Error", loading = false)
        addTimelineEvent(EventType.ERROR, "Error: ${msg.take(80)}")

        // Show the auth banner immediately when an auth error is detected
        if (authService.isAuthenticationError(msg)) {
            authService.markAuthError(msg)
            copilotBanner?.triggerCheck()
        }

        val isRecoverable = e is InterruptedException || e.cause is InterruptedException ||
            (e is com.github.catatafishen.ideagentforcopilot.bridge.CopilotException && e.isRecoverable)
        if (!isRecoverable) {
            currentSessionId = null
            updateSessionInfo()
        }
        e.printStackTrace()
    }

    private fun getModelMultiplier(modelId: String): String {
        return try {
            CopilotService.getInstance(project).getClient().getModelMultiplier(modelId)
        } catch (_: Exception) {
            "1x"
        }
    }

    private fun saveTurnStatistics(prompt: String, toolCalls: Int, modelId: String) {
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
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
        val title = "Copilot response ready"
        val content =
            if (toolCallCount > 0) "Turn completed with $toolCallCount tool call${if (toolCallCount != 1) "s" else ""}"
            else "Turn completed"
        // Balloon attached to the Copilot tool window tab (same style as build/test notifications)
        com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            .notifyByBalloon(
                "IDE Agent for Copilot",
                com.intellij.openapi.ui.MessageType.INFO,
                "<b>$title</b><br>$content"
            )
        // OS-native notification with sound
        com.intellij.ui.SystemNotifications.getInstance().notify("Copilot Notifications", title, content)
        // Flash the taskbar icon
        com.intellij.ui.AppIcon.getInstance().requestAttention(project, false)
    }

    private fun notifyPermissionRequestIfUnfocused(toolName: String) {
        val frame = com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project) ?: return
        if (frame.isActive) return
        val title = "Copilot needs approval"
        val content = "Permission requested for: $toolName"
        com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            .notifyByBalloon(
                "IDE Agent for Copilot",
                com.intellij.openapi.ui.MessageType.WARNING,
                "<b>$title</b><br>$content"
            )
        com.intellij.ui.SystemNotifications.getInstance().notify("Copilot Notifications", title, content)
        com.intellij.ui.AppIcon.getInstance().requestAttention(project, true)
    }

    private fun saveConversation() {
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            try {
                conversationFile().writeText(consolePanel.serializeEntries())
            } catch (_: Exception) { /* best-effort */
            }
        }
    }

    private fun restoreConversation() {
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val file = conversationFile()
                if (!file.exists() || file.length() < 10) {
                    SwingUtilities.invokeLater {
                        consolePanel.showPlaceholder("Start a conversation with Copilot...")
                    }
                    return@executeOnPooledThread
                }
                val json = file.readText()
                SwingUtilities.invokeLater {
                    consolePanel.restoreEntries(json)
                }
            } catch (_: Exception) {
                SwingUtilities.invokeLater {
                    consolePanel.showPlaceholder("Start a conversation with Copilot...")
                }
            }
        }
    }

    private fun handleAddCurrentFile() {
        val fileEditorManager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
        val currentFile = fileEditorManager.selectedFiles.firstOrNull()

        if (currentFile == null) {
            Messages.showWarningDialog(project, "No file is currently open in the editor", "No File")
            return
        }

        val path = currentFile.path
        val lineCount = try {
            fileEditorManager.selectedTextEditor?.document?.lineCount ?: 0
        } catch (_: Exception) {
            0
        }

        val exists = (0 until contextListModel.size()).any { contextListModel[it].path == path }
        if (exists) {
            Messages.showInfoMessage(project, "File already in context: ${currentFile.name}", "Duplicate File")
            return
        }

        contextListModel.addElement(
            ContextItem(
                path = path, name = currentFile.name, startLine = 1, endLine = lineCount,
                fileType = currentFile.fileType, isSelection = false
            )
        )
    }

    private fun handleAddSelection() {
        val fileEditorManager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
        val editor = fileEditorManager.selectedTextEditor
        val currentFile = fileEditorManager.selectedFiles.firstOrNull()

        if (editor == null || currentFile == null) {
            Messages.showWarningDialog(project, "No editor is currently open", "No Editor")
            return
        }

        val selectionModel = editor.selectionModel
        if (!selectionModel.hasSelection()) {
            Messages.showWarningDialog(project, "No text is selected. Select some code first.", "No Selection")
            return
        }

        val document = editor.document
        val startLine = document.getLineNumber(selectionModel.selectionStart) + 1
        val endLine = document.getLineNumber(selectionModel.selectionEnd) + 1

        contextListModel.addElement(
            ContextItem(
                path = currentFile.path, name = "${currentFile.name}:$startLine-$endLine",
                startLine = startLine, endLine = endLine,
                fileType = currentFile.fileType, isSelection = true
            )
        )
    }

    // Debug/Timeline/Settings tabs extracted to DebugPanel.kt
    fun getComponent(): JComponent = mainPanel
    fun openSettings() = debugPanel.openSettings()
    fun openDebug() = debugPanel.openDebug()
    fun openSessionFiles() = debugPanel.openSessionFiles()

    fun resetSession() {
        currentSessionId = null
        conversationSummaryInjected = false
        billing.billingCycleStartUsed = -1
        billing.resetLocalCounter()
        if (::processingTimerPanel.isInitialized) processingTimerPanel.resetSession()
        consolePanel.clear()
        consolePanel.showPlaceholder("New conversation started.")
        addTimelineEvent(EventType.SESSION_START, "New conversation started")
        updateSessionInfo()
        archiveConversation()
        SwingUtilities.invokeLater {
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
        addTimelineEvent(EventType.SESSION_START, "New session started (history kept)")
        updateSessionInfo()
    }

    private fun restoreModelSelection(models: List<CopilotAcpClient.Model>) {
        val savedModel = CopilotSettings.getSelectedModel()
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

    private fun loadModelsAsync(onSuccess: (List<CopilotAcpClient.Model>) -> Unit) {
        SwingUtilities.invokeLater {
            loadingSpinner.isVisible = true
            modelsStatusText = MSG_LOADING
            selectedModelIndex = -1
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            var lastError: Exception? = null
            for (attempt in 1..3) {
                try {
                    val models = CopilotService.getInstance(project).getClient().listModels().toList()
                    SwingUtilities.invokeLater {
                        loadingSpinner.isVisible = false
                        modelsStatusText = null
                        restoreModelSelection(models)
                        onSuccess(models)
                    }
                    return@executeOnPooledThread
                } catch (e: Exception) {
                    lastError = e
                    if (authService.isAuthenticationError(e.message ?: "")) break
                    if (attempt < 3) Thread.sleep(2000L)
                }
            }
            val errorMsg = lastError?.message ?: MSG_UNKNOWN_ERROR
            LOG.warn("Failed to load models: $errorMsg")
            SwingUtilities.invokeLater {
                loadingSpinner.suspend()
                loadingSpinner.isVisible = false
                modelsStatusText = "Unavailable"
                if (authService.isAuthenticationError(errorMsg)) {
                    authService.markAuthError(errorMsg)
                    copilotBanner?.triggerCheck()
                }
            }
        }
    }

    // Data classes
    private data class ContextItem(
        val path: String,
        val name: String,
        val startLine: Int,
        val endLine: Int,
        val fileType: com.intellij.openapi.fileTypes.FileType?,
        val isSelection: Boolean
    )

    // TimelineEvent and EventType extracted to DebugPanel.kt

    /** Tree node that holds file content and path for the Plans tab. */
    private class FileTreeNode(
        val fileName: String,
        val filePath: String,
        val fileContent: String
    ) : javax.swing.tree.DefaultMutableTreeNode("\uD83D\uDCC4 $fileName")
}
