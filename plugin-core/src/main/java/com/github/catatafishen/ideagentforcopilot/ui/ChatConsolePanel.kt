package com.github.catatafishen.ideagentforcopilot.ui

import com.github.catatafishen.ideagentforcopilot.settings.ScratchTypeSettings
import com.github.catatafishen.ideagentforcopilot.ui.renderers.ArgumentAwareRenderer
import com.github.catatafishen.ideagentforcopilot.ui.renderers.ToolRenderers
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.io.File
import java.util.*
import javax.swing.JComponent
import javax.swing.UIManager

/**
 * Chat panel — web-component-based implementation.
 * All rendering delegated to JS ChatController; Kotlin manages data model and bridge.
 */
class ChatConsolePanel(private val project: Project) : JBPanel<ChatConsolePanel>(BorderLayout()), ChatPanelApi {

    override val component: JComponent get() = this
    override var onQuickReply: ((String) -> Unit)? = null
    override var onStatusMessage: ((type: String, message: String) -> Unit)? = null

    // ── Data model (same types as V1 for serialization compat) ─────
    private val entries = mutableListOf<EntryData>()
    private var currentTextData: EntryData.Text? = null
    private var currentThinkingData: EntryData.Thinking? = null
    private var nextSubAgentColor = 0
    private var turnCounter = 0
    private var currentTurnId = ""
    private var toolJustCompleted = false
    private val toolCallNames = mutableMapOf<String, String>() // domId → tool baseName
    private val toolCallEntries = mutableMapOf<String, EntryData.ToolCall>() // domId → entry
    private val toolRegistry = com.github.catatafishen.ideagentforcopilot.services.ToolRegistry.getInstance(project)

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

    @Volatile
    private var htmlPageFuture: java.util.concurrent.CompletableFuture<String>? = null
    private val deferredRestoreJson = mutableListOf<com.google.gson.JsonElement>()
    private val pendingPermissionCallbacks =
        java.util.concurrent.ConcurrentHashMap<String, (com.github.catatafishen.ideagentforcopilot.bridge.PermissionResponse) -> Unit>()

    // Periodic JCEF repaint during streaming to avoid partial-update artifacts
    private val repaintTimer = javax.swing.Timer(150) {
        browser?.cefBrowser?.invalidate()
    }.apply { isRepeats = true }

    // ── Swing fallback ─────────────────────────────────────────────
    private val fallbackArea: JBTextArea?

