package com.github.catatafishen.ideagentforcopilot.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.JBColor
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
import javax.swing.SwingUtilities
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

    @Volatile
    private var htmlPageFuture: java.util.concurrent.CompletableFuture<String>? = null
    private val deferredRestoreJson = mutableListOf<com.google.gson.JsonElement>()
    private val pendingPermissionCallbacks = java.util.concurrent.ConcurrentHashMap<String, (Boolean) -> Unit>()

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

        private fun getThemeColor(key: String, lightFallback: Color, darkFallback: Color): Color =
            UIManager.getColor(key) ?: JBColor(lightFallback, darkFallback)

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
                SwingUtilities.invokeLater {
                    browser.component.cursor = when (type) {
                        "pointer" -> java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                        "text" -> java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.TEXT_CURSOR)
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
                // data format: "reqId:true" or "reqId:false"
                val colonIdx = data.lastIndexOf(':')
                if (colonIdx > 0) {
                    val reqId = data.substring(0, colonIdx)
                    val allowed = data.substring(colonIdx + 1) == "true"
                    pendingPermissionCallbacks.remove(reqId)?.invoke(allowed)
                }
                null
            }
            Disposer.register(this, permissionResponseQuery)
            permissionResponseBridgeJs = permissionResponseQuery.inject("data")

            val openScratchQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase)
            openScratchQuery.addHandler { data -> handleOpenScratch(data); null }
            Disposer.register(this, openScratchQuery)
            openScratchBridgeJs = openScratchQuery.inject("lang + '\\n' + content")

            add(browser.component, BorderLayout.CENTER)

            browser.jbCefClient.addLoadHandler(
                com.github.catatafishen.ideagentforcopilot.psi.PlatformApiCompat.createMainFrameLoadEndHandler {
                    SwingUtilities.invokeLater {
                        browserReady = true
                        pendingJs.forEach { browser.cefBrowser.executeJavaScript(it, "", 0) }
                        pendingJs.clear()
                    }
                }, browser.cefBrowser)

            browser.jbCefClient.addDisplayHandler(
                com.github.catatafishen.ideagentforcopilot.psi.PlatformApiCompat.createConsoleLogHandler(
                    com.intellij.openapi.diagnostic.Logger.getInstance(ChatConsolePanel::class.java)
                ), browser.cefBrowser)

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

    override fun addPromptEntry(text: String, contextFiles: List<Triple<String, String, Int>>?) {
        toolJustCompleted = false
        finalizeCurrentText()
        collapseThinking()
        currentTurnId = "t${turnCounter++}"
        val ts = timestamp()
        entries.add(EntryData.Prompt(text, ts))
        val ctxHtml = if (!contextFiles.isNullOrEmpty()) {
            contextFiles.joinToString("") { (name, path, line) ->
                val href = if (line > 0) "openfile://$path:$line" else "openfile://$path"
                "<a class=\\'prompt-ctx-chip\\' href=\\'$href\\' title=\\'${escJs(path)}${if (line > 0) ":$line" else ""}\\'>📄 ${
                    escJs(
                        name
                    )
                }</a>"
            }
        } else ""
        executeJs("ChatController.addUserMessage('${escJs(text)}','$ts','$ctxHtml')")
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
        fallbackArea?.let { SwingUtilities.invokeLater { it.append(text) } }
    }

    override fun addToolCallEntry(id: String, title: String, arguments: String?, kind: String?) {
        finalizeCurrentText()
        val resolvedKind = kind ?: "other"
        entries.add(EntryData.ToolCall(title, arguments, resolvedKind))
        val did = domId(id)
        val baseName = title.substringAfterLast("-")
        toolCallNames[did] = baseName
        val info = TOOL_DISPLAY_INFO[baseName]
        val displayName = info?.displayName ?: title.replaceFirstChar { it.uppercaseChar() }
        val short = formatToolSubtitle(baseName, arguments)
        val label = if (short != null) "$displayName — $short" else displayName
        val hasCustomRenderer = baseName in TOOL_RESULT_RENDERERS
        val paramsJson = if (!arguments.isNullOrBlank() && !hasCustomRenderer) escJs(arguments) else ""
        val safeKind = escJs(resolvedKind)
        executeJs("ChatController.addToolCall('$currentTurnId','main','$did','${escJs(label)}','$paramsJson','$safeKind')")
    }

    override fun updateToolCall(id: String, status: String, details: String?) {
        val did = domId(id)
        val baseName = toolCallNames[did]
        val resultHtml = renderToolResult(baseName, status, details)
        val failed = if (status == "failed") "failed" else "completed"
        executeJs("(function(){ChatController.updateToolCall('$did','$failed',$resultHtml);})()")
        toolJustCompleted = true
    }

    /** Add a tool call chip+section to a sub-agent's result message. */
    override fun addSubAgentToolCall(subAgentId: String, toolId: String, title: String, arguments: String?) {
        val saDid = domId(subAgentId)
        val toolDid = domId(toolId)
        val baseName = title.substringAfterLast("-")
        val info = TOOL_DISPLAY_INFO[baseName]
        val displayName = info?.displayName ?: title.replaceFirstChar { it.uppercaseChar() }
        val short = formatToolSubtitle(baseName, arguments)
        val label = if (short != null) "$displayName — $short" else displayName
        val hasCustomRenderer = baseName in TOOL_RESULT_RENDERERS
        val paramsJson = if (!arguments.isNullOrBlank() && !hasCustomRenderer) escJs(arguments) else ""
        executeJs("ChatController.addSubAgentToolCall('$saDid','$toolDid','${escJs(label)}','$paramsJson')")
    }

    /** Update a sub-agent internal tool call (no segment break). */
    override fun updateSubAgentToolCall(toolId: String, status: String, details: String?) {
        val did = domId(toolId)
        val resultHtml = if (!details.isNullOrBlank()) {
            val encoded =
                b64("<div class='tool-result-label'>Output:</div><pre class='tool-output'><code>${esc(details)}</code></pre>")
            "b64('$encoded')"
        } else {
            if (status == "completed") "'Completed'" else "'<span style=\"color:var(--error)\">✖ Failed</span>'"
        }
        val failed = if (status == "failed") "failed" else "completed"
        executeJs("(function(){ChatController.updateToolCall('$did','$failed',$resultHtml);})()")
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
                if (!initialResult.isNullOrBlank()) markdownToHtml(initialResult) else if (initialStatus == "completed") "Completed" else "<span style='color:var(--error)'>✖ Failed</span>"
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
            if (!result.isNullOrBlank()) markdownToHtml(result) else if (status == "completed") "Completed" else "<span style='color:var(--error)'>✖ Failed</span>"
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

    override fun addSessionSeparator(timestamp: String) {
        finalizeCurrentText()
        entries.add(EntryData.SessionSeparator(timestamp))
        executeJs("ChatController.addSessionSeparator('${escJs(timestamp)}')")
    }

    override fun showPlaceholder(text: String) {
        entries.clear(); deferredRestoreJson.clear()
        currentTextData = null; currentThinkingData = null; nextSubAgentColor = 0
        turnCounter = 0; currentTurnId = ""; toolJustCompleted = false
        executeJs("ChatController.showPlaceholder('${escJs(text)}')")
        fallbackArea?.let { SwingUtilities.invokeLater { it.text = text } }
    }

    override fun clear() {
        entries.clear(); deferredRestoreJson.clear()
        currentTextData = null; currentThinkingData = null; nextSubAgentColor = 0
        turnCounter = 0; currentTurnId = ""; toolJustCompleted = false
        executeJs("ChatController.clear()")
        fallbackArea?.let { SwingUtilities.invokeLater { it.text = "" } }
    }

    override fun finishResponse(toolCallCount: Int, modelId: String, multiplier: String) {
        repaintTimer.stop()
        toolJustCompleted = false
        finalizeCurrentText()
        collapseThinking()
        val statsJson = """{"tools":$toolCallCount,"model":"${escJs(modelId)}","mult":"${escJs(multiplier)}"}"""
        executeJs("ChatController.finalizeTurn('$currentTurnId',$statsJson)")
        SwingUtilities.invokeLater { browser?.component?.repaint() }
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
                    obj.addProperty("type", "separator"); obj.addProperty("timestamp", e.timestamp)
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
            "prompt" -> entries.add(EntryData.Prompt(obj["text"]?.asString ?: "", obj["ts"]?.asString ?: ""))
            "text" -> entries.add(EntryData.Text(StringBuilder(obj["raw"]?.asString ?: "")))
            "thinking" -> entries.add(EntryData.Thinking(StringBuilder(obj["raw"]?.asString ?: "")))
            "tool" -> entries.add(
                EntryData.ToolCall(
                    obj["title"]?.asString ?: "",
                    obj["args"]?.asString,
                    obj["kind"]?.asString ?: "other"
                )
            )

            "subagent" -> {
                val ci = obj["colorIndex"]?.asInt ?: (nextSubAgentColor++ % SA_COLOR_COUNT)
                entries.add(
                    EntryData.SubAgent(
                        obj["agentType"]?.asString ?: "general-purpose",
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

            "separator" -> entries.add(EntryData.SessionSeparator(obj["timestamp"]?.asString ?: ""))
        }
    }

    private fun renderRestoredEntry(obj: com.google.gson.JsonObject) {
        when (obj["type"]?.asString) {
            "prompt" -> {
                currentTurnId = "t${turnCounter++}"
                val text = obj["text"]?.asString ?: ""
                val ts = obj["ts"]?.asString ?: ""
                executeJs("ChatController.addUserMessage('${escJs(text)}','$ts','')")
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
                val baseName = title.substringAfterLast("-")
                val info = TOOL_DISPLAY_INFO[baseName]
                val displayName = info?.displayName ?: title.replaceFirstChar { it.uppercaseChar() }
                val short = formatToolSubtitle(baseName, args)
                val label = if (short != null) "$displayName — $short" else displayName
                val did = "restored-tool-${entries.size}"
                val kind = obj["kind"]?.asString ?: "other"
                executeJs(
                    "ChatController.addToolCall('$currentTurnId','main','$did','${escJs(label)}','${escJs(args ?: "")}','${
                        escJs(
                            kind
                        )
                    }');ChatController.updateToolCall('$did','completed','Completed')"
                )
            }

            "subagent" -> {
                if (currentTurnId.isEmpty()) currentTurnId = "t${turnCounter++}"
                val agentType = obj["agentType"]?.asString ?: "general-purpose"
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
                    if (!result.isNullOrBlank()) markdownToHtml(result) else if (status == "completed") "Completed" else "<span style='color:var(--error)'>✖ Failed</span>"
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
                executeJs("ChatController.addSessionSeparator('${escJs(ts)}')")
            }
        }
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
                    sb.append("<chat-message type='user'>")
                    sb.append("<message-meta><span class='ts'>${esc(ts)}</span></message-meta>")
                    sb.append("<message-bubble type='user'>${esc(text)}</message-bubble>")
                    sb.append("</chat-message>")
                    i++
                }

                "separator" -> {
                    sb.append("<session-divider timestamp='${esc(obj["timestamp"]?.asString ?: "")}'></session-divider>")
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
                                val baseName = title.substringAfterLast("-")
                                val info = TOOL_DISPLAY_INFO[baseName]
                                val displayName = info?.displayName ?: title.replaceFirstChar { it.uppercaseChar() }
                                val short = formatToolSubtitle(baseName, args)
                                val label = if (short != null) "$displayName — $short" else displayName
                                val id = "batch-tool-${batchIdCounter++}"
                                metaChips.append("<tool-chip label='${esc(label)}' status='complete' kind='${esc(kind)}' data-chip-for='$id'></tool-chip>")
                                detailsContent.append("<tool-section id='$id' title='${esc(label)}'")
                                if (args != null && baseName !in TOOL_RESULT_RENDERERS) detailsContent.append(
                                    " params='${
                                        esc(
                                            args
                                        )
                                    }'"
                                )
                                detailsContent.append("><div class='tool-params'></div><div class='tool-result'>Completed</div></tool-section>")
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
                                val agentType = e["agentType"]?.asString ?: "general-purpose"
                                val saInfo = SUB_AGENT_INFO[agentType]
                                val dn = saInfo?.displayName ?: agentType.replaceFirstChar { it.uppercaseChar() }
                                val result = e["result"]?.asString?.ifEmpty { null }
                                val ci = e["colorIndex"]?.asInt ?: 0
                                val resultHtml = if (!result.isNullOrBlank()) markdownToHtml(result) else "Completed"
                                val id = "batch-sa-${batchIdCounter++}"
                                metaChips.append("<subagent-chip label='${esc(dn)}' status='complete' color-index='$ci' data-chip-for='$id'></subagent-chip>")
                                afterDetails.append("<div id='$id' class='subagent-indent subagent-c$ci turn-hidden'><message-bubble>$resultHtml</message-bubble></div>")
                            }

                            else -> {}
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
        if (SwingUtilities.isEventDispatchThread()) {
            trigger.run()
        } else {
            SwingUtilities.invokeLater(trigger)
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
        SwingUtilities.invokeLater {
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
                val repos = git4idea.repo.GitRepositoryManager.getInstance(project).repositories
                val root = repos.firstOrNull()?.root
                if (root == null) {
                    log.warn("No VCS root found for git commit link $hash")
                    return@invokeLater
                }
                // Resolve short hash to full 40-char SHA — VcsProjectLog requires exact match
                val fullHash = resolveFullHash(hash) ?: hash
                val hashObj = com.intellij.vcs.log.impl.HashImpl.build(fullHash)
                com.intellij.vcs.log.impl.VcsProjectLog.showRevisionInMainLog(project, root, hashObj)
            } catch (e: Exception) {
                log.warn("Failed to open git commit $hash", e)
            }
        }
    }

    private fun resolveFullHash(shortHash: String): String? {
        val basePath = project.basePath ?: return null
        return try {
            val process = ProcessBuilder("git", "rev-parse", shortHash)
                .directory(java.io.File(basePath))
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
                .directory(java.io.File(basePath))
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
            val files = FilenameIndex.getVirtualFilesByName(name, GlobalSearchScope.projectScope(project))
            if (files.size == 1) result = files.first().path
        }
        result
    } catch (_: Exception) {
        null
    }

    // ── Custom tool result renderers ─────────────────────────────

    /**
     * Dispatch to a custom renderer or fall back to the default pre/code block.
     * Returns a JS expression that evaluates to an HTML string.
     */
    private fun renderToolResult(baseName: String?, status: String, details: String?): String {
        if (details.isNullOrBlank()) {
            return if (status == "completed") "'Completed'"
            else "'<span style=\"color:var(--error)\">✖ Failed</span>'"
        }
        if (status == "completed" && baseName != null) {
            val custom = TOOL_RESULT_RENDERERS[baseName]?.invoke(details)
            if (custom != null) return "b64('${b64(custom)}')"
        }
        val encoded = b64(
            "<pre class='tool-output'><code>${esc(details)}</code></pre>"
        )
        return "b64('$encoded')"
    }

    /** Per-tool custom result renderers. Return HTML string or null to use default. */
    private val TOOL_RESULT_RENDERERS: Map<String, (String) -> String?> = mapOf(
        "git_commit" to ::renderGitCommitResult,
        "git_status" to ::renderGitStatusResult,
        "git_diff" to ::renderGitDiffResult,
        "git_log" to ::renderGitLogResult,
        "build_project" to ::renderBuildResult,
    )

    /**
     * Renders a git commit result as a rich card with commit metadata,
     * message, changed files with stats, and a link to show in IDE git tools.
     */
    private fun renderGitCommitResult(output: String): String? {
        // Parse: [branch hash] message\n N files changed, X insertions(+), Y deletions(-)\n ...
        val lines = output.trimEnd().lines()
        if (lines.isEmpty()) return null

        val headerLine = lines[0]
        // Match "[branch hash] message" pattern
        val headerMatch = Regex("""\[(\S+)\s+([a-f0-9]+)]\s+(.+)""").find(headerLine) ?: return null
        val branch = esc(headerMatch.groupValues[1])
        val shortHash = esc(headerMatch.groupValues[2])
        val message = esc(headerMatch.groupValues[3])

        // Remaining lines: stats summary and file entries
        val fileLines = mutableListOf<String>()
        var summaryLine = ""
        for (i in 1 until lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) continue
            if (line.matches(Regex("""\d+ files? changed.*"""))) {
                summaryLine = line
            } else {
                fileLines.add(line)
            }
        }

        val sb = StringBuilder()
        sb.append("<div class='git-commit-result'>")

        // Header: hash + branch
        sb.append("<div class='git-commit-header'>")
        sb.append("<span class='git-commit-hash'>")
        sb.append("<a href='gitshow://$shortHash' class='git-commit-link' title='Show commit in IDE'>$shortHash</a>")
        sb.append("</span>")
        sb.append(" <span class='git-commit-branch'>$branch</span>")
        sb.append("</div>")

        // Commit message
        sb.append("<div class='git-commit-message'>$message</div>")

        // Stats summary
        if (summaryLine.isNotEmpty()) {
            val statsParts = parseDiffStats(summaryLine)
            sb.append("<div class='git-commit-stats'>")
            sb.append("<span class='git-stat-files'>${esc(statsParts.files)}</span>")
            if (statsParts.insertions.isNotEmpty()) {
                sb.append(" <span class='git-stat-ins'>+${esc(statsParts.insertions)}</span>")
            }
            if (statsParts.deletions.isNotEmpty()) {
                sb.append(" <span class='git-stat-del'>-${esc(statsParts.deletions)}</span>")
            }
            sb.append("</div>")
        }

        // Changed files
        if (fileLines.isNotEmpty()) {
            sb.append("<div class='git-commit-files'>")
            for (fileLine in fileLines) {
                val rendered = renderFileEntry(fileLine)
                sb.append(rendered)
            }
            sb.append("</div>")
        }

        sb.append("</div>")
        return sb.toString()
    }

    private data class DiffStats(val files: String, val insertions: String, val deletions: String)

    private fun parseDiffStats(line: String): DiffStats {
        val filesMatch = Regex("""(\d+ files? changed)""").find(line)
        val insMatch = Regex("""(\d+) insertions?\(\+\)""").find(line)
        val delMatch = Regex("""(\d+) deletions?\(-\)""").find(line)
        return DiffStats(
            files = filesMatch?.groupValues?.get(1) ?: line,
            insertions = insMatch?.groupValues?.get(1) ?: "",
            deletions = delMatch?.groupValues?.get(1) ?: "",
        )
    }

    private fun renderFileEntry(line: String): String {
        // "create mode 100644 path/to/file" or "delete mode 100644 path/to/file" or just path
        val createMatch = Regex("""create mode \d+ (.+)""").find(line)
        val deleteMatch = Regex("""delete mode \d+ (.+)""").find(line)
        val renameMatch = Regex("""rename (.+) => (.+) \((\d+)%\)""").find(line)

        return when {
            createMatch != null -> {
                val path = esc(createMatch.groupValues[1].trim())
                "<div class='git-file-entry'><span class='git-file-badge git-file-add'>A</span> <span class='git-file-path'>$path</span></div>"
            }

            deleteMatch != null -> {
                val path = esc(deleteMatch.groupValues[1].trim())
                "<div class='git-file-entry'><span class='git-file-badge git-file-del'>D</span> <span class='git-file-path'>$path</span></div>"
            }

            renameMatch != null -> {
                val from = esc(renameMatch.groupValues[1].trim())
                val to = esc(renameMatch.groupValues[2].trim())
                "<div class='git-file-entry'><span class='git-file-badge git-file-rename'>R</span> <span class='git-file-path'>$from → $to</span></div>"
            }

            else -> {
                val path = esc(line.trim())
                "<div class='git-file-entry'><span class='git-file-badge git-file-mod'>M</span> <span class='git-file-path'>$path</span></div>"
            }
        }
    }

    // ── git_status renderer ───────────────────────────────────────

    private data class StatusFiles(
        val staged: List<Pair<String, String>>,
        val unstaged: List<Pair<String, String>>,
        val untracked: List<String>,
        val conflicted: List<String>,
    )

    /**
     * Renders git status output as a structured card with branch header,
     * staged/unstaged/untracked sections, and colored file badges.
     */
    private fun renderGitStatusResult(output: String): String? {
        val lines = output.trimEnd().lines()
        if (lines.isEmpty()) return null

        val sb = StringBuilder("<div class='git-status-result'>")
        appendStatusBranchHeader(sb, lines)

        val files = categorizeStatusFiles(lines)
        if (files.staged.isEmpty() && files.unstaged.isEmpty()
            && files.untracked.isEmpty() && files.conflicted.isEmpty()
        ) {
            sb.append("<div class='git-status-clean'>Nothing to commit, working tree clean</div>")
        }

        appendStatusSection(sb, files.conflicted, "git-status-conflict", "Conflicts") { path ->
            "<span class='git-file-badge git-file-conflict'>U</span> <span class='git-file-path'>${esc(path)}</span>"
        }
        appendBadgedSection(sb, files.staged, "git-status-staged", "Staged")
        appendBadgedSection(sb, files.unstaged, "git-status-unstaged", "Changes")
        appendStatusSection(sb, files.untracked, "git-status-untracked", "Untracked") { path ->
            "<span class='git-file-badge git-file-untracked'>?</span> <span class='git-file-path'>${esc(path)}</span>"
        }

        sb.append("</div>")
        return sb.toString()
    }

    private fun appendStatusBranchHeader(sb: StringBuilder, lines: List<String>) {
        val branchLine = lines.firstOrNull { it.startsWith("##") } ?: return
        val branchInfo = branchLine.removePrefix("## ").trim()
        val branchName = branchInfo.split("...").first()
        val tracking = if ("..." in branchInfo) branchInfo.substringAfter("...") else null
        sb.append("<div class='git-status-branch'>")
        sb.append("<span class='git-status-branch-name'>${esc(branchName)}</span>")
        if (tracking != null) {
            sb.append(" <span class='git-status-tracking'>${esc(tracking)}</span>")
        }
        sb.append("</div>")
    }

    private fun categorizeStatusFiles(lines: List<String>): StatusFiles {
        val staged = mutableListOf<Pair<String, String>>()
        val unstaged = mutableListOf<Pair<String, String>>()
        val untracked = mutableListOf<String>()
        val conflicted = mutableListOf<String>()

        for (line in lines) {
            if (line.startsWith("##") || line.isBlank() || line.length < 4) continue
            val x = line[0]
            val y = line[1]
            val path = line.substring(3).trim()
            categorizeFile(x, y, path, staged, unstaged, untracked, conflicted)
        }
        return StatusFiles(staged, unstaged, untracked, conflicted)
    }

    private fun categorizeFile(
        x: Char, y: Char, path: String,
        staged: MutableList<Pair<String, String>>,
        unstaged: MutableList<Pair<String, String>>,
        untracked: MutableList<String>,
        conflicted: MutableList<String>,
    ) {
        if (x == 'U' || y == 'U' || (x == 'A' && y == 'A') || (x == 'D' && y == 'D')) {
            conflicted.add(path); return
        }
        if (x == '?' && y == '?') { untracked.add(path); return }
        if (x != ' ' && x != '?') staged.add(statusBadge(x) to path)
        if (y != ' ' && y != '?') unstaged.add(statusBadge(y) to path)
    }

    private fun <T> appendStatusSection(
        sb: StringBuilder, items: List<T>, headerCls: String, label: String, renderItem: (T) -> String,
    ) {
        if (items.isEmpty()) return
        sb.append("<div class='git-status-section'>")
        sb.append("<div class='git-status-section-header $headerCls'>$label (${items.size})</div>")
        for (item in items) {
            sb.append("<div class='git-file-entry'>${renderItem(item)}</div>")
        }
        sb.append("</div>")
    }

    private fun appendBadgedSection(
        sb: StringBuilder, items: List<Pair<String, String>>, headerCls: String, label: String,
    ) {
        appendStatusSection(sb, items, headerCls, label) { (badge, path) ->
            "$badge <span class='git-file-path'>${esc(path)}</span>"
        }
    }

    private fun statusBadge(code: Char): String {
        val (label, cls) = when (code) {
            'M' -> "M" to "git-file-mod"
            'A' -> "A" to "git-file-add"
            'D' -> "D" to "git-file-del"
            'R' -> "R" to "git-file-rename"
            'C' -> "C" to "git-file-rename"
            'T' -> "T" to "git-file-mod"
            else -> code.toString() to "git-file-mod"
        }
        return "<span class='git-file-badge $cls'>$label</span>"
    }

    // ── git_diff renderer ─────────────────────────────────────────

    /**
     * Renders unified diff output with syntax-highlighted additions/deletions,
     * file headers, and hunk markers. Also handles stat-only output.
     */
    private fun renderGitDiffResult(output: String): String? {
        val lines = output.trimEnd().lines()
        if (lines.isEmpty()) return null

        // Detect stat-only output (ends with "N files changed, ...")
        val lastNonBlank = lines.lastOrNull { it.isNotBlank() } ?: return null
        if (lastNonBlank.matches(Regex("""\s*\d+ files? changed.*"""))) {
            return renderDiffStat(lines)
        }

        return renderUnifiedDiff(lines)
    }

    private fun renderDiffStat(lines: List<String>): String {
        val sb = StringBuilder("<div class='git-diff-result'>")
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.matches(Regex("""\d+ files? changed.*"""))) {
                appendDiffStatSummary(sb, trimmed)
            } else {
                appendDiffStatFileLine(sb, trimmed)
            }
        }
        sb.append("</div>")
        return sb.toString()
    }

    private fun appendDiffStatSummary(sb: StringBuilder, line: String) {
        val stats = parseDiffStats(line)
        sb.append("<div class='git-diff-stat-summary'>")
        sb.append("<span class='git-stat-files'>${esc(stats.files)}</span>")
        if (stats.insertions.isNotEmpty()) sb.append(" <span class='git-stat-ins'>+${esc(stats.insertions)}</span>")
        if (stats.deletions.isNotEmpty()) sb.append(" <span class='git-stat-del'>-${esc(stats.deletions)}</span>")
        sb.append("</div>")
    }

    private fun appendDiffStatFileLine(sb: StringBuilder, line: String) {
        val statMatch = Regex("""^\s*(.+?)\s*\|\s*(\d+)\s*([+\-]+)?\s*$""").find(line)
        if (statMatch != null) {
            val path = esc(statMatch.groupValues[1].trim())
            val count = esc(statMatch.groupValues[2])
            val bar = statMatch.groupValues[3]
            sb.append("<div class='git-diff-stat-file'>")
            sb.append("<span class='git-file-path'>$path</span>")
            sb.append(" <span class='git-diff-stat-count'>$count</span> ")
            sb.append(renderStatBar(bar))
            sb.append("</div>")
        } else {
            sb.append("<div class='git-diff-stat-file'><span class='git-file-path'>${esc(line)}</span></div>")
        }
    }

    private fun renderStatBar(bar: String): String {
        if (bar.isEmpty()) return ""
        val sb = StringBuilder("<span class='git-diff-stat-bar'>")
        for (ch in bar) {
            when (ch) {
                '+' -> sb.append("<span class='git-stat-ins'>+</span>")
                '-' -> sb.append("<span class='git-stat-del'>-</span>")
                else -> sb.append(ch)
            }
        }
        sb.append("</span>")
        return sb.toString()
    }

    private fun renderUnifiedDiff(lines: List<String>): String {
        val sb = StringBuilder("<div class='git-diff-result'>")
        var inFile = false

        for (line in lines) {
            val diffLine = classifyDiffLine(line)
            when (diffLine) {
                DiffLineType.FILE_HEADER -> {
                    if (inFile) sb.append("</div></div>")
                    inFile = true
                    val filePath = extractDiffFilePath(line)
                    sb.append("<div class='git-diff-file'>")
                    sb.append("<div class='git-diff-file-header'><span class='git-file-path'>${esc(filePath)}</span></div>")
                    sb.append("<div class='git-diff-hunks'>")
                }
                DiffLineType.HUNK -> appendHunkHeader(sb, line)
                DiffLineType.ADD -> sb.append("<div class='git-diff-line git-diff-add'>${esc(line)}</div>")
                DiffLineType.DEL -> sb.append("<div class='git-diff-line git-diff-del'>${esc(line)}</div>")
                DiffLineType.BINARY -> sb.append("<div class='git-diff-line git-diff-meta'>${esc(line)}</div>")
                DiffLineType.CONTEXT -> if (inFile) sb.append("<div class='git-diff-line git-diff-ctx'>${esc(line)}</div>")
                DiffLineType.SKIP -> { /* meta lines — skip */ }
            }
        }
        if (inFile) sb.append("</div></div>")
        sb.append("</div>")
        return sb.toString()
    }

    private enum class DiffLineType { FILE_HEADER, HUNK, ADD, DEL, CONTEXT, BINARY, SKIP }

    private fun classifyDiffLine(line: String): DiffLineType = when {
        line.startsWith("diff --git") -> DiffLineType.FILE_HEADER
        line.startsWith("@@") -> DiffLineType.HUNK
        line.startsWith("+") && !line.startsWith("+++") -> DiffLineType.ADD
        line.startsWith("-") && !line.startsWith("---") -> DiffLineType.DEL
        line.startsWith("---") || line.startsWith("+++") -> DiffLineType.SKIP
        line.startsWith("Binary files") -> DiffLineType.BINARY
        isDiffMetaLine(line) -> DiffLineType.SKIP
        else -> DiffLineType.CONTEXT
    }

    private fun isDiffMetaLine(line: String): Boolean =
        line.startsWith("index ") || line.startsWith("new file") || line.startsWith("deleted file") ||
            line.startsWith("similarity") || line.startsWith("rename") ||
            line.startsWith("old mode") || line.startsWith("new mode")

    private fun appendHunkHeader(sb: StringBuilder, line: String) {
        val hunkMatch = Regex("""@@\s*-\d+(?:,\d+)?\s*\+\d+(?:,\d+)?\s*@@(.*)""").find(line)
        val context = hunkMatch?.groupValues?.get(1)?.trim() ?: ""
        sb.append("<div class='git-diff-hunk-header'>${esc(line.substringBefore("@@", "").let { "$it@@" })} ")
        if (context.isNotEmpty()) sb.append("<span class='git-diff-hunk-ctx'>${esc(context)}</span>")
        sb.append("</div>")
    }

    private fun extractDiffFilePath(diffLine: String): String {
        // "diff --git a/path/to/file b/path/to/file" → "path/to/file"
        val match = Regex("""diff --git a/(.+) b/(.+)""").find(diffLine)
        return match?.groupValues?.get(2) ?: diffLine.substringAfter("diff --git ").trim()
    }

    // ── git_log renderer ──────────────────────────────────────────

    /**
     * Renders git log output as a structured commit list with clickable hashes,
     * author info, and relative dates.
     */
    private fun renderGitLogResult(output: String): String? {
        val lines = output.trimEnd().lines()
        if (lines.isEmpty()) return null

        // Detect format: "short" has "hash msg (author, date)" on each line
        // "oneline" has "hash msg" on each line
        // "medium/full" has "commit hash" blocks
        val firstLine = lines.first().trim()

        return when {
            firstLine.startsWith("commit ") -> renderMediumLog(lines)
            Regex("""^[a-f0-9]{7,40}\s+.+\(.+,\s*.+\)$""").matches(firstLine) -> renderShortLog(lines)
            Regex("""^[a-f0-9]{7,40}\s+.+""").matches(firstLine) -> renderOnelineLog(lines)
            else -> null // fall back to default
        }
    }

    private fun renderOnelineLog(lines: List<String>): String {
        val sb = StringBuilder("<div class='git-log-result'>")
        for (line in lines) {
            if (line.isBlank()) continue
            val spaceIdx = line.indexOf(' ')
            if (spaceIdx < 0) continue
            val hash = line.substring(0, spaceIdx).trim()
            val message = line.substring(spaceIdx + 1).trim()
            sb.append("<div class='git-log-entry'>")
            sb.append("<a href='gitshow://$hash' class='git-log-hash' title='Show commit'>${esc(hash)}</a>")
            sb.append(" <span class='git-log-message'>${esc(message)}</span>")
            sb.append("</div>")
        }
        sb.append("</div>")
        return sb.toString()
    }

    private fun renderShortLog(lines: List<String>): String {
        val sb = StringBuilder("<div class='git-log-result'>")
        val pattern = Regex("""^([a-f0-9]{7,40})\s+(.+)\((.+),\s*(.+)\)$""")
        for (line in lines) {
            if (line.isBlank()) continue
            val match = pattern.find(line.trim())
            if (match != null) {
                val hash = match.groupValues[1]
                val message = match.groupValues[2].trim()
                val author = match.groupValues[3].trim()
                val date = match.groupValues[4].trim()
                sb.append("<div class='git-log-entry'>")
                sb.append("<a href='gitshow://$hash' class='git-log-hash' title='Show commit'>${esc(hash)}</a>")
                sb.append(" <span class='git-log-message'>${esc(message)}</span>")
                sb.append(" <span class='git-log-meta'>${esc(author)}, ${esc(date)}</span>")
                sb.append("</div>")
            } else {
                // fallback for lines that don't match
                sb.append("<div class='git-log-entry'><span class='git-log-message'>${esc(line.trim())}</span></div>")
            }
        }
        sb.append("</div>")
        return sb.toString()
    }

    private fun renderMediumLog(lines: List<String>): String {
        val sb = StringBuilder("<div class='git-log-result'>")
        var currentHash = ""
        var currentAuthor = ""
        var currentDate = ""
        val currentMessage = StringBuilder()
        var inMessage = false

        fun flushEntry() {
            if (currentHash.isEmpty()) return
            val shortHash = if (currentHash.length > 7) currentHash.substring(0, 7) else currentHash
            sb.append("<div class='git-log-entry'>")
            sb.append("<a href='gitshow://$shortHash' class='git-log-hash' title='Show commit'>${esc(shortHash)}</a>")
            val msg = currentMessage.toString().trim()
            if (msg.isNotEmpty()) {
                val firstLine = msg.lines().first()
                sb.append(" <span class='git-log-message'>${esc(firstLine)}</span>")
            }
            if (currentAuthor.isNotEmpty() || currentDate.isNotEmpty()) {
                val meta = listOf(currentAuthor, currentDate).filter { it.isNotEmpty() }.joinToString(", ")
                sb.append(" <span class='git-log-meta'>${esc(meta)}</span>")
            }
            sb.append("</div>")
            currentHash = ""
            currentAuthor = ""
            currentDate = ""
            currentMessage.clear()
            inMessage = false
        }

        for (line in lines) {
            when {
                line.startsWith("commit ") -> {
                    flushEntry()
                    currentHash = line.removePrefix("commit ").trim()
                }
                line.startsWith("Author:") -> {
                    currentAuthor = line.removePrefix("Author:").trim()
                        .replace(Regex("""<.*>"""), "").trim()
                }
                line.startsWith("Date:") -> {
                    currentDate = line.removePrefix("Date:").trim()
                    inMessage = true
                }
                inMessage -> currentMessage.appendLine(line.trimStart())
            }
        }
        flushEntry()
        sb.append("</div>")
        return sb.toString()
    }

    // ── build_project renderer ────────────────────────────────────

    /**
     * Renders build results as a status card with success/fail indicator,
     * error/warning counts, duration, and message list.
     */
    private fun renderBuildResult(output: String): String? {
        val lines = output.trimEnd().lines()
        if (lines.isEmpty()) return null

        val firstLine = lines.first()
        val isSuccess = firstLine.startsWith("✓")
        val isFailed = firstLine.startsWith("✗")
        val isAborted = firstLine.startsWith("Build aborted")

        if (!isSuccess && !isFailed && !isAborted) return null

        val sb = StringBuilder("<div class='build-result'>")

        // Status header
        val statusClass = when {
            isSuccess -> "build-success"
            isFailed -> "build-fail"
            else -> "build-abort"
        }
        val icon = when {
            isSuccess -> "✓"
            isFailed -> "✗"
            else -> "⚠"
        }

        // Parse counts from "(N errors, M warnings, Xs)"
        val countsMatch = Regex("""\((\d+) errors?,\s*(\d+) warnings?,\s*([\d.]+)s\)""").find(firstLine)
        sb.append("<div class='build-header $statusClass'>")
        sb.append("<span class='build-icon'>$icon</span>")
        sb.append("<span class='build-status'>${if (isSuccess) "Build succeeded" else if (isFailed) "Build failed" else "Build aborted"}</span>")
        if (countsMatch != null) {
            val errors = countsMatch.groupValues[1]
            val warnings = countsMatch.groupValues[2]
            val duration = countsMatch.groupValues[3]
            sb.append("<span class='build-meta'>${duration}s</span>")
            if (errors != "0") sb.append(" <span class='build-errors'>$errors errors</span>")
            if (warnings != "0") sb.append(" <span class='build-warnings'>$warnings warnings</span>")
        }
        sb.append("</div>")

        // Error and warning messages
        val messages = lines.drop(1).filter { it.isNotBlank() }
        if (messages.isNotEmpty()) {
            sb.append("<div class='build-messages'>")
            for (msg in messages) {
                val trimmed = msg.trim()
                val isError = trimmed.startsWith("ERROR")
                val isWarning = trimmed.startsWith("WARN")
                val cls = when {
                    isError -> "build-msg-error"
                    isWarning -> "build-msg-warn"
                    else -> "build-msg-info"
                }
                sb.append("<div class='build-msg $cls'>${esc(trimmed)}</div>")
            }
            sb.append("</div>")
        }

        sb.append("</div>")
        return sb.toString()
    }

    private fun escJs(s: String) = s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "")
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;")
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
        sb.append("--font-family:'${font.family}';--font-size:${font.size - 2}pt;--code-font-size:${font.size - 3}pt;")
        sb.append("--fg:${rgb(fg)};--fg-a08:${rgba(fg, 0.08)};--fg-a16:${rgba(fg, 0.16)};--fg-muted:${rgba(fg, 0.55)};--bg:${rgb(bg)};")
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
        reqId: String, toolDisplayName: String, description: String, onRespond: (Boolean) -> Unit
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

    private fun langToExtension(lang: String): String = when (lang.lowercase()) {
        "java" -> "java"
        "kotlin", "kt", "kts" -> "kt"
        "python", "py" -> "py"
        "javascript", "js" -> "js"
        "typescript", "ts" -> "ts"
        "tsx" -> "tsx"
        "jsx" -> "jsx"
        "html" -> "html"
        "css" -> "css"
        "xml" -> "xml"
        "json" -> "json"
        "yaml", "yml" -> "yaml"
        "sql" -> "sql"
        "shell", "bash", "sh", "zsh" -> "sh"
        "groovy" -> "groovy"
        "scala" -> "scala"
        "rust", "rs" -> "rs"
        "go", "golang" -> "go"
        "c" -> "c"
        "cpp", "c++" -> "cpp"
        "ruby", "rb" -> "rb"
        "swift" -> "swift"
        "php" -> "php"
        "r" -> "r"
        "markdown", "md" -> "md"
        "toml" -> "toml"
        "properties" -> "properties"
        "gradle" -> "gradle"
        else -> lang.ifEmpty { "txt" }
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
                openScratch: function(lang, content) { $openScratchBridgeJs }
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
