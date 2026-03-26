package com.github.catatafishen.ideagentforcopilot.ui

import com.github.catatafishen.ideagentforcopilot.services.ChatWebServer
import com.github.catatafishen.ideagentforcopilot.services.ToolChipRegistry
import com.github.catatafishen.ideagentforcopilot.settings.McpServerSettings
import com.github.catatafishen.ideagentforcopilot.settings.ScratchTypeSettings
import com.github.catatafishen.ideagentforcopilot.ui.renderers.ArgumentAwareRenderer
import com.github.catatafishen.ideagentforcopilot.ui.renderers.ToolRenderers
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import java.awt.BorderLayout
import java.util.*
import javax.swing.JComponent

/**
 * Chat panel — web-component-based implementation.
 * All rendering delegated to JS ChatController; Kotlin manages data model and bridge.
 */
class ChatConsolePanel(private val project: Project) : JBPanel<ChatConsolePanel>(BorderLayout()), ChatPanelApi {

    override val component: JComponent get() = this
    override var onQuickReply: ((String) -> Unit)? = null
    override var onStatusMessage: ((type: String, message: String) -> Unit)? = null
    var onCancelNudge: ((String) -> Unit)? = null
    var onCancelQueuedMessage: ((id: String, text: String) -> Unit)? = null

    // ── Data model (same types as V1 for serialization compat) ─────
    private val entries = mutableListOf<EntryData>()
    private var currentTextData: EntryData.Text? = null
    private var currentThinkingData: EntryData.Thinking? = null
    private var nextSubAgentColor = 0
    private var turnCounter = 0
    private var currentTurnId = ""
    private var toolJustCompleted = false
    private var currentAgent = ""
    private var currentClientType = ""
    private val toolCallNames = mutableMapOf<String, String>() // domId → tool baseName
    private val toolCallEntries = mutableMapOf<String, EntryData.ToolCall>() // domId → entry
    private val toolRegistry = com.github.catatafishen.ideagentforcopilot.services.ToolRegistry.getInstance(project)
    private val registry: ToolChipRegistry by lazy { ToolChipRegistry.getInstance(project) }

    private val fileNavigator = FileNavigator(project)

    private val kindStateListener = ToolChipRegistry.ChipStateWithKindListener { chipId, state, kind ->
        // Use "t-$chipId" — chips are registered in the DOM as data-chip-for="t-<chipId>"
        val did = "t-$chipId"
        if (state == ToolChipRegistry.ChipState.RUNNING) {
            // MCP is handling this tool — mark as agentbridge tool (solid border) and set running
            executeJs("ChatController.markMcpHandled('$did')")
            // Mark the entry as MCP handled for persistence
            toolCallEntries[did]?.mcpHandled = true
        } else {
            // COMPLETE, EXTERNAL, FAILED — just remove the spinner; border already shows origin
            val jsState = if (state == ToolChipRegistry.ChipState.FAILED) "failed" else "complete"
            executeJs("ChatController.setToolChipState('$did','$jsState')")
            toolJustCompleted = true
        }
        if (kind != null) {
            val jsKind = kind.replace("'", "\\'")
            executeJs("ChatController.updateToolCallKind('$did','$jsKind')")
        }
    }

    // ── JCEF ───────────────────────────────────────────────────────
    private val browser: JBCefBrowser?
    private val openFileQuery: JBCefJSQuery?
    private var browserReady = false
    private val pendingJs = mutableListOf<String>()
    private var openUrlBridgeJs = ""
    private var cursorBridgeJs = ""
    private var loadMoreBridgeJs = ""
    private var quickReplyBridgeJs = ""
    private var htmlQueryBridgeJs = ""
    private var permissionResponseBridgeJs = ""
    private var openScratchBridgeJs = ""
    private var showToolPopupBridgeJs = ""
    private var cancelNudgeBridgeJs = ""
    private var cancelQueuedMessageBridgeJs = ""

    @Volatile
    private var htmlPageFuture: java.util.concurrent.CompletableFuture<String>? = null
    private val pendingPermissionCallbacks =
        java.util.concurrent.ConcurrentHashMap<String, (com.github.catatafishen.ideagentforcopilot.bridge.PermissionResponse) -> Unit>()
    private val pendingAskUserCallbacks = java.util.concurrent.ConcurrentHashMap<String, (String) -> Unit>()

    @Volatile
    private var activeAskUserRequestId: String? = null

    // Periodic JCEF repaint during streaming to avoid partial-update artifacts
    private val repaintTimer = javax.swing.Timer(150) {
        browser?.cefBrowser?.invalidate()
    }.apply { isRepeats = true }

    // ── Swing fallback ─────────────────────────────────────────────
    private val fallbackArea: JBTextArea?

    companion object {
        private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(ChatConsolePanel::class.java)
        private val QUICK_REPLY_TAG_REGEX = Regex("\\[quick-reply:\\s*([^]]+)]")

        /** Active panels keyed by project — used by MCP tool to retrieve page HTML. */
        private val instances = java.util.concurrent.ConcurrentHashMap<Project, ChatConsolePanel>()

        fun getInstance(project: Project): ChatConsolePanel? = instances[project]

        private const val FAILED_SPAN = "<span style='color:var(--error)'>✖ Failed</span>"
    }

    // ── Init ───────────────────────────────────────────────────────

    init {
        if (JBCefApp.isSupported()) {
            browser = JBCefBrowser()
            val panelBg = com.intellij.util.ui.JBUI.CurrentTheme.ToolWindow.background()
            browser.setPageBackgroundColor("rgb(${panelBg.red},${panelBg.green},${panelBg.blue})")
            openFileQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase)
            openFileQuery.addHandler { handleFileLink(it); null }
            Disposer.register(this, openFileQuery)
            Disposer.register(this, browser)

            val openUrlQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase)
            openUrlQuery.addHandler { url -> com.intellij.ide.BrowserUtil.browse(url); null }
            Disposer.register(this, openUrlQuery)
            openUrlBridgeJs = openUrlQuery.inject("url")