    companion object {
        private const val SA_COLOR_COUNT = 8
        private val QUICK_REPLY_TAG_REGEX = Regex("\\[quick-reply:\\s*([^]]+)]")

        /** Active panels keyed by project — used by MCP tool to retrieve page HTML. */
        private val instances = java.util.concurrent.ConcurrentHashMap<Project, ChatConsolePanel>()

        fun getInstance(project: Project): ChatConsolePanel? = instances[project]

        private const val FAILED_SPAN = "<span style='color:var(--error)'>✖ Failed</span>"
        private const val DEFAULT_AGENT_TYPE = "general-purpose"

        private val LINK_COLOR_KEY = "Link.activeForeground"
        private val USER_COLOR = JBColor(Color(0x28, 0x6B, 0xC0), Color(86, 156, 214))
        private val AGENT_COLOR = JBColor(Color(0x2A, 0x80, 0x2A), Color(150, 200, 150))
        private val TOOL_COLOR = JBColor(Color(0x6A, 0x4C, 0xB0), Color(180, 160, 220))
        private val THINK_COLOR = JBColor(Color(0x68, 0x68, 0x68), Color(176, 176, 176))
        private val ERROR_COLOR = JBColor(Color(0xC7, 0x22, 0x22), Color(199, 34, 34))
        private val SA_COLORS = arrayOf(
            JBColor(Color(0x1E, 0x88, 0x7E), Color(38, 166, 154)),
            JBColor(Color(0xC8, 0x8E, 0x32), Color(240, 173, 78)),
            JBColor(Color(0x7B, 0x5D, 0xAE), Color(156, 120, 216)),
            JBColor(Color(0xB8, 0x58, 0x78), Color(216, 112, 147)),
            JBColor(Color(0x3B, 0x9F, 0xB8), Color(91, 192, 222)),
            JBColor(Color(0x68, 0x9F, 0x30), Color(139, 195, 74)),
            JBColor(Color(0xC6, 0x50, 0x50), Color(229, 115, 115)),
            JBColor(Color(0x28, 0x6B, 0xC0), Color(86, 156, 214)),
        )

        // Tool kind chip colors — semantic categories
        private val KIND_READ_COLOR = JBColor(Color(0x3A, 0x95, 0x95), Color(100, 185, 185))
        private val KIND_EDIT_COLOR = JBColor(Color(0xA0, 0x7A, 0x3A), Color(205, 155, 95))
        private val KIND_EXECUTE_COLOR = JBColor(Color(0x4A, 0x90, 0x4A), Color(130, 190, 130))
        private val KIND_SEARCH_COLOR = JBColor(Color(0x3A, 0x95, 0x95), Color(100, 185, 185))
        private val KIND_THINK_COLOR = JBColor(Color(0x7A, 0x70, 0xA8), Color(170, 155, 210))
        private val KIND_OTHER_COLOR = JBColor(Color(0x78, 0x7C, 0x80), Color(160, 165, 170))
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
            loadMoreQuery.addHandler { loadMoreEntries(); null }
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
                // data format: "reqId:deny", "reqId:once", or "reqId:session"
                val colonIdx = data.lastIndexOf(':')
                if (colonIdx > 0) {
                    val reqId = data.substring(0, colonIdx)
                    val response = when (data.substring(colonIdx + 1)) {
                        "once" -> com.github.catatafishen.ideagentforcopilot.bridge.PermissionResponse.ALLOW_ONCE
                        "session" -> com.github.catatafishen.ideagentforcopilot.bridge.PermissionResponse.ALLOW_SESSION
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
        executeJs("ChatController.addUserMessage('${escJs(text)}','$ts','$encodedBubble');ChatController.showWorkingIndicator()")
    }

    override fun startStreaming() {
        repaintTimer.start()
    }

    override fun setPromptStats(modelId: String, multiplier: String) {
        val short = escJs(modelId.substringAfterLast("/").take(30))
        executeJs("ChatController.setPromptStats('$short','${escJs(multiplier)}')")
    }

    override fun setCurrentModel(modelId: String) {
        executeJs("ChatController.setCurrentModel('${escJs(modelId)}')")
    }

    override fun setCurrentProfile(profileId: String) {
        executeJs("ChatController.setCurrentProfile('${escJs(profileId)}')")
    }

    override fun addContextFilesEntry(files: List<Pair<String, String>>) {
        entries.add(EntryData.ContextFiles(files))
    }

    override fun appendThinkingText(text: String) {
        maybeStartNewSegment()
        if (currentThinkingData == null) {
            currentThinkingData = EntryData.Thinking().also { entries.add(it) }
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
            currentTextData = EntryData.Text().also { entries.add(it) }
        }
        currentTextData!!.raw.append(text)
        executeJs("ChatController.appendAgentText('$currentTurnId','main','${escJs(text)}')")
        fallbackArea?.let { ApplicationManager.getApplication().invokeLater { it.append(text) } }
    }

    override fun addToolCallEntry(id: String, title: String, arguments: String?, kind: String?) {
        finalizeCurrentText()
        val resolvedKind = kind ?: "other"
        val entry = EntryData.ToolCall(title, arguments, resolvedKind)
        entries.add(entry)
        val did = domId(id)
        val baseName = stripMcpPrefix(title)
        toolCallNames[did] = baseName
        toolCallEntries[did] = entry
        val info = TOOL_DISPLAY_INFO[baseName]
        val displayName = info?.displayName ?: title.replaceFirstChar { it.uppercaseChar() }
        val short = formatToolSubtitle(baseName, arguments)
        val label = if (short != null) "$displayName — $short" else displayName
        val hasCustomRenderer = ToolRenderers.hasRenderer(baseName, toolRegistry)
        val paramsJson = if (!arguments.isNullOrBlank() && !hasCustomRenderer) escJs(arguments) else ""
        val safeKind = escJs(resolvedKind)
        executeJs("ChatController.addToolCall('$currentTurnId','main','$did','${escJs(label)}','$paramsJson','$safeKind')")
    }

    override fun updateToolCall(id: String, status: String, details: String?) {
        val did = domId(id)
        toolCallEntries[did]?.let { it.result = details; it.status = status }
        val failed = if (status == "failed") "failed" else "completed"
        executeJs("ChatController.updateToolCall('$did','$failed','$failed')")
        toolJustCompleted = true
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
        val baseName = stripMcpPrefix(title)
        val resolvedKind = kind ?: "other"
        val entry = EntryData.ToolCall(title, arguments, resolvedKind)
        toolCallNames[toolDid] = baseName
        toolCallEntries[toolDid] = entry
        val info = TOOL_DISPLAY_INFO[baseName]
        val displayName = info?.displayName ?: title.replaceFirstChar { it.uppercaseChar() }
        val short = formatToolSubtitle(baseName, arguments)
        val label = if (short != null) "$displayName — $short" else displayName
        val hasCustomRenderer = ToolRenderers.hasRenderer(baseName, toolRegistry)
        val paramsJson = if (!arguments.isNullOrBlank() && !hasCustomRenderer) escJs(arguments) else ""
        val safeKind = escJs(resolvedKind)
        executeJs("ChatController.addSubAgentToolCall('$saDid','$toolDid','${escJs(label)}','$paramsJson','$safeKind')")
    }

    /** Update a sub-agent internal tool call (no segment break). */
    override fun updateSubAgentToolCall(toolId: String, status: String, details: String?) {
        val did = domId(toolId)
        toolCallEntries[did]?.let { it.result = details; it.status = status }
        val failed = if (status == "failed") "failed" else "completed"
        executeJs("ChatController.updateToolCall('$did','$failed','$failed')")
    }

    override fun addSubAgentEntry(
        id: String, agentType: String, description: String, prompt: String?,
        initialResult: String?, initialStatus: String?
    ) {
        maybeStartNewSegment()
        finalizeCurrentText()
        val colorIndex = nextSubAgentColor++ % SA_COLOR_COUNT
        val entry =
            EntryData.SubAgent(agentType, description, prompt, colorIndex = colorIndex, callId = id)
        if (initialResult != null) {
            entry.result = initialResult; entry.status = initialStatus
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
        if (!initialResult.isNullOrBlank() || initialStatus == "completed" || initialStatus == "failed") {
            val resultHtml =
                if (!initialResult.isNullOrBlank()) markdownToHtml(initialResult) else if (initialStatus == "completed") "Completed" else FAILED_SPAN
            val encoded = b64(resultHtml)
            executeJs("ChatController.updateSubAgent('$did','${initialStatus ?: "completed"}',b64('$encoded'))")
        }
    }

    override fun updateSubAgentResult(id: String, status: String, result: String?) {
        val entry = entries.filterIsInstance<EntryData.SubAgent>().find { it.callId == id }
            ?: entries.filterIsInstance<EntryData.SubAgent>().lastOrNull()
        entry?.let { it.result = result; it.status = status }
        val did = domId(id)
        val resultHtml =
            if (!result.isNullOrBlank()) markdownToHtml(result) else if (status == "completed") "Completed" else FAILED_SPAN
        val encoded = b64(resultHtml)
        executeJs("ChatController.updateSubAgent('$did','$status',b64('$encoded'))")
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
        executeJs("ChatController.addSessionSeparator('${escJs(timestamp)}', '${escJs(agent)}')")
    }

    override fun showPlaceholder(text: String) {
        entries.clear(); deferredRestoreJson.clear()
        currentTextData = null; currentThinkingData = null; nextSubAgentColor = 0
        turnCounter = 0; currentTurnId = ""; toolJustCompleted = false
        executeJs("ChatController.showPlaceholder('${escJs(text)}')")
        fallbackArea?.let { ApplicationManager.getApplication().invokeLater { it.text = text } }
    }

    override fun clear() {
        entries.clear(); deferredRestoreJson.clear()
        currentTextData = null; currentThinkingData = null; nextSubAgentColor = 0
        turnCounter = 0; currentTurnId = ""; toolJustCompleted = false
        executeJs("ChatController.clear()")
        fallbackArea?.let { ApplicationManager.getApplication().invokeLater { it.text = "" } }
    }

    override fun finishResponse(toolCallCount: Int, modelId: String, multiplier: String) {
        repaintTimer.stop()
        toolJustCompleted = false
        finalizeCurrentText()
        collapseThinking()
        val statsJson = """{"tools":$toolCallCount,"model":"${escJs(modelId)}","mult":"${escJs(multiplier)}"}"""
        executeJs("ChatController.finalizeTurn('$currentTurnId',$statsJson)")
        ApplicationManager.getApplication().invokeLater { browser?.component?.repaint() }
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
        executeJs("ChatController.cancelAllRunning()")
    }

    // ── Conversation export ────────────────────────────────────────

    private val exporter: ConversationExporter get() = ConversationExporter(entries)
    override fun getConversationText(): String = exporter.getConversationText()
    override fun getCompressedSummary(maxChars: Int): String = exporter.getCompressedSummary(maxChars)
    override fun getConversationHtml(): String = exporter.getConversationHtml()

    override fun getLastResponseText(): String =
        entries.filterIsInstance<EntryData.Text>().lastOrNull()?.raw?.toString() ?: ""

    override fun serializeEntries(): String {
        val arr = com.google.gson.JsonArray()
        for (e in entries) {
            val obj = com.google.gson.JsonObject()
            when (e) {
                is EntryData.Prompt -> {
                    obj.addProperty("type", "prompt"); obj.addProperty("text", e.text)
                    if (e.timestamp.isNotEmpty()) obj.addProperty("ts", e.timestamp)
                    if (!e.contextFiles.isNullOrEmpty()) {
                        val fa = com.google.gson.JsonArray()
                        e.contextFiles.forEach { (name, path, line) ->
                            val fo = com.google.gson.JsonObject()
                            fo.addProperty("name", name)
                            fo.addProperty("path", path)
                            fo.addProperty("line", line)
                            fa.add(fo)
                        }
                        obj.add("ctxFiles", fa)
                    }
                }

                is EntryData.Text -> {
                    obj.addProperty("type", "text"); obj.addProperty("raw", e.raw.toString())
                }

                is EntryData.Thinking -> {
                    obj.addProperty("type", "thinking"); obj.addProperty("raw", e.raw.toString())
                }

                is EntryData.ToolCall -> {
                    obj.addProperty("type", "tool"); obj.addProperty("title", e.title); obj.addProperty(
                        "args",
                        e.arguments ?: ""
                    )
                    obj.addProperty("kind", e.kind)
                    if (!e.result.isNullOrEmpty()) obj.addProperty("result", e.result)
                    if (!e.status.isNullOrEmpty()) obj.addProperty("status", e.status)
                }

                is EntryData.SubAgent -> {
                    obj.addProperty("type", "subagent"); obj.addProperty("agentType", e.agentType)
                    obj.addProperty("description", e.description); obj.addProperty("prompt", e.prompt ?: "")
                    obj.addProperty("result", e.result ?: ""); obj.addProperty("status", e.status ?: "")
                    obj.addProperty("colorIndex", e.colorIndex)
                }

                is EntryData.ContextFiles -> {
                    obj.addProperty("type", "context")
                    val fa = com.google.gson.JsonArray()
                    e.files.forEach { f ->
                        val fo = com.google.gson.JsonObject(); fo.addProperty(
                        "name",
                        f.first
                    ); fo.addProperty("path", f.second); fa.add(fo)
                    }
                    obj.add("files", fa)
                }

                is EntryData.Status -> {
                    obj.addProperty("type", "status"); obj.addProperty("icon", e.icon); obj.addProperty(
                        "message",
                        e.message
                    )
                }

                is EntryData.SessionSeparator -> {
                    obj.addProperty("type", "separator"); obj.addProperty(
                        "timestamp",
                        e.timestamp
                    ); obj.addProperty("agent", e.agent)
                }
            }
            arr.add(obj)
        }
        return arr.toString()
    }

    override fun restoreEntries(json: String) {
        entries.clear(); deferredRestoreJson.clear()
        currentTextData = null; currentThinkingData = null; nextSubAgentColor = 0
        turnCounter = 0; currentTurnId = ""
        val arr = try {
            com.google.gson.JsonParser.parseString(json).asJsonArray
        } catch (_: Exception) {
            return
        }
        if (arr.size() == 0) return

        // Split: show last N prompt turns immediately, defer the rest
        val turnsToShow = 5
        val splitAt = findSplitIndex(arr, turnsToShow)
        if (splitAt > 0) {
            for (i in 0 until splitAt) deferredRestoreJson.add(arr[i])
            executeJs("ChatController.showLoadMore(${deferredRestoreJson.size})")
        }
        for (i in splitAt until arr.size()) {
            val obj = arr[i].asJsonObject
            addEntryFromJson(obj)
            renderRestoredEntry(obj)
        }
    }

    private fun findSplitIndex(arr: com.google.gson.JsonArray, turnsFromEnd: Int): Int {
        var promptCount = 0
        for (i in arr.size() - 1 downTo 0) {
            if (arr[i].asJsonObject["type"]?.asString == "prompt") promptCount++
            if (promptCount >= turnsFromEnd) return i
        }
        return 0
    }

    private fun addEntryFromJson(obj: com.google.gson.JsonObject) {
        when (obj["type"]?.asString) {
            "prompt" -> {
                val ctxFiles = obj["ctxFiles"]?.asJsonArray?.map { f ->
                    val fo = f.asJsonObject
                    Triple(fo["name"]?.asString ?: "", fo["path"]?.asString ?: "", fo["line"]?.asInt ?: 0)
                }
                entries.add(EntryData.Prompt(obj["text"]?.asString ?: "", obj["ts"]?.asString ?: "", ctxFiles))
            }

            "text" -> entries.add(EntryData.Text(StringBuilder(obj["raw"]?.asString ?: "")))
            "thinking" -> entries.add(EntryData.Thinking(StringBuilder(obj["raw"]?.asString ?: "")))
            "tool" -> entries.add(
                EntryData.ToolCall(
                    obj["title"]?.asString ?: "",
                    obj["args"]?.asString,
                    obj["kind"]?.asString ?: "other",
                    obj["result"]?.asString,
                    obj["status"]?.asString
                )
            )

            "subagent" -> {
                val ci = obj["colorIndex"]?.asInt ?: (nextSubAgentColor++ % SA_COLOR_COUNT)
                entries.add(
                    EntryData.SubAgent(
                        obj["agentType"]?.asString ?: DEFAULT_AGENT_TYPE,
                        obj["description"]?.asString ?: "",
                        obj["prompt"]?.asString?.ifEmpty { null },
                        obj["result"]?.asString?.ifEmpty { null },
                        obj["status"]?.asString?.ifEmpty { null } ?: "completed",
                        ci
                    ))
            }

            "context" -> {
                val files = mutableListOf<Pair<String, String>>()
                obj["files"]?.asJsonArray?.forEach { f ->
                    val fo = f.asJsonObject
                    files.add(Pair(fo["name"]?.asString ?: "", fo["path"]?.asString ?: ""))
                }
                entries.add(EntryData.ContextFiles(files))
            }

            "status" -> entries.add(
                EntryData.Status(
                    obj["icon"]?.asString ?: "ℹ",
                    obj["message"]?.asString ?: ""
                )
            )

            "separator" -> entries.add(
                EntryData.SessionSeparator(
                    obj["timestamp"]?.asString ?: "",
                    obj["agent"]?.asString ?: ""
                )
            )
        }
    }

    private fun renderRestoredEntry(obj: com.google.gson.JsonObject) {
        when (obj["type"]?.asString) {
            "prompt" -> {
                currentTurnId = "t${turnCounter++}"
                val text = obj["text"]?.asString ?: ""
                val ts = obj["ts"]?.asString ?: ""
                val ctxFiles = obj["ctxFiles"]?.asJsonArray?.map { f ->
                    val fo = f.asJsonObject
                    Triple(fo["name"]?.asString ?: "", fo["path"]?.asString ?: "", fo["line"]?.asInt ?: 0)
                }
                val encodedBubble = if (!ctxFiles.isNullOrEmpty()) b64(buildRestoredBubbleHtml(text, ctxFiles)) else ""
                executeJs("ChatController.addUserMessage('${escJs(text)}','$ts','$encodedBubble')")
            }

            "text" -> {
                if (currentTurnId.isEmpty()) currentTurnId = "t${turnCounter++}"
                val raw = obj["raw"]?.asString ?: ""
                if (raw.isNotBlank()) {
                    val clean = raw.replace(QUICK_REPLY_TAG_REGEX, "").trimEnd()
                    val html = markdownToHtml(clean)
                    val encoded = b64(html)
                    executeJs("ChatController.finalizeAgentText('$currentTurnId','main','$encoded')")
                }
                // Reset segment so subsequent tool/text entries get a fresh message element,
                // matching the live flow where newSegment is called between tool results and new text.
                executeJs("ChatController.newSegment('$currentTurnId','main')")
            }

            "thinking" -> {
                if (currentTurnId.isEmpty()) currentTurnId = "t${turnCounter++}"
                val raw = obj["raw"]?.asString ?: ""
                if (raw.isNotBlank()) {
                    executeJs("ChatController.addThinkingText('$currentTurnId','main','${escJs(raw)}');ChatController.collapseThinking('$currentTurnId','main')")
                }
            }

            "tool" -> {
                if (currentTurnId.isEmpty()) currentTurnId = "t${turnCounter++}"
                val title = obj["title"]?.asString ?: ""
                val args = obj["args"]?.asString
                val baseName = stripMcpPrefix(title)
                val info = TOOL_DISPLAY_INFO[baseName]
                val displayName = info?.displayName ?: title.replaceFirstChar { it.uppercaseChar() }
                val short = formatToolSubtitle(baseName, args)
                val label = if (short != null) "$displayName — $short" else displayName
                val did = "restored-tool-${entries.size}"
                val kind = obj["kind"]?.asString ?: "other"
                val result = obj["result"]?.asString
                val status = obj["status"]?.asString ?: "completed"
                toolCallNames[did] = baseName
                toolCallEntries[did] = EntryData.ToolCall(title, args, kind, result, status)
                val hasCustomRenderer = ToolRenderers.hasRenderer(baseName, toolRegistry)
                val paramsJson = if (!args.isNullOrBlank() && !hasCustomRenderer) escJs(args) else ""
                executeJs(
                    "ChatController.addToolCall('$currentTurnId','main','$did','${escJs(label)}','$paramsJson','${
                        escJs(kind)
                    }');ChatController.updateToolCall('$did','$status','$status')"
                )
            }

            "subagent" -> {
                if (currentTurnId.isEmpty()) currentTurnId = "t${turnCounter++}"
                val agentType = obj["agentType"]?.asString ?: DEFAULT_AGENT_TYPE
                val saInfo = SUB_AGENT_INFO[agentType]
                val displayName = saInfo?.displayName ?: agentType.replaceFirstChar { it.uppercaseChar() }
                val prompt = obj["prompt"]?.asString?.ifEmpty { null }
                val result = obj["result"]?.asString?.ifEmpty { null }
                val status = obj["status"]?.asString?.ifEmpty { null } ?: "completed"
                val ci = obj["colorIndex"]?.asInt ?: 0
                val did = "restored-sa-${entries.size}"
                val promptText = prompt ?: (obj["description"]?.asString ?: "")
                executeJs(
                    "ChatController.addSubAgent('$currentTurnId','main','$did','${escJs(displayName)}',$ci,'${
                        escJs(
                            promptText
                        )
                    }')"
                )
                val resultHtml =
                    if (!result.isNullOrBlank()) markdownToHtml(result) else if (status == "completed") "Completed" else FAILED_SPAN
                val encoded = b64(resultHtml)
                executeJs("ChatController.updateSubAgent('$did','$status',b64('$encoded'))")
                // Start a new segment so subsequent entries are appended after the
                // subagent result element in the DOM, not inside the preceding message.
                executeJs("ChatController.newSegment('$currentTurnId','main')")
            }

            "status" -> {
                // Status entries are shown via Swing banner, not in the chat HTML.
                // During history replay we skip them — they are transient notifications.
            }

            "separator" -> {
                currentTurnId = ""
                val ts = obj["timestamp"]?.asString ?: ""
                val ag = obj["agent"]?.asString ?: ""
                executeJs("ChatController.addSessionSeparator('${escJs(ts)}', '${escJs(ag)}')")
            }
        }
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

    private fun loadMoreEntries() {
        if (deferredRestoreJson.isEmpty()) return
        val turnsToLoad = 3
        var promptCount = 0
        var start = deferredRestoreJson.size - 1
        while (start >= 0) {
            if (deferredRestoreJson[start].asJsonObject["type"]?.asString == "prompt") promptCount++
            if (promptCount >= turnsToLoad) break
            start--
        }
        if (start < 0) start = 0
        val batch = deferredRestoreJson.subList(start, deferredRestoreJson.size)
        val entries = batch.map { it.asJsonObject }
        for (obj in entries) addEntryFromJson(obj)
        val html = renderBatchGroupedHtml(entries)
        batch.clear()
        if (html.isNotEmpty()) {
            val encoded = b64(html)
            executeJs("ChatController.restoreBatch('$encoded')")
        }
        if (deferredRestoreJson.isEmpty()) {
            executeJs("ChatController.removeLoadMore()")
        } else {
            executeJs("ChatController.showLoadMore(${deferredRestoreJson.size})")
        }
    }

    private var batchIdCounter = 0

    private fun renderBatchGroupedHtml(entries: List<com.google.gson.JsonObject>): String {
        val sb = StringBuilder()
        var i = 0
        while (i < entries.size) {
            val obj = entries[i]
            when (obj["type"]?.asString) {
                "prompt" -> {
                    val text = obj["text"]?.asString ?: ""
                    val ts = obj["ts"]?.asString ?: ""
                    val ctxArr = obj["ctxFiles"]?.asJsonArray
                    val ctxFiles = ctxArr?.map { f ->
                        val fo = f.asJsonObject
                        Triple(fo["name"]?.asString ?: "", fo["path"]?.asString ?: "", fo["line"]?.asInt ?: 0)
                    }
                    sb.append("<chat-message type='user'>")
                    sb.append("<message-meta><span class='ts'>${esc(ts)}</span></message-meta>")
                    sb.append("<message-bubble type='user'>")
                    if (!ctxFiles.isNullOrEmpty()) {
                        sb.append(buildRestoredBubbleHtml(text, ctxFiles))
                    } else {
                        sb.append(esc(text))
                    }
                    sb.append("</message-bubble>")
                    sb.append("</chat-message>")
                    i++
                }

                "separator" -> {
                    sb.append("<session-divider timestamp='${esc(obj["timestamp"]?.asString ?: "")}' agent='${esc(obj["agent"]?.asString ?: "")}'></session-divider>")
                    i++
                }

                "status" -> {
                    // Status entries are transient Swing banners — skip in HTML rendering.
                    i++
                }

                "context" -> {
                    i++
                }

                else -> {
                    // Agent turn: group consecutive agent entries into one chat-message
                    sb.append("<chat-message type='agent'>")
                    val metaChips = StringBuilder()
                    val detailsContent = StringBuilder()
                    val afterDetails = StringBuilder()
                    while (i < entries.size) {
                        val e = entries[i]
                        val t = e["type"]?.asString
                        if (t == "prompt" || t == "separator" || t == "status") break
                        when (t) {
                            "thinking" -> {
                                val raw = e["raw"]?.asString ?: ""
                                if (raw.isNotBlank()) {
                                    val id = "batch-think-${batchIdCounter++}"
                                    metaChips.append("<thinking-chip status='complete' data-chip-for='$id'></thinking-chip>")
                                    detailsContent.append(
                                        "<thinking-block id='$id' class='thinking-section turn-hidden'><div class='thinking-content'>${
                                            esc(
                                                raw
                                            )
                                        }</div></thinking-block>"
                                    )
                                }
                            }

                            "tool" -> {
                                val title = e["title"]?.asString ?: ""
                                val args = e["args"]?.asString
                                val kind = e["kind"]?.asString ?: "other"
                                val baseName = stripMcpPrefix(title)
                                val info = TOOL_DISPLAY_INFO[baseName]
                                val displayName = info?.displayName ?: title.replaceFirstChar { it.uppercaseChar() }
                                val short = formatToolSubtitle(baseName, args)
                                val label = if (short != null) "$displayName — $short" else displayName
                                val id = "batch-tool-${batchIdCounter++}"
                                val result = e["result"]?.asString
                                val status = e["status"]?.asString ?: "completed"
                                toolCallNames[id] = baseName
                                toolCallEntries[id] = EntryData.ToolCall(title, args, kind, result, status)
                                val paramsAttr = if (args != null) " data-params='${esc(args)}'" else ""
                                metaChips.append("<tool-chip label='${esc(label)}' status='complete' kind='${esc(kind)}' data-chip-for='$id'$paramsAttr></tool-chip>")
                            }

                            "text" -> {
                                val raw = e["raw"]?.asString ?: ""
                                if (raw.isNotBlank()) {
                                    val clean = raw.replace(QUICK_REPLY_TAG_REGEX, "").trimEnd()
                                    val html = markdownToHtml(clean)
                                    afterDetails.append("<message-bubble>$html</message-bubble>")
                                }
                            }

                            "subagent" -> {
                                val agentType = e["agentType"]?.asString ?: DEFAULT_AGENT_TYPE
                                val saInfo = SUB_AGENT_INFO[agentType]
                                val dn = saInfo?.displayName ?: agentType.replaceFirstChar { it.uppercaseChar() }
                                val result = e["result"]?.asString?.ifEmpty { null }
                                val ci = e["colorIndex"]?.asInt ?: 0
                                val resultHtml = if (!result.isNullOrBlank()) markdownToHtml(result) else "Completed"
                                val id = "batch-sa-${batchIdCounter++}"
                                metaChips.append("<subagent-chip label='${esc(dn)}' status='complete' color-index='$ci' data-chip-for='$id'></subagent-chip>")
                                afterDetails.append("<div id='$id' class='subagent-indent subagent-c$ci turn-hidden'><message-bubble>$resultHtml</message-bubble></div>")
                            }

                            else -> { /* exhaustive: no action for unknown entry types */
                            }
                        }
                        i++
                    }
                    if (metaChips.isNotEmpty()) {
                        sb.append("<message-meta class='show'>$metaChips</message-meta>")
                    }
                    sb.append("<turn-details>$detailsContent</turn-details>")
                    sb.append(afterDetails)
                    sb.append("</chat-message>")
                }
            }
        }
        return sb.toString()
    }

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

    private fun handleFileLink(href: String) {
        if (href.startsWith("gitshow://")) {
            handleGitShowLink(href.removePrefix("gitshow://"))
            return
        }
        val pathAndLine = href.removePrefix("openfile://")
        val (filePath, line) = parsePathAndLine(pathAndLine)
        val normalizedPath = filePath.replace('\\', '/')
        val vf = LocalFileSystem.getInstance().findFileByPath(normalizedPath) ?: return
        ApplicationManager.getApplication().invokeLater {
            OpenFileDescriptor(project, vf, maxOf(0, line - 1), 0).navigate(true)
        }
    }

    /** Splits a path-and-optional-line string, handling Windows drive letters (e.g. C:\...:42). */
    private fun parsePathAndLine(pathAndLine: String): Pair<String, Int> {
        val lastColon = pathAndLine.lastIndexOf(':')
        if (lastColon > 0) {
            val afterColon = pathAndLine.substring(lastColon + 1)
            val lineNum = afterColon.toIntOrNull()
            if (lineNum != null) return Pair(pathAndLine.substring(0, lastColon), lineNum)
        }
        return Pair(pathAndLine, 0)
    }

    private fun handleGitShowLink(hash: String) {
        val log = com.intellij.openapi.diagnostic.Logger.getInstance(ChatConsolePanel::class.java)
        ApplicationManager.getApplication().invokeLater {
            try {
                val repos = git4idea.repo.GitRepositoryManager.getInstance(project).repositories.toList()
                val root = repos.firstOrNull()?.root
                if (root == null) {
                    log.warn("No VCS root found for git commit link $hash")
                    return@invokeLater
                }
                val fullHash = resolveFullHash(hash) ?: hash
                val hashObj = com.intellij.vcs.log.impl.HashImpl.build(fullHash)

                // Refresh VCS log to pick up recent commits
                val vcsLog = com.intellij.vcs.log.impl.VcsProjectLog.getInstance(project)
                val dataManager = vcsLog.dataManager
                dataManager?.refresh(listOf(root))

                // Poll until the commit appears in the VCS log storage, then navigate
                showRevisionWhenIndexed(root, hashObj, attemptsLeft = 25, delayMs = 200)
            } catch (e: Exception) {
                log.warn("Failed to open git commit $hash", e)
            }
        }
    }

    private fun showRevisionWhenIndexed(
        root: com.intellij.openapi.vfs.VirtualFile,
        hash: com.intellij.vcs.log.Hash,
        attemptsLeft: Int,
        delayMs: Long,
    ) {
        val dm = com.intellij.vcs.log.impl.VcsProjectLog.getInstance(project).dataManager
        val commitId = com.intellij.vcs.log.CommitId(hash, root)
        val indexed = dm != null && dm.storage.containsCommit(commitId)

        if (indexed || attemptsLeft <= 0) {
            ApplicationManager.getApplication().invokeLater {
                com.intellij.vcs.log.impl.VcsProjectLog.showRevisionInMainLog(project, root, hash)
            }
            return
        }

        // Commit not yet indexed — retry after delay (total budget ~5 seconds)
        com.intellij.util.concurrency.AppExecutorUtil.getAppScheduledExecutorService().schedule({
            showRevisionWhenIndexed(root, hash, attemptsLeft - 1, delayMs)
        }, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun resolveFullHash(shortHash: String): String? {
        val basePath = project.basePath ?: return null
        return try {
            val process = ProcessBuilder("git", "rev-parse", shortHash)
                .directory(File(basePath))
                .redirectErrorStream(true)
                .start()
            val exited = process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            if (exited && process.exitValue() == 0) {
                process.inputStream.bufferedReader().readLine()?.trim()
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun markdownToHtml(text: String): String =
        MarkdownRenderer.markdownToHtml(text, ::resolveFileReference, ::resolveFilePath, ::isGitCommit)

    private fun isGitCommit(sha: String): Boolean {
        val basePath = project.basePath ?: return false
        return try {
            val process = ProcessBuilder("git", "cat-file", "-t", sha)
                .directory(File(basePath))
                .redirectErrorStream(true)
                .start()
            val exited = process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            exited && process.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveFileReference(ref: String): Pair<String, Int?>? {
        val colonIdx = ref.indexOf(':')
        val (name, lineNum) = if (colonIdx > 0) {
            val afterColon = ref.substring(colonIdx + 1)
            val num = afterColon.split(",", " ").firstOrNull()?.toIntOrNull()
            if (num != null) ref.substring(0, colonIdx) to num else ref to null
        } else ref to null
        val path = resolveFilePath(name)
            ?: if (!name.contains("/") && name.contains(".")) findProjectFileByName(name) else null
        return if (path != null) Pair(path, lineNum) else null
    }

    private fun resolveFilePath(path: String): String? {
        val f = File(path)
        if (f.isAbsolute) return if (f.exists()) f.absolutePath else null
        val base = project.basePath ?: return null
        val rel = File(base, path)
        return if (rel.exists()) rel.absolutePath else null
    }

    private fun findProjectFileByName(name: String): String? = try {
        var result: String? = null
        ApplicationManager.getApplication().runReadAction {
            val files = FilenameIndex.getVirtualFilesByName(name, GlobalSearchScope.projectScope(project)).toList()
            if (files.size == 1) result = files.first().path
        }
        result
    } catch (_: Exception) {
        null
    }

    // ── Tool result panel rendering ─────────────────────────────

    /**
     * Creates a Swing component for the tool result, dispatching to a custom renderer
     * or falling back to a monospace code panel.
     */
    private fun renderToolResultPanel(
        baseName: String?,
        status: String?,
        details: String?,
        arguments: String? = null
    ): JComponent {
        if (details.isNullOrBlank()) {
            return JBLabel(if (status != "failed") "Completed" else "✖ Failed")
        }
        if (status != "failed" && baseName != null) {
            val renderer = ToolRenderers.get(baseName, toolRegistry)
            val panel = when (renderer) {
                is ArgumentAwareRenderer -> renderer.render(details, arguments)
                else -> renderer?.render(details)
            }
            if (panel != null) return panel
        }
        return ToolRenderers.codePanel(details)
    }

    // ── Helpers ────────────────────────────────────────────────────

    private fun escJs(s: String) =
        s.replace("\\", "\\\\").replace("'", "\\'").replace("`", "\\`").replace("\n", "\\n").replace("\r", "")

    private fun esc(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;").replace("`", "&#96;")

    private fun b64(s: String): String = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
    private fun timestamp(): String {
        val c = Calendar.getInstance(); return "%02d:%02d".format(c[Calendar.HOUR_OF_DAY], c[Calendar.MINUTE])
    }

    private fun domId(id: String) = id.replace(Regex("[^a-zA-Z0-9_-]"), "_")
    private fun rgb(c: Color) = "rgb(${c.red},${c.green},${c.blue})"
    private fun rgba(c: Color, a: Double) = "rgba(${c.red},${c.green},${c.blue},$a)"

    // ── Theme ──────────────────────────────────────────────────────

    private fun buildCssVars(): String {
        val font = UIUtil.getLabelFont()
        val fg = UIUtil.getLabelForeground()
        val bg = com.intellij.util.ui.JBUI.CurrentTheme.ToolWindow.background()
        val codeBg =
            UIManager.getColor("Editor.backgroundColor") ?: JBColor(Color(0xF0, 0xF0, 0xF0), Color(0x2B, 0x2D, 0x30))
        val tblBorder =
            UIManager.getColor("TableCell.borderColor") ?: JBColor(Color(0xD0, 0xD0, 0xD0), Color(0x45, 0x48, 0x4A))
        val thBg =
            UIManager.getColor("TableHeader.background") ?: JBColor(Color(0xE8, 0xE8, 0xE8), Color(0x35, 0x38, 0x3B))
        val spinBg = UIManager.getColor("Panel.background") ?: JBColor(Color(0xDD, 0xDD, 0xDD), Color(0x55, 0x55, 0x55))
        val linkColor = UIManager.getColor(LINK_COLOR_KEY) ?: JBColor(Color(0x28, 0x7B, 0xDE), Color(0x58, 0x9D, 0xF6))
        val tooltipBg =
            UIManager.getColor("ToolTip.background") ?: JBColor(Color(0xF7, 0xF7, 0xF7), Color(0x3C, 0x3F, 0x41))
        val sb = StringBuilder()
        sb.append("--font-family:'${font.family}';--font-size:${font.size - 2}pt;--code-font-size:${font.size - 3}pt;--code-font:'JetBrains Mono','${font.family}',monospace;")
        sb.append(
            "--fg:${rgb(fg)};--fg-a05:${rgba(fg, 0.05)};--fg-a08:${rgba(fg, 0.08)};--fg-a16:${
                rgba(
                    fg,
                    0.16
                )
            };--fg-muted:${rgba(fg, 0.55)};--bg:${rgb(bg)};"
        )
        sb.append(
            "--user:${rgb(USER_COLOR)};--user-a06:${rgba(USER_COLOR, 0.06)};--user-a08:${
                rgba(
                    USER_COLOR,
                    0.08
                )
            };"
        )
        sb.append(
            "--user-a12:${rgba(USER_COLOR, 0.12)};--user-a15:${rgba(USER_COLOR, 0.15)};--user-a16:${
                rgba(
                    USER_COLOR,
                    0.16
                )
            };"
        )
        sb.append("--user-a18:${rgba(USER_COLOR, 0.18)};--user-a25:${rgba(USER_COLOR, 0.25)};")
        sb.append(
            "--agent:${rgb(AGENT_COLOR)};--agent-a06:${rgba(AGENT_COLOR, 0.06)};--agent-a08:${
                rgba(
                    AGENT_COLOR,
                    0.08
                )
            };"
        )
        sb.append("--agent-a10:${rgba(AGENT_COLOR, 0.10)};--agent-a16:${rgba(AGENT_COLOR, 0.16)};")
        sb.append(
            "--think:${rgb(THINK_COLOR)};--think-a04:${rgba(THINK_COLOR, 0.04)};--think-a06:${
                rgba(
                    THINK_COLOR,
                    0.06
                )
            };"
        )
        sb.append(
            "--think-a08:${rgba(THINK_COLOR, 0.08)};--think-a10:${rgba(THINK_COLOR, 0.10)};--think-a16:${
                rgba(
                    THINK_COLOR,
                    0.16
                )
            };"
        )
        sb.append(
            "--think-a25:${rgba(THINK_COLOR, 0.25)};--think-a30:${rgba(THINK_COLOR, 0.30)};--think-a35:${
                rgba(
                    THINK_COLOR,
                    0.35
                )
            };"
        )
        sb.append("--think-a40:${rgba(THINK_COLOR, 0.40)};--think-a55:${rgba(THINK_COLOR, 0.55)};")
        sb.append(
            "--tool:${rgb(TOOL_COLOR)};--tool-a08:${rgba(TOOL_COLOR, 0.08)};--tool-a16:${
                rgba(
                    TOOL_COLOR,
                    0.16
                )
            };--tool-a40:${rgba(TOOL_COLOR, 0.40)};"
        )
        sb.append("--spin-bg:${rgb(spinBg)};--code-bg:${rgb(codeBg)};--tbl-border:${rgb(tblBorder)};--th-bg:${rgb(thBg)};")
        sb.append("--link:${rgb(linkColor)};--tooltip-bg:${rgb(tooltipBg)};")
        sb.append(
            "--error:${rgb(ERROR_COLOR)};--error-a05:${rgba(ERROR_COLOR, 0.05)};--error-a06:${
                rgba(
                    ERROR_COLOR,
                    0.06
                )
            };"
        )
        sb.append("--error-a12:${rgba(ERROR_COLOR, 0.12)};--error-a16:${rgba(ERROR_COLOR, 0.16)};")
        val bannerInfoBg = com.intellij.util.ui.JBUI.CurrentTheme.Banner.INFO_BACKGROUND
        val bannerInfoBorder = com.intellij.util.ui.JBUI.CurrentTheme.Banner.INFO_BORDER_COLOR
        val bannerErrorBg = com.intellij.util.ui.JBUI.CurrentTheme.Banner.ERROR_BACKGROUND
        val bannerErrorBorder = com.intellij.util.ui.JBUI.CurrentTheme.Banner.ERROR_BORDER_COLOR
        val bannerFg = com.intellij.util.ui.JBUI.CurrentTheme.Banner.FOREGROUND
        sb.append("--banner-info-bg:${rgb(bannerInfoBg)};--banner-info-border:${rgb(bannerInfoBorder)};")
        sb.append("--banner-error-bg:${rgb(bannerErrorBg)};--banner-error-border:${rgb(bannerErrorBorder)};")
        sb.append("--banner-fg:${rgb(bannerFg)};")
        sb.append("--shadow:${rgba(THINK_COLOR, 0.25)};")
        for (i in SA_COLORS.indices) {
            val c = SA_COLORS[i]
            sb.append(
                "--sa-c$i:${rgb(c)};--sa-c$i-a06:${rgba(c, 0.06)};--sa-c$i-a10:${
                    rgba(
                        c,
                        0.10
                    )
                };--sa-c$i-a15:${rgba(c, 0.15)};"
            )
        }
        sb.append("--kind-read:${rgb(KIND_READ_COLOR)};--kind-edit:${rgb(KIND_EDIT_COLOR)};")
        sb.append("--kind-execute:${rgb(KIND_EXECUTE_COLOR)};--kind-search:${rgb(KIND_SEARCH_COLOR)};")
        sb.append("--kind-think:${rgb(KIND_THINK_COLOR)};--kind-other:${rgb(KIND_OTHER_COLOR)};")
        return sb.toString()
    }

    private fun updateThemeColors() {
        val vars = buildCssVars().replace("'", "\\'")
        executeJs("document.documentElement.style.cssText='$vars'")
        val panelBg = com.intellij.util.ui.JBUI.CurrentTheme.ToolWindow.background()
        browser?.setPageBackgroundColor("rgb(${panelBg.red},${panelBg.green},${panelBg.blue})")
    }

    // ── Permission requests ────────────────────────────────────────

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
        val resultPanel = renderToolResultPanel(baseName, entry?.status, entry?.result, entry?.arguments)
        val paramsPanel = if (!entry?.arguments.isNullOrBlank()) {
            ToolRenderers.jsonEditor(prettyJson(entry.arguments), project)
        } else null
        ApplicationManager.getApplication().invokeLater {
            ToolCallPopup.show(project, chipTitle, kind, paramsPanel, resultPanel)
        }
    }

    /** Produces a chip-style title matching the JS toolDisplayName() logic. */
    private fun toolChipTitle(baseName: String?, arguments: String?): String {
        if (baseName == null) return "Tool Call"
        val subtitle = formatToolSubtitle(baseName, arguments)
        val info = TOOL_DISPLAY_INFO[baseName]
        val display = info?.displayName ?: baseName
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
                showToolPopup: function(id) { $showToolPopupBridgeJs }
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