            val cursorQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase)
            cursorQuery.addHandler { type ->
                ApplicationManager.getApplication().invokeLater {
                    browser.component.cursor = when (type) {
                        "pointer" -> java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                        "text" -> java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.TEXT_CURSOR)
                        "grab", "grabbing" -> java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.MOVE_CURSOR)
                        "nwse-resize" -> java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.SE_RESIZE_CURSOR)
                        else -> java.awt.Cursor.getDefaultCursor()
                    }
                }
                null
            }
            Disposer.register(this, cursorQuery)
            cursorBridgeJs = cursorQuery.inject("c")

            val loadMoreQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase)
            loadMoreQuery.addHandler { onLoadMoreRequested?.invoke(); null }
            Disposer.register(this, loadMoreQuery)
            loadMoreBridgeJs = loadMoreQuery.inject("'load'")

            val quickReplyQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase)
            quickReplyQuery.addHandler { text -> onQuickReply?.invoke(text); null }
            Disposer.register(this, quickReplyQuery)
            quickReplyBridgeJs = quickReplyQuery.inject("text")

            val htmlQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase)
            htmlQuery.addHandler { html ->
                htmlPageFuture?.complete(html)
                null
            }
            Disposer.register(this, htmlQuery)
            htmlQueryBridgeJs = htmlQuery.inject("html")

            val permissionResponseQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase)
            permissionResponseQuery.addHandler { data ->
                // data format: "reqId:deny", "reqId:once", "reqId:session", or "reqId:always"
                val colonIdx = data.lastIndexOf(':')
                if (colonIdx > 0) {
                    val reqId = data.substring(0, colonIdx)
                    val response = when (data.substring(colonIdx + 1)) {
                        "once" -> com.github.catatafishen.ideagentforcopilot.bridge.PermissionResponse.ALLOW_ONCE
                        "session" -> com.github.catatafishen.ideagentforcopilot.bridge.PermissionResponse.ALLOW_SESSION
                        "always" -> com.github.catatafishen.ideagentforcopilot.bridge.PermissionResponse.ALLOW_ALWAYS
                        else -> com.github.catatafishen.ideagentforcopilot.bridge.PermissionResponse.DENY
                    }
                    pendingPermissionCallbacks.remove(reqId)?.invoke(response)
                }
                null
            }
            Disposer.register(this, permissionResponseQuery)
            permissionResponseBridgeJs = permissionResponseQuery.inject("data")

            val openScratchQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase)
            openScratchQuery.addHandler { data -> handleOpenScratch(data); null }
            Disposer.register(this, openScratchQuery)
            openScratchBridgeJs = openScratchQuery.inject("lang + '\\n' + content")

            val showToolPopupQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase)
            showToolPopupQuery.addHandler { toolDomId -> handleShowToolPopup(toolDomId); null }
            Disposer.register(this, showToolPopupQuery)
            showToolPopupBridgeJs = showToolPopupQuery.inject("id")

            val cancelNudgeQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase)
            cancelNudgeQuery.addHandler { id -> onCancelNudge?.invoke(id); null }
            Disposer.register(this, cancelNudgeQuery)
            cancelNudgeBridgeJs = cancelNudgeQuery.inject("id")

            val cancelQueuedMessageQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase)
            cancelQueuedMessageQuery.addHandler { json ->
                val obj = JsonParser.parseString(json).asJsonObject
                onCancelQueuedMessage?.invoke(obj["id"].asString, obj["text"].asString)
                null
            }
            Disposer.register(this, cancelQueuedMessageQuery)
            cancelQueuedMessageBridgeJs = cancelQueuedMessageQuery.inject("JSON.stringify({id: id, text: text})")

            add(browser.component, BorderLayout.CENTER)

            browser.jbCefClient.addLoadHandler(
                com.github.catatafishen.ideagentforcopilot.psi.PlatformApiCompat.createMainFrameLoadEndHandler {
                    ApplicationManager.getApplication().invokeLater {
                        browserReady = true
                        pendingJs.forEach { browser.cefBrowser.executeJavaScript(it, "", 0) }
                        pendingJs.clear()
                    }
                }, browser.cefBrowser
            )

            browser.jbCefClient.addDisplayHandler(
                com.github.catatafishen.ideagentforcopilot.psi.PlatformApiCompat.createConsoleLogHandler(
                    com.intellij.openapi.diagnostic.Logger.getInstance(ChatConsolePanel::class.java)
                ), browser.cefBrowser
            )

            browser.loadHTML(buildInitialPage())
            fallbackArea = null

            com.github.catatafishen.ideagentforcopilot.psi.PlatformApiCompat.subscribeLafChanges(this) { updateThemeColors() }
        } else {
            browser = null; openFileQuery = null
            fallbackArea = JBTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
            add(JBScrollPane(fallbackArea), BorderLayout.CENTER)
        }
        instances[project] = this
        registerChipStateListener()
    }

    private fun registerChipStateListener() {
        registry.addKindStateListener(kindStateListener)
    }

    // ── Public API ─────────────────────────────────────────────────

    override fun addPromptEntry(text: String, contextFiles: List<Triple<String, String, Int>>?, bubbleHtml: String?) {
        toolJustCompleted = false
        finalizeCurrentText()
        collapseThinking()
        currentTurnId = "t${turnCounter++}"
        val ts = timestamp()
        entries.add(EntryData.Prompt(text, ts, contextFiles))
        val encodedBubble = if (bubbleHtml != null) b64(bubbleHtml) else ""
        executeJs("ChatController.addUserMessage('${escJs(text)}','${displayTs(ts)}','$encodedBubble');ChatController.showWorkingIndicator()")
    }

    override fun startStreaming() {
        repaintTimer.start()
    }

    override fun setPromptStats(modelId: String, multiplier: String) {
        val short = escJs(modelId.substringAfterLast("/").take(30))
        executeJs("ChatController.setPromptStats('$short','${escJs(multiplier)}')")
    }

    override fun setCodeChangeStats(linesAdded: Int, linesRemoved: Int) {
        executeJs("ChatController.setCodeChangeStats($linesAdded,$linesRemoved)")
    }

    override fun setCurrentModel(modelId: String) {
        executeJs("ChatController.setCurrentModel('${escJs(modelId)}')")
    }

    override fun setCurrentProfile(profileId: String) {
        executeJs("ChatController.setCurrentProfile('${escJs(profileId)}')")
    }

    override fun setCurrentAgent(agentName: String, profileId: String, clientType: String) {
        currentAgent = agentName
        currentClientType = clientType
        val agentCss = ChatTheme.activeAgentCss(profileId)
        val isDark = com.intellij.ide.ui.LafManager.getInstance().currentUIThemeLookAndFeel.isDark
        val iconSvg = ChatTheme.getAgentIconSvg(profileId, isDark)
        executeJs(
            "document.documentElement.style.cssText += '$agentCss';ChatController.setClientType('${
                escJs(
                    clientType
                )
            }', '${escJs(iconSvg)}')"
        )
    }

    override fun addContextFilesEntry(files: List<Pair<String, String>>) {
        entries.add(EntryData.ContextFiles(files))
    }

    override fun appendThinkingText(text: String) {
        maybeStartNewSegment()
        if (currentThinkingData == null) {
            currentThinkingData =
                EntryData.Thinking(StringBuilder(), timestamp(), currentAgent).also { entries.add(it) }
        }
        currentThinkingData!!.raw.append(text)
        executeJs("ChatController.addThinkingText('$currentTurnId','main','${escJs(text)}')")
    }

    override fun collapseThinking() {
        if (currentThinkingData == null) return
        currentThinkingData = null
        executeJs("ChatController.collapseThinking('$currentTurnId','main')")
    }

    override fun appendText(text: String) {
        maybeStartNewSegment()
        collapseThinking()

        // ACP framework status messages arrive as regular text chunks — render them
        // as distinct info/error entries instead of plain agent text.
        if (text.startsWith("Info: ")) {
            addInfoEntry(text.removePrefix("Info: ").trimEnd())
            return
        }
        if (text.startsWith("Error: ")) {
            addErrorEntry(text.removePrefix("Error: ").trimEnd())
            return
        }

        if (currentTextData == null && text.isBlank()) return
        if (currentTextData == null) {
            currentTextData = EntryData.Text(StringBuilder(), timestamp(), currentAgent).also { entries.add(it) }
        }
        currentTextData!!.raw.append(text)
        executeJs("ChatController.appendAgentText('$currentTurnId','main','${escJs(text)}')")
        fallbackArea?.let { ApplicationManager.getApplication().invokeLater { it.append(text) } }
    }

    override fun addToolCallEntry(
        id: String,
        title: String,
        arguments: String?,
        kind: String?
    ) {
        val cleanTitle = title.trim('\'', '"')
        finalizeCurrentText()
        val resolvedKind = kind ?: "other"

        // Extract file path from arguments for edit tools
        val filePath = extractFilePathFromArgs(cleanTitle, arguments)

        val entry =
            EntryData.ToolCall(
                cleanTitle, arguments, resolvedKind, null, null, null, filePath,
                autoDenied = false, denialReason = null,
                timestamp = timestamp(), agent = currentAgent
            )
        entries.add(entry)

        val def = toolRegistry?.findById(cleanTitle)
        val info = TOOL_DISPLAY_INFO[cleanTitle]
        val displayName = def?.displayName() ?: info?.displayName ?: cleanTitle.replaceFirstChar { it.uppercaseChar() }
        val short = formatToolSubtitle(cleanTitle, arguments)
        val label = if (short != null) "$displayName — $short" else displayName
        val hasCustomRenderer = ToolRenderers.hasRenderer(cleanTitle, toolRegistry)
        val paramsJson = if (!arguments.isNullOrBlank() && !hasCustomRenderer) escJs(arguments) else ""
        val safeKind = escJs(resolvedKind)

        // Parse args for correlation
        val argsObj = arguments?.let {
            try {
                JsonParser.parseString(it).takeIf { e -> e.isJsonObject }?.asJsonObject
            } catch (_: Exception) {
                null
            }
        }

        // Register with chip registry — returns chipId and whether MCP already handled it
        val registration = registry.registerClientSide(cleanTitle, argsObj, id)
        val chipId = registration.chipId()
        val did = "t-$chipId"
        toolCallNames[did] = cleanTitle
        toolCallEntries[did] = entry

        val isMcpHandled = registration.initialState() == ToolChipRegistry.ChipState.RUNNING
        if (isMcpHandled) {
            entry.mcpHandled = true
        }

        val initialStatus = if (isMcpHandled) "running" else "pending"
        executeJs("ChatController.upsertToolChip('$currentTurnId','main','$did','${escJs(label)}','$paramsJson','$safeKind','$initialStatus')")
        if (isMcpHandled) {
            executeJs("ChatController.markMcpHandled('$did')")
        }
    }

    override fun updateToolCall(
        id: String,
        status: String,
        details: String?,
        description: String?,
        kind: String?,
        autoDenied: Boolean,
        denialReason: String?,
        arguments: String?,
        title: String?
    ) {
        val chipId = registry.findChipIdByClientId(id)
        var did = if (chipId != null) "t-$chipId" else domId(id)

        // Try to re-correlate if we have new arguments and status is running
        if (arguments != null && status == "running") {
            try {
                val argsObj = JsonParser.parseString(arguments).asJsonObject
                val registration = registry.reregisterWithArgs(id, argsObj)
                val newChipId = registration.chipId()
                val newDid = "t-$newChipId"

                if (newDid != did) {
                    val entry = toolCallEntries.remove(did)
                    if (entry != null) {
                        toolCallEntries[newDid] = entry
                    }
                    val name = toolCallNames.remove(did)
                    if (name != null) {
                        toolCallNames[newDid] = name
                    }

                    // Remove old chip DOM element
                    executeJs("ChatController.removeToolChip('$did')")

                    // Create new chip with correct hash-based ID
                    val cleanTitle = (title ?: name ?: "Tool").trim('\'', '"')
                    val resolvedKind = kind ?: entry?.kind ?: "other"
                    val def = toolRegistry?.findById(cleanTitle)
                    val info = TOOL_DISPLAY_INFO[cleanTitle]
                    val displayName =
                        def?.displayName() ?: info?.displayName ?: cleanTitle.replaceFirstChar { it.uppercaseChar() }
                    val short = formatToolSubtitle(cleanTitle, arguments)
                    val label = if (short != null) "$displayName — $short" else displayName
                    val hasCustomRenderer = ToolRenderers.hasRenderer(cleanTitle, toolRegistry)
                    val paramsJson = if (!hasCustomRenderer) escJs(arguments) else ""

                    executeJs(
                        "ChatController.upsertToolChip('$currentTurnId','main','$newDid','${escJs(label)}','$paramsJson','${
                            escJs(
                                resolvedKind
                            )
                        }','running')"
                    )

                    did = newDid
                    if (registration.initialState() == ToolChipRegistry.ChipState.RUNNING) {
                        executeJs("ChatController.markMcpHandled('$did')")
                        toolCallEntries[did]?.mcpHandled = true
                    }
                    LOG.debug("updateToolCall: re-correlated chip $id: $did -> $newDid")
                }
            } catch (e: Exception) {
                LOG.warn("updateToolCall: failed to re-correlate chip $id", e)
            }
        }

        val resultLen = details?.length ?: 0
        LOG.debug("updateToolCall: id=$id, chipId=$chipId, status=$status, resultLen=$resultLen, hasDesc=${description != null}, denied=$autoDenied")
        toolCallEntries[did]?.let {
            it.result = details
            it.status = status
            it.autoDenied = autoDenied
            it.denialReason = denialReason
            if (description != null) it.description = description
            if (kind != null) it.kind = kind
        }
        // For intermediate running state, update DOM immediately
        if (status == "running") {
            executeJs("ChatController.setToolChipState('$did','running')")
            if (kind != null) {
                val jsKind = kind.replace("'", "\\'")
                executeJs("ChatController.updateToolCallKind('$did','$jsKind')")
            }
            return
        }

        // For terminal states, notify the registry — it determines COMPLETE vs EXTERNAL vs FAILED,
        // and the chip state listener updates the DOM with the authoritative final state.
        when (status) {
            "failed" -> registry.completeClientSide(id, false)
            else -> registry.completeClientSide(id, true) // "complete", "completed", etc.
        }

        if (autoDenied) {
            executeJs("ChatController.setToolChipState('$did','denied')")
        }
    }

    /** Add a tool call chip+section to a sub-agent's result message. */
    override fun addSubAgentToolCall(
        subAgentId: String,
        toolId: String,
        title: String,
        arguments: String?,
        kind: String?
    ) {
        val saDid = domId(subAgentId)
        val toolDid = domId(toolId)
        val resolvedKind = kind ?: "other"
        val cleanTitle = title.trim('\'', '"')
        val entry = EntryData.ToolCall(cleanTitle, arguments, resolvedKind)
        toolCallNames[toolDid] = cleanTitle
        toolCallEntries[toolDid] = entry

        val def = toolRegistry?.findById(cleanTitle)
        val info = TOOL_DISPLAY_INFO[cleanTitle]
        val displayName = def?.displayName() ?: info?.displayName ?: cleanTitle.replaceFirstChar { it.uppercaseChar() }
        val short = formatToolSubtitle(cleanTitle, arguments)
        val label = if (short != null) "$displayName — $short" else displayName
        val hasCustomRenderer = ToolRenderers.hasRenderer(cleanTitle, toolRegistry)
        val paramsJson = if (!arguments.isNullOrBlank() && !hasCustomRenderer) escJs(arguments) else ""
        val safeKind = escJs(resolvedKind)
        val isExternal = def == null  // Not from our MCP plugin
        executeJs("ChatController.addSubAgentToolCall('$saDid','$toolDid','${escJs(label)}','$paramsJson','$safeKind',$isExternal)")
    }

    /** Update a sub-agent internal tool call (no segment break). */
    override fun updateSubAgentToolCall(
        toolId: String,
        status: String,
        details: String?,
        description: String?,
        autoDenied: Boolean,
        denialReason: String?
    ) {
        val did = domId(toolId)
        toolCallEntries[did]?.let {
            it.result = details
            it.status = status
            it.autoDenied = autoDenied
            it.denialReason = denialReason
            if (description != null) it.description = description
        }
        val jsStatus = if (autoDenied) "denied" else when (status) {
            "failed" -> "failed"
            "running" -> "running"
            else -> "completed"
        }
        executeJs("ChatController.updateToolCall('$did','$jsStatus','$jsStatus')")
    }

    override fun addSubAgentEntry(
        id: String, agentType: String, description: String, prompt: String?,
        initialResult: String?, initialStatus: String?, initialDescription: String?,
        autoDenied: Boolean, denialReason: String?
    ) {
        maybeStartNewSegment()
        finalizeCurrentText()
        val colorIndex = nextSubAgentColor++ % ChatTheme.SA_COLOR_COUNT
        val entry = EntryData.SubAgent(
            agentType, description, prompt,
            colorIndex = colorIndex, callId = id,
            autoDenied = autoDenied, denialReason = denialReason,
            timestamp = timestamp(), agent = currentAgent
        )
        if (initialResult != null) {
            entry.result = initialResult; entry.status = initialStatus
            if (initialDescription != null) entry.result = "$initialDescription\n\n$initialResult"
        }
        entries.add(entry)
        val did = domId(id)
        val info = SUB_AGENT_INFO[agentType]
        val displayName = info?.displayName ?: agentType.replaceFirstChar { it.uppercaseChar() }
        val promptText = prompt ?: description
        executeJs(
            "ChatController.addSubAgent('$currentTurnId','main','$did','${escJs(displayName)}',$colorIndex,'${
                escJs(
                    promptText
                )
            }')"
        )
        if (autoDenied || !initialResult.isNullOrBlank() || initialStatus == "completed" || initialStatus == "failed") {
            val status = if (autoDenied) "denied" else (initialStatus ?: "completed")
            val resultHtml =
                if (autoDenied) FAILED_SPAN else if (!initialResult.isNullOrBlank()) markdownToHtml(initialResult) else if (initialStatus == "completed") "Completed" else FAILED_SPAN
            val encoded = b64(resultHtml)
            executeJs("ChatController.updateSubAgent('$did','$status',b64('$encoded'))")
        }
    }

    override fun updateSubAgentResult(
        id: String,
        status: String,
        result: String?,
        description: String?,
        autoDenied: Boolean,
        denialReason: String?
    ) {
        val entry = entries.filterIsInstance<EntryData.SubAgent>().find { it.callId == id }
            ?: entries.filterIsInstance<EntryData.SubAgent>().lastOrNull()
        entry?.let {
            it.result = result
            it.status = status
            it.autoDenied = autoDenied
            it.denialReason = denialReason
            if (description != null) it.result = "$description\n\n$result"
        }
        val did = domId(id)
        val jsStatus = if (autoDenied) "denied" else status
        val resultHtml =
            if (autoDenied) FAILED_SPAN else if (!result.isNullOrBlank()) markdownToHtml(result) else if (status == "completed") "Completed" else FAILED_SPAN
        val encoded = b64(resultHtml)
        executeJs("ChatController.updateSubAgent('$did','$jsStatus',b64('$encoded'))")
        toolJustCompleted = true
    }

    override fun addErrorEntry(message: String) {
        finalizeCurrentText()
        entries.add(EntryData.Status("❌", message))
        onStatusMessage?.invoke("error", message)
    }

    override fun addInfoEntry(message: String) {
        finalizeCurrentText()
        entries.add(EntryData.Status("ℹ", message))
        onStatusMessage?.invoke("info", message)
    }

    override fun hasContent(): Boolean = entries.isNotEmpty()

    override fun addSessionSeparator(timestamp: String, agent: String) {
        finalizeCurrentText()
        entries.add(EntryData.SessionSeparator(timestamp, agent))
        executeJs("ChatController.addSessionSeparator('${escJs(displayTsSeparator(timestamp))}', '${escJs(agent)}')")
    }

    override fun showPlaceholder(text: String) {
        entries.clear()
        currentTextData = null; currentThinkingData = null; nextSubAgentColor = 0
        turnCounter = 0; currentTurnId = ""; toolJustCompleted = false
        executeJs("ChatController.showPlaceholder('${escJs(text)}')")
        fallbackArea?.let { ApplicationManager.getApplication().invokeLater { it.text = text } }
    }

    override fun clear() {
        entries.clear()
        currentTextData = null; currentThinkingData = null; nextSubAgentColor = 0
        turnCounter = 0; currentTurnId = ""; toolJustCompleted = false
        toolCallNames.clear(); toolCallEntries.clear()
        registry.clear()
        clearPendingAskUserRequest(null)
        executeJs("ChatController.clear()")
        fallbackArea?.let { ApplicationManager.getApplication().invokeLater { it.text = "" } }
    }

    override fun finishResponse(toolCallCount: Int, modelId: String, multiplier: String) {
        repaintTimer.stop()
        toolJustCompleted = false
        finalizeCurrentText()
        collapseThinking()
        val statsJson = """{"tools":$toolCallCount,"model":"${escJs(modelId)}","mult":"${escJs(multiplier)}"}"""

        // Clear the chip registry for this turn
        registry.clearTurn()

        executeJs("ChatController.finalizeTurn('$currentTurnId',$statsJson)")
        ApplicationManager.getApplication().invokeLater { browser?.component?.repaint() }
        ChatWebServer.getInstance(project)
            ?.pushNotification("Turn complete", "Agent finished ($toolCallCount tool calls)")
    }

    override fun showQuickReplies(options: List<String>) {
        if (options.isEmpty()) return
        val json = options.joinToString(",") { "'${escJs(it)}'" }
        executeJs("ChatController.showQuickReplies([$json])")
    }

    override fun disableQuickReplies() {
        executeJs("ChatController.disableQuickReplies()")
    }

    override fun cancelAllRunning() {
        repaintTimer.stop()
        clearPendingAskUserRequest(null)
        executeJs("ChatController.cancelAllRunning()")
    }

    // ── Conversation export ────────────────────────────────────────

    private val exporter: ConversationExporter get() = ConversationExporter(entries)
    override fun getConversationText(): String = exporter.getConversationText()
    override fun getCompressedSummary(maxChars: Int): String = exporter.getCompressedSummary(maxChars)
    override fun getConversationHtml(): String = exporter.getConversationHtml()

    override fun getLastResponseText(): String =
        entries.filterIsInstance<EntryData.Text>().lastOrNull()?.raw?.toString() ?: ""

    // ── History / persistence API ──────────────────────────────────

    var onLoadMoreRequested: (() -> Unit)? = null

    fun getEntries(): List<EntryData> = entries.toList()

    fun showLoadMore(deferredCount: Int) {
        executeJs("ChatController.showLoadMore($deferredCount)")
    }

    fun hideLoadMore() {
        executeJs("ChatController.removeLoadMore()")
    }

    fun appendEntries(entries: List<EntryData>) {
        if (entries.isEmpty()) return
        for (e in entries) addEntryFromData(e)
        val turnCount = entries.count { it is EntryData.Prompt }
        if (turnCount > 0) turnCounter += turnCount
        val html = renderBatchGroupedHtml(entries)
        if (html.isNotEmpty()) {
            val encoded = b64(html)
            executeJs("ChatController.restoreBatch('$encoded')")
        }
    }

    fun prependEntries(entries: List<EntryData>) {
        if (entries.isEmpty()) return
        for ((idx, e) in entries.withIndex()) this.entries.add(idx, e)
        val html = renderBatchGroupedHtml(entries)
        if (html.isNotEmpty()) {
            val encoded = b64(html)
            executeJs("ChatController.prependBatch('$encoded')")
        }
    }

    private fun addEntryFromData(e: EntryData) {
        this.entries.add(e)
        if (e is EntryData.ToolCall) toolCallEntries["batch-tool-${batchIdCounter}"] = e
    }

    private fun buildRestoredBubbleHtml(text: String, ctxFiles: List<Triple<String, String, Int>>): String {
        var result = esc(text)
        for ((name, path, line) in ctxFiles) {
            val href = if (line > 0) "openfile://$path:$line" else "openfile://$path"
            val title = esc(if (line > 0) "$path:$line" else path)
            val chip = "<a class='prompt-ctx-chip' href='$href' title='$title'>${esc(name)}</a>"
            result = result.replaceFirst("`${esc(name)}`", chip)
        }
        return result
    }

    private var batchIdCounter = 0

    private fun renderBatchGroupedHtml(entries: List<EntryData>): String {
        val sb = StringBuilder()
        var i = 0
        while (i < entries.size) {
            when (val e = entries[i]) {
                is EntryData.Prompt -> {
                    sb.append(buildPromptHtml(e)); i++
                }

                is EntryData.SessionSeparator -> {
                    sb.append("<session-divider timestamp='${esc(displayTsSeparator(e.timestamp))}' agent='${esc(e.agent)}'></session-divider>")
                    i++
                }

                is EntryData.Status, is EntryData.ContextFiles -> i++ // transient / non-visual in restored HTML
                else -> i = appendAgentTurn(entries, i, sb)
            }
        }
        return sb.toString()
    }

    private fun buildPromptHtml(e: EntryData.Prompt): String {
        val sb = StringBuilder()
        sb.append("<chat-message type='user'>")
        sb.append("<message-meta><span class='ts'>${esc(displayTs(e.timestamp))}</span></message-meta>")
        sb.append("<message-bubble type='user'>")
        if (!e.contextFiles.isNullOrEmpty()) {
            sb.append(buildRestoredBubbleHtml(e.text, e.contextFiles))
        } else {
            sb.append(esc(e.text))
        }
        sb.append("</message-bubble>")
        sb.append("</chat-message>")
        return sb.toString()
    }

    private fun appendAgentTurn(entries: List<EntryData>, startI: Int, sb: StringBuilder): Int {
        var i = startI
        var segmentMetaChips = StringBuilder()
        var segmentDetailsContent = StringBuilder()
        var segmentAfterDetails = StringBuilder()
        var segmentStarted = false
        var hadToolOrSubagent = false
        var segmentTimestamp = ""
        var segmentAgent = ""

        fun flushSegment() {
            sb.append("<chat-message type='agent'")
            if (segmentAgent.isNotEmpty()) {
                sb.append(" data-agent='${esc(segmentAgent)}'")
            }
            sb.append(">")
            sb.append("<message-meta")
            if (segmentMetaChips.isNotEmpty()) {
                sb.append(" class='show'")
            }
            sb.append(">")
            if (segmentTimestamp.isNotEmpty()) {
                sb.append("<span class='ts'>${esc(displayTs(segmentTimestamp))}</span>")
            }
            sb.append(segmentMetaChips)
            sb.append("</message-meta>")
            sb.append("<turn-details>$segmentDetailsContent</turn-details>")
            sb.append(segmentAfterDetails)
            sb.append("</chat-message>")
            segmentMetaChips = StringBuilder()
            segmentDetailsContent = StringBuilder()
            segmentAfterDetails = StringBuilder()
            segmentTimestamp = ""
            segmentAgent = ""
            hadToolOrSubagent = false
        }

        while (i < entries.size) {
            val e = entries[i]
            if (e is EntryData.Prompt || e is EntryData.SessionSeparator || e is EntryData.Status) break
            if (e is EntryData.ContextFiles) {
                i++; continue
            }
            if (hadToolOrSubagent && (e is EntryData.Text || e is EntryData.Thinking)) flushSegment()

            // Capture timestamp and agent from the first entry in the segment
            if (!segmentStarted) {
                when (e) {
                    is EntryData.Text -> {
                        segmentTimestamp = e.timestamp; segmentAgent = e.agent
                    }

                    is EntryData.Thinking -> {
                        segmentTimestamp = e.timestamp; segmentAgent = e.agent
                    }

                    is EntryData.ToolCall -> {
                        segmentTimestamp = e.timestamp; segmentAgent = e.agent
                    }

                    is EntryData.SubAgent -> {
                        segmentTimestamp = e.timestamp; segmentAgent = e.agent
                    }

                    else -> {}
                }
            }

            appendAgentEntry(e, segmentMetaChips, segmentDetailsContent, segmentAfterDetails)
            if (e is EntryData.ToolCall || e is EntryData.SubAgent) hadToolOrSubagent = true
            segmentStarted = true
            i++
        }
        if (segmentStarted) flushSegment()
        return i
    }

    private fun appendAgentEntry(
        e: EntryData,
        metaChips: StringBuilder,
        detailsContent: StringBuilder,
        afterDetails: StringBuilder
    ) {
        when (e) {
            is EntryData.Thinking -> {
                val raw = e.raw.toString()
                if (raw.isNotBlank()) {
                    val id = "batch-think-${batchIdCounter++}"
                    metaChips.append("<thinking-chip label='Thought' status='complete' data-chip-for='$id'></thinking-chip>")
                    detailsContent.append(
                        "<thinking-block id='$id' class='thinking-section turn-hidden'><div class='thinking-content'>${
                            esc(
                                raw
                            )
                        }</div></thinking-block>"
                    )
                }
            }

            is EntryData.ToolCall -> appendToolEntry(e, metaChips)

            is EntryData.Text -> {
                val raw = e.raw.toString()
                if (raw.isNotBlank()) {
                    val clean = raw.replace(QUICK_REPLY_TAG_REGEX, "").trimEnd()
                    afterDetails.append("<message-bubble>${markdownToHtml(clean)}</message-bubble>")
                }
            }

            is EntryData.SubAgent -> {
                val saInfo = SUB_AGENT_INFO[e.agentType]
                val dn = saInfo?.displayName ?: e.agentType.replaceFirstChar { it.uppercaseChar() }
                val resultHtml = if (!e.result.isNullOrBlank()) markdownToHtml(e.result!!) else "Completed"
                val id = "batch-sa-${batchIdCounter++}"
                metaChips.append("<subagent-chip label='${esc(dn)}' status='complete' color-index='${e.colorIndex}' data-chip-for='$id'></subagent-chip>")
                afterDetails.append("<div id='$id' class='subagent-indent subagent-c${e.colorIndex} turn-hidden'><message-bubble>$resultHtml</message-bubble></div>")
            }

            else -> { /* Prompt, ContextFiles, Status, SessionSeparator are handled by the caller */
            }
        }
    }

    private fun appendToolEntry(e: EntryData.ToolCall, metaChips: StringBuilder) {
        val title = e.title.trim('\'', '"')
        val info = TOOL_DISPLAY_INFO[title]
        val displayName = info?.displayName ?: title.replaceFirstChar { it.uppercaseChar() }
        val short = formatToolSubtitle(title, e.arguments)
        val label = if (short != null) "$displayName — $short" else displayName
        val id = "batch-tool-${batchIdCounter++}"
        val result = e.result
        val status = e.status ?: "completed"
        toolCallNames[id] = title
        toolCallEntries[id] = EntryData.ToolCall(
            title, e.arguments, e.kind,
            result = result, status = status, description = e.description,
            mcpHandled = e.mcpHandled
        )
        val paramsAttr = if (e.arguments != null) " data-params='${esc(e.arguments)}'" else ""
        val mcpAttr = if (e.mcpHandled) " data-mcp-handled='true'" else ""
        metaChips.append("<tool-chip label='${esc(label)}' status='complete' kind='${esc(e.kind)}' data-chip-for='$id'$paramsAttr$mcpAttr></tool-chip>")
    }

    @Suppress("kotlin:S6518") // False positive: CompletableFuture.get(long, TimeUnit) is not an indexed accessor
    override fun getPageHtml(): String? {
        if (browser == null || !browserReady || htmlQueryBridgeJs.isBlank()) return null
        val future = java.util.concurrent.CompletableFuture<String>()
        val trigger = Runnable {
            htmlPageFuture = future
            browser.cefBrowser.executeJavaScript(
                "(function(){ var el = document.querySelector('#messages'); var html = el ? el.innerHTML : ''; $htmlQueryBridgeJs })()",
                "", 0
            )
        }
        if (ApplicationManager.getApplication().isDispatchThread) {
            trigger.run()
        } else {
            ApplicationManager.getApplication().invokeLater(trigger)
        }
        return try {
            future.get(5, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) {
            null
        } finally {
            htmlPageFuture = null
        }
    }

    override fun dispose() {
        registry.removeKindStateListener(kindStateListener)
        repaintTimer.stop()
        instances.remove(project)
    }

    // ── Internal ───────────────────────────────────────────────────

    private fun maybeStartNewSegment() {
        if (!toolJustCompleted) return
        toolJustCompleted = false
        finalizeCurrentText()
        collapseThinking()
        executeJs("ChatController.newSegment('$currentTurnId','main')")
    }

    private fun finalizeCurrentText() {
        val data = currentTextData ?: return
        currentTextData = null
        val turnId = currentTurnId
        val rawText = data.raw.toString()
        if (rawText.isBlank()) {
            executeJs("ChatController.finalizeAgentText('$turnId','main',null)")
            entries.remove(data); return
        }
        val cleanText = rawText.replace(QUICK_REPLY_TAG_REGEX, "").trimEnd()
        val html = markdownToHtml(cleanText)
        val encoded = b64(html)
        executeJs("ChatController.finalizeAgentText('$turnId','main','$encoded')")
    }

    private fun executeJs(js: String) {
        val short = if (js.length > 80) js.take(80) + "…" else js
        if (browserReady) {
            com.intellij.openapi.diagnostic.Logger.getInstance(ChatConsolePanel::class.java)
                .info("executeJs (ready): $short")
            browser?.cefBrowser?.executeJavaScript(js, "", 0)
        } else {
            com.intellij.openapi.diagnostic.Logger.getInstance(ChatConsolePanel::class.java)
                .info("executeJs (queued): $short")
            pendingJs.add(js)
        }
        if (!js.startsWith("document.")) {
            ChatWebServer.getInstance(project)?.pushJsEvent(js)
        }
    }

    private fun formatToolSubtitle(baseName: String, arguments: String?): String? {
        if (arguments.isNullOrBlank()) return null
        val key = TOOL_SUBTITLE_KEY[baseName] ?: return null
        return try {
            val json = com.google.gson.JsonParser.parseString(arguments).asJsonObject
            val value = json[key]?.asString ?: return null
            if (value.length > 40) "…" + value.takeLast(37) else value
        } catch (_: Exception) {
            null
        }
    }

    private fun handleFileLink(href: String) = fileNavigator.handleFileLink(href)

    private fun markdownToHtml(text: String): String = fileNavigator.markdownToHtml(text)

    // ── Tool result panel rendering ─────────────────────────────

    /**
     * Creates a Swing component for the tool result, dispatching to a custom renderer
     * or falling back to a monospace code panel.
     */
    private fun renderToolResultPanel(
        baseName: String?,
        status: String?,
        details: String?,
        arguments: String? = null,
        description: String? = null,
        autoDenied: Boolean = false,
        denialReason: String? = null
    ): JComponent {
        val detailsLen = details?.length ?: 0
        val descLen = description?.length ?: 0
        LOG.debug("renderToolResultPanel: baseName=$baseName, status=$status, detailsLen=$detailsLen, descLen=$descLen, denied=$autoDenied")

        val container = ToolRenderers.listPanel()

        if (autoDenied) {
            container.add(JBLabel("<html><body style='width: 450px'><span style='color: #FF0000; font-weight: bold;'>Tool call was automatically denied.</span><br/>Reason: ${denialReason ?: "Security policy"}</body></html>").apply {
                border = com.intellij.util.ui.JBUI.Borders.empty(0, 0, 8, 0)
                alignmentX = JComponent.LEFT_ALIGNMENT
            })
        }

        // 1. Show natural language description/explanation if available
        if (!description.isNullOrBlank()) {
            container.add(JBLabel("<html><body style='width: 450px'>${markdownToHtml(description)}</body></html>").apply {
                border = com.intellij.util.ui.JBUI.Borders.empty(0, 0, 8, 0)
                alignmentX = JComponent.LEFT_ALIGNMENT
            })
        }

        // 2. Determine if we have a real tool result or if we should fallback to showing arguments
        val finalDetails = if (details.isNullOrBlank() && !arguments.isNullOrBlank()) {
            // No result but we have arguments? Junie often doesn't stream the raw tool output.
            // As a fallback, we show the parameters so the user knows what was called.
            "Parameters: $arguments"
        } else {
            details
        }
        if (finalDetails.isNullOrBlank()) {
            val label = when (status) {
                "failed" -> "✖ Failed"
                "running" -> "⏳ Running…"
                else -> if (baseName != null) "Tool $baseName completed with no output." else "Completed"
            }
            container.add(JBLabel(label).apply {
                foreground = if (status == "failed") ToolRenderers.FAIL_COLOR else ToolRenderers.MUTED_COLOR
                border = com.intellij.util.ui.JBUI.Borders.empty(4, 0)
                alignmentX = JComponent.LEFT_ALIGNMENT
            })
            return container
        }

        // 3. Attempt to use a custom renderer for the result
        if (status != "failed" && baseName != null) {
            val renderer = ToolRenderers.get(baseName, toolRegistry)
            LOG.debug("Renderer for $baseName: ${renderer?.javaClass?.simpleName ?: "null"}")

            val rendered = when (renderer) {
                is ArgumentAwareRenderer -> renderer.render(finalDetails, arguments)
                else -> renderer?.render(finalDetails)
            }

            if (rendered != null) {
                container.add(rendered)
                return container
            }
        }

        // 4. Fallback: monospace code or JSON editor; long text gets a scratch-file link
        val fallbackContent = if (isJson(finalDetails)) {
            ToolRenderers.jsonEditor(prettyJson(finalDetails), project)
        } else {
            ToolRenderers.codeOrScratchPanel(finalDetails)
        }
        container.add(fallbackContent)

        return container
    }

    // ── Helpers ────────────────────────────────────────────────────

    private fun escJs(s: String) =
        s.replace("\\", "\\\\").replace("'", "\\'").replace("`", "\\`").replace("\n", "\\n").replace("\r", "\\r")

    private fun esc(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;").replace("`", "&#96;")

    private fun b64(s: String): String = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
    private fun timestamp(): String = java.time.Instant.now().toString()

    private fun displayTs(isoOrLegacy: String): String {
        return try {
            val zdt = java.time.Instant.parse(isoOrLegacy).atZone(java.time.ZoneId.systemDefault())
            "%02d:%02d".format(zdt.hour, zdt.minute)
        } catch (_: Exception) {
            isoOrLegacy
        }
    }

    private fun displayTsSeparator(isoOrLegacy: String): String {
        return try {
            val zdt = java.time.Instant.parse(isoOrLegacy).atZone(java.time.ZoneId.systemDefault())
            java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm").format(zdt)
        } catch (_: Exception) {
            isoOrLegacy
        }
    }

    private fun domId(id: String) = id.replace(Regex("[^a-zA-Z0-9_-]"), "_")

    // ── Theme ──────────────────────────────────────────────────────

    private fun buildCssVars(): String = ChatTheme.buildCssVars(McpServerSettings.getInstance(project))

    private fun updateThemeColors() {
        val vars = buildCssVars().replace("'", "\\'")
        executeJs("document.documentElement.style.cssText='$vars'")
        val panelBg = com.intellij.util.ui.JBUI.CurrentTheme.ToolWindow.background()
        browser?.setPageBackgroundColor("rgb(${panelBg.red},${panelBg.green},${panelBg.blue})")
    }

    // ── Permission requests ────────────────────────────────────────

    fun handleWebPermissionResponse(data: String) {
        val colonIdx = data.indexOf(':')
        if (colonIdx > 0) {
            val reqId = data.substring(0, colonIdx)
            val response = when (data.substring(colonIdx + 1)) {
                "once" -> com.github.catatafishen.ideagentforcopilot.bridge.PermissionResponse.ALLOW_ONCE
                "session" -> com.github.catatafishen.ideagentforcopilot.bridge.PermissionResponse.ALLOW_SESSION
                "always" -> com.github.catatafishen.ideagentforcopilot.bridge.PermissionResponse.ALLOW_ALWAYS
                else -> com.github.catatafishen.ideagentforcopilot.bridge.PermissionResponse.DENY
            }
            pendingPermissionCallbacks.remove(reqId)?.invoke(response)
        }
    }

    override fun showPermissionRequest(
        reqId: String,
        toolDisplayName: String,
        description: String,
        onRespond: (com.github.catatafishen.ideagentforcopilot.bridge.PermissionResponse) -> Unit
    ) {
        pendingPermissionCallbacks[reqId] = onRespond
        val safeId = escJs(reqId)
        val safeName = escJs(toolDisplayName)
        val safeDesc = escJs(description)
        val turnId = currentTurnId.ifEmpty { "t${turnCounter++}".also { currentTurnId = it } }
        executeJs("window.showPermissionRequest('$turnId','main','$safeId','$safeName','$safeDesc');")
    }

    override fun showAskUserRequest(
        reqId: String,
        question: String,
        options: List<String>,
        onRespond: (String) -> Unit
    ) {
        clearPendingAskUserRequest(null)
        pendingAskUserCallbacks[reqId] = onRespond
        activeAskUserRequestId = reqId

        val safeId = escJs(reqId)
        val safeQuestion = escJs(question)
        val optionJson = options.joinToString(",") { "'${escJs(it)}'" }
        val turnId = currentTurnId.ifEmpty { "t${turnCounter++}".also { currentTurnId = it } }
        executeJs("window.showAskUserRequest('$turnId','main','$safeId','$safeQuestion',[$optionJson]);")
        ChatWebServer.getInstance(project)?.pushNotification("Agent needs your input", question.take(100))
    }

    override fun hasPendingAskUserRequest(): Boolean = activeAskUserRequestId != null

    override fun consumePendingAskUserResponse(response: String): Boolean {
        val reqId = activeAskUserRequestId ?: return false
        if (response.isBlank()) return false

        val callback = pendingAskUserCallbacks.remove(reqId) ?: return false
        activeAskUserRequestId = null
        disableQuickReplies()
        addPromptEntry(response, null)
        callback.invoke(response)
        return true
    }

    override fun clearPendingAskUserRequest(reqId: String?) {
        val activeId = activeAskUserRequestId
        if (reqId != null && activeId != null && reqId != activeId) return
        if (activeId != null) {
            pendingAskUserCallbacks.remove(activeId)
        }
        activeAskUserRequestId = null
        disableQuickReplies()
    }

    override fun showNudgeBubble(id: String, text: String) {
        executeJs("ChatController.showNudgeBubble('${escJs(id)}','${escJs(text)}');")
    }

    override fun resolveNudgeBubble(id: String) {
        executeJs("ChatController.resolveNudgeBubble('${escJs(id)}');")
    }

    override fun removeNudgeBubble(id: String) {
        executeJs("ChatController.removeNudgeBubble('${escJs(id)}');")
    }

    override fun showQueuedMessage(id: String, text: String) {
        executeJs("ChatController.showQueuedMessage('${escJs(id)}','${escJs(text)}');")
    }

    override fun removeQueuedMessage(id: String) {
        executeJs("ChatController.removeQueuedMessage('${escJs(id)}');")
    }

    override fun removeQueuedMessageByText(text: String) {
        executeJs("ChatController.removeQueuedMessageByText('${escJs(text)}');")
    }

    // ── Open in scratch file ─────────────────────────────────────────

    private fun handleOpenScratch(data: String) {
        val newlineIdx = data.indexOf('\n')
        val lang = if (newlineIdx > 0) data.substring(0, newlineIdx).trim() else ""
        val content = if (newlineIdx >= 0) data.substring(newlineIdx + 1) else data

        val ext = langToExtension(lang)
        val name = "snippet.$ext"
        val log = com.intellij.openapi.diagnostic.Logger.getInstance(ChatConsolePanel::class.java)

        ApplicationManager.getApplication().invokeLater {
            try {
                val scratchService = com.intellij.ide.scratch.ScratchFileService.getInstance()
                val scratchRoot = com.intellij.ide.scratch.ScratchRootType.getInstance()

                // Explicit Computable type needed: runWriteAction is overloaded (Computable vs ThrowableComputable)
                @Suppress("RedundantCast")
                val file = ApplicationManager.getApplication().runWriteAction(
                    com.intellij.openapi.util.Computable<com.intellij.openapi.vfs.VirtualFile?> {
                        try {
                            val f = scratchService.findFile(
                                scratchRoot, name,
                                com.intellij.ide.scratch.ScratchFileService.Option.create_new_always
                            )
                            if (f != null) {
                                f.getOutputStream(null).use { out ->
                                    out.write(content.toByteArray(Charsets.UTF_8))
                                }
                            }
                            f
                        } catch (e: java.io.IOException) {
                            log.warn("Failed to create scratch file", e)
                            null
                        }
                    }
                )

                if (file != null) {
                    com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                        .openFile(file, true)
                }
            } catch (e: Exception) {
                log.warn("Failed to open scratch file from chat", e)
            }
        }
    }

    private fun langToExtension(lang: String): String =
        ScratchTypeSettings.getInstance().resolve(lang)

    private fun handleShowToolPopup(toolDomId: String) {
        val entry = toolCallEntries[toolDomId]
        val baseName = toolCallNames[toolDomId]
        val chipTitle = toolChipTitle(baseName, entry?.arguments)
        val kind = entry?.kind ?: "other"
        val toolDef = baseName?.let { toolRegistry?.findById(it) }
        val mcpDescription = if (toolDef != null && !toolDef.isBuiltIn()) toolDef.description() else null
        val autoDenied = entry?.autoDenied ?: false
        val denialReason = entry?.denialReason
        val resultPanel =
            renderToolResultPanel(
                baseName,
                entry?.status,
                entry?.result,
                entry?.arguments,
                entry?.description,
                autoDenied,
                denialReason
            )
        val arguments = entry?.arguments
        val paramsPanel = if (!arguments.isNullOrBlank()) {
            ToolRenderers.jsonEditor(prettyJson(arguments), project)
        } else null
        ApplicationManager.getApplication().invokeLater {
            ToolCallPopup.show(
                project,
                chipTitle,
                kind,
                paramsPanel,
                resultPanel,
                mcpDescription,
                autoDenied,
                denialReason
            )
        }
    }

    private fun isJson(text: String): Boolean =
        (text.startsWith("{") && text.endsWith("}")) || (text.startsWith("[") && text.endsWith("]"))

    /** Produces a chip-style title matching the JS toolDisplayName() logic. */
    private fun toolChipTitle(baseName: String?, arguments: String?): String {
        if (baseName == null) return "Tool Call"
        val clean = baseName.trim('\'', '"')
        val subtitle = formatToolSubtitle(clean, arguments)
        val toolDef = toolRegistry?.findById(clean)
        val display = toolDef?.displayName() ?: TOOL_DISPLAY_INFO[clean]?.displayName ?: clean
        return if (subtitle != null) "$display — $subtitle" else display
    }

    private fun prettyJson(json: String): String {
        return try {
            val el = com.google.gson.JsonParser.parseString(json)
            com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(el)
        } catch (_: Exception) {
            json
        }
    }

    private fun extractFilePathFromArgs(toolName: String, arguments: String?): String? {
        if (arguments.isNullOrBlank()) return null
        try {
            val json = JsonParser.parseString(arguments)
            if (!json.isJsonObject) return null
            val obj = json.asJsonObject
            // Check common file path parameter names
            for (key in listOf("path", "file", "filename", "filepath")) {
                if (obj.has(key) && obj.get(key).isJsonPrimitive) {
                    return obj.get(key).asString
                }
            }
        } catch (_: Exception) {
            // Ignore parse errors
        }
        return null
    }

    private fun buildInitialPage(): String {
        val cssVars = buildCssVars()
        val fileHandler = openFileQuery!!.inject("href")
        val bridgeJs = """
            window._bridge = {
                openFile: function(href) { $fileHandler },
                openUrl: function(url) { $openUrlBridgeJs },
                setCursor: function(c) { $cursorBridgeJs },
                loadMore: function() { $loadMoreBridgeJs },
                quickReply: function(text) { $quickReplyBridgeJs },
                permissionResponse: function(data) { $permissionResponseBridgeJs },
                openScratch: function(lang, content) { $openScratchBridgeJs },
                showToolPopup: function(id) { $showToolPopupBridgeJs },
                cancelNudge: function(id) { $cancelNudgeBridgeJs },
                cancelQueuedMessage: function(id, text) { $cancelQueuedMessageBridgeJs }
            };
        """.trimIndent()
        val css = loadResource("/chat/chat.css")
        val js = loadResource("/chat/chat-components.js")
        return """<!DOCTYPE html><html lang="en"><head><meta charset="utf-8">
<style>$css</style>
<style>:root { $cssVars }</style></head><body>
<chat-container></chat-container>
<script>$bridgeJs</script>
<script>$js</script></body></html>"""
    }

    private fun loadResource(path: String): String =
        javaClass.getResourceAsStream(path)?.bufferedReader()?.readText() ?: error("Missing resource: $path")
}
