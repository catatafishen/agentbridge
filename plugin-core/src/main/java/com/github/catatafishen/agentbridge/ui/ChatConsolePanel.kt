package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.bridge.PermissionResponse
import com.github.catatafishen.agentbridge.psi.PlatformApiCompat
import com.github.catatafishen.agentbridge.services.ChatWebServer
import com.github.catatafishen.agentbridge.services.ToolChipRegistry
import com.github.catatafishen.agentbridge.services.ToolRegistry
import com.github.catatafishen.agentbridge.settings.McpServerSettings
import com.github.catatafishen.agentbridge.settings.ScratchTypeSettings
import com.github.catatafishen.agentbridge.ui.MessageFormatter.ChipStatus
import com.github.catatafishen.agentbridge.ui.renderers.ArgumentAwareRenderer
import com.github.catatafishen.agentbridge.ui.renderers.ToolRenderers
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
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
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
    var onAutoScrollDisabled: (() -> Unit)? = null
    var onAutoScrollEnabled: (() -> Unit)? = null

    // ── Data model (same types as V1 for serialization compat) ─────
    private val entries = mutableListOf<EntryData>()

    /** Snapshot of the current entries list — safe to read from any thread as a defensive copy. */
    fun entriesSnapshot(): List<EntryData> = ArrayList(entries)
    private var currentTextData: EntryData.Text? = null
    private var currentThinkingData: EntryData.Thinking? = null
    private var nextSubAgentColor = 0
    private var turnCounter = 0
    private var currentTurnId = ""
    private var toolJustCompleted = false
    private var currentAgent = ""
    private var currentClientType = ""
    private var currentProfileId = ""
    private val toolCallNames = mutableMapOf<String, String>() // domId → tool baseName
    private val toolCallEntries = mutableMapOf<String, EntryData.ToolCall>() // domId → entry
    private val toolRegistry = ToolRegistry.getInstance(project)
    private val registry: ToolChipRegistry by lazy { ToolChipRegistry.getInstance(project) }

    private val fileNavigator = FileNavigator(project)

    private val kindStateListener = ToolChipRegistry.ChipStateWithKindListener { chipId, state, kind, mcpToolName ->
        // Use "t-$chipId" — chips are registered in the DOM as data-chip-for="t-<chipId>"
        val did = "t-$chipId"
        if (state == ToolChipRegistry.ChipState.RUNNING) {
            // MCP is handling this tool — mark as agentbridge tool (solid border) and set running
            executeJs("ChatController.markMcpHandled('$did')")
            // Mark the entry as MCP handled for persistence
            toolCallEntries[did]?.pluginTool = mcpToolName ?: toolCallNames[did]
        } else {
            // COMPLETE, EXTERNAL, FAILED — just remove the spinner; border already shows origin
            val jsState = if (state == ToolChipRegistry.ChipState.FAILED) "failed" else "complete"
            executeJs("ChatController.setToolChipState('$did','$jsState')")
            toolJustCompleted = true
        }
        if (kind != null) {
            val jsKind = kind.replace("'", "\\'")
            executeJs("ChatController.updateToolCallKind('$did','$jsKind')")
            toolCallEntries[did]?.kind = kind
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
    private var autoScrollDisabledBridgeJs = ""
    private var autoScrollEnabledBridgeJs = ""

    @Volatile
    private var htmlPageFuture: java.util.concurrent.CompletableFuture<String>? = null
    private val pendingPermissionCallbacks =
        java.util.concurrent.ConcurrentHashMap<String, (PermissionResponse) -> Unit>()
    private val pendingAskUserCallbacks = java.util.concurrent.ConcurrentHashMap<String, (String) -> Unit>()

    @Volatile
    private var activeAskUserRequestId: String? = null

    // CEF windowless frame rate — high during streaming, moderate when idle.
    // 10fps was too aggressive — caused stale-frame tearing during manual scroll.
    private fun setFrameRate(fps: Int) {
        browser?.cefBrowser?.setWindowlessFrameRate(fps)
    }

    // Periodic CEF invalidation during streaming — forces OSR buffer refresh
    // as a safety net against compositor desync during rapid content changes.
    private val repaintTimer = javax.swing.Timer(200) {
        browser?.cefBrowser?.invalidate()
    }.apply { isRepeats = true }

    // ── Swing fallback ─────────────────────────────────────────────
    private val fallbackArea: JBTextArea?

    companion object {
        private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(ChatConsolePanel::class.java)
        private val QUICK_REPLY_TAG_REGEX = Regex("\\[\\s*quick-reply:\\s*([^]]+)]")

        /** Active panels keyed by project — used by MCP tool to retrieve page HTML. */
        private val instances = java.util.concurrent.ConcurrentHashMap<Project, ChatConsolePanel>()
        fun getInstance(project: Project): ChatConsolePanel? = instances[project]

        private const val FAILED_SPAN = "<span style='color:var(--error)'>✖ Failed</span>"
        private const val STREAMING_FRAME_RATE = 60
        private const val IDLE_FRAME_RATE = 30

        private val GIT_HISTORY_TOOLS = setOf("git_log", "git_show")
    }

    // ── Init ───────────────────────────────────────────────────────

    init {
        if (JBCefApp.isSupported()) {
            browser = JBCefBrowser()
            val panelBg = JBUI.CurrentTheme.ToolWindow.background()
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
                parsePermissionResponse(data)?.let { (reqId, response) ->
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

            val autoScrollDisabledQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase)
            autoScrollDisabledQuery.addHandler { onAutoScrollDisabled?.invoke(); null }
            Disposer.register(this, autoScrollDisabledQuery)
            autoScrollDisabledBridgeJs = autoScrollDisabledQuery.inject("''")

            val autoScrollEnabledQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase)
            autoScrollEnabledQuery.addHandler { onAutoScrollEnabled?.invoke(); null }
            Disposer.register(this, autoScrollEnabledQuery)
            autoScrollEnabledBridgeJs = autoScrollEnabledQuery.inject("''")

            add(browser.component, BorderLayout.CENTER)

            browser.jbCefClient.addLoadHandler(
                PlatformApiCompat.createMainFrameLoadEndHandler {
                    ApplicationManager.getApplication().invokeLater {
                        browserReady = true
                        pendingJs.forEach { browser.cefBrowser.executeJavaScript(it, "", 0) }
                        pendingJs.clear()
                        if (McpServerSettings.getInstance(project).isSmoothScrollEnabled) {
                            setSmoothScroll(true)
                        }
                        if (!McpServerSettings.getInstance(project).isShowTurnStats) {
                            setShowTurnStats(false)
                        }
                    }
                }, browser.cefBrowser
            )

            browser.jbCefClient.addDisplayHandler(
                PlatformApiCompat.createConsoleLogHandler(
                    com.intellij.openapi.diagnostic.Logger.getInstance(ChatConsolePanel::class.java)
                ), browser.cefBrowser
            )

            browser.loadHTML(buildInitialPage())
            fallbackArea = null

            PlatformApiCompat.subscribeLafChanges(this) { updateThemeColors() }
            setupMonitorChangeListener()
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

    fun setAutoScroll(enabled: Boolean) {
        executeJs("ChatController.setAutoScroll($enabled)")
    }

    fun setSmoothScroll(enabled: Boolean) {
        executeJs("document.querySelector('chat-container').style.scrollBehavior = '${if (enabled) "smooth" else "auto"}'")
    }

    fun setShowTurnStats(enabled: Boolean) {
        executeJs("document.documentElement.classList.toggle('hide-turn-stats', ${!enabled})")
    }

    override fun addPromptEntry(
        text: String,
        contextFiles: List<Triple<String, String, Int>>?,
        bubbleHtml: String?
    ): String {
        toolJustCompleted = false
        finalizeCurrentText()
        collapseThinking()
        currentTurnId = "t${turnCounter++}"
        val ts = timestamp()
        val ctxRefs = contextFiles?.map { (name, path, line) -> ContextFileRef(name, path, line) }
        entries.add(EntryData.Prompt(text, ts, ctxRefs, id = currentTurnId))
        val encodedBubble = if (bubbleHtml != null) encodeBase64(bubbleHtml) else ""
        executeJs("ChatController.addUserMessage('${escJs(text)}','${displayTs(ts)}','$encodedBubble','$currentTurnId');ChatController.showWorkingIndicator()")
        fireEntriesChanged()
        return currentTurnId
    }

    override fun removePromptEntry(entryId: String) {
        val idx = entries.indexOfLast { it is EntryData.Prompt && it.id == entryId }
        if (idx >= 0) entries.removeAt(idx)
        executeJs("ChatController.removeUserMessage('$entryId')")
        fireEntriesChanged()
    }

    override fun startStreaming() {
        setFrameRate(STREAMING_FRAME_RATE)
        repaintTimer.start()
        // Disable smooth scroll during streaming — CSS scroll animations conflict
        // with rapid programmatic scrollTop changes, causing JCEF OSR tearing.
        executeJs("document.querySelector('chat-container')?.setStreaming(true, false)")
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
        currentProfileId = profileId
        val agentCss = ChatTheme.activeAgentCss(profileId)
        val isDark = com.intellij.ide.ui.LafManager.getInstance().currentUIThemeLookAndFeel.isDark
        val iconSvg = ChatTheme.getAgentIconSvg(profileId, isDark)
        // Apply per-agent CSS variables to the JCEF document root only.
        // Starts with "document." so it is intentionally filtered from the web app event log.
        executeJs("document.documentElement.style.cssText += '$agentCss'")
        // Notify the client type change — pushed to both JCEF and the web app event log.
        executeJs("ChatController.setClientType('${escJs(clientType)}', '${escJs(iconSvg)}')")
    }

    override fun addContextFilesEntry(files: List<Pair<String, String>>) {
        entries.add(EntryData.ContextFiles(files.map { (name, path) -> FileRef(name, path) }))
    }

    override fun appendThinkingText(text: String) {
        maybeStartNewSegment()
        val thinkingTs = if (currentThinkingData == null) {
            val ts = timestamp()
            currentThinkingData = EntryData.Thinking("", ts, currentAgent).also { entries.add(it) }
            displayTs(ts)
        } else {
            displayTs(currentThinkingData!!.timestamp)
        }
        currentThinkingData!!.raw += text
        executeJs("ChatController.addThinkingText('$currentTurnId','main','${escJs(text)}','$thinkingTs')")
    }

    override fun collapseThinking() {
        if (currentThinkingData == null) return
        val raw = currentThinkingData!!.raw
        currentThinkingData = null
        val encoded = encodeBase64(markdownToHtml(raw))
        executeJs("ChatController.collapseThinking('$currentTurnId','main','$encoded')")
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
            currentTextData = EntryData.Text("", timestamp(), currentAgent).also { entries.add(it) }
        }
        currentTextData!!.raw += text
        val ts = displayTs(currentTextData!!.timestamp)
        executeJs("ChatController.appendAgentText('$currentTurnId','main','${escJs(text)}','$ts')")
        fallbackArea?.let { ApplicationManager.getApplication().invokeLater { it.append(text) } }
    }

    override fun addToolCallEntry(
        id: String,
        title: String,
        arguments: String?,
        kind: String?
    ) {
        val cleanTitle = title.trim('\'', '"')

        // Defensive guard: task_complete should never reach here — PromptOrchestrator
        // suppresses it. If it does (e.g. stale classloader), render as text instead.
        if (cleanTitle == "task_complete") {
            LOG.warn("task_complete reached addToolCallEntry — rendering as text instead of chip")
            val summary = extractTaskCompleteSummary(arguments)
            if (summary.isNotBlank()) appendText(summary)
            return
        }

        finalizeCurrentText()
        val resolvedKind = kind ?: "other"

        // Extract file path from arguments for edit tools
        val filePath = extractFilePathFromArgs(arguments)

        val entry =
            EntryData.ToolCall(
                cleanTitle, arguments, resolvedKind, null, null, null, filePath,
                autoDenied = false, denialReason = null,
                timestamp = timestamp(), agent = currentAgent
            )
        entries.add(entry)

        val reg = registerToolChip(cleanTitle, arguments, resolvedKind, id)
        toolCallNames[reg.domId] = cleanTitle
        toolCallEntries[reg.domId] = entry
        if (reg.isMcpHandled) entry.pluginTool = cleanTitle

        val initialStatus = if (reg.isMcpHandled) ChipStatus.RUNNING else ChipStatus.PENDING
        val entryTs = displayTs(entry.timestamp)
        executeJs("ChatController.upsertToolChip('$currentTurnId','main','${reg.domId}','${escJs(reg.label)}','${reg.paramsJson}',{kind:'${reg.safeKind}',status:'$initialStatus',timestamp:'$entryTs'})")
        if (reg.isMcpHandled) {
            executeJs("ChatController.markMcpHandled('${reg.domId}')")
        }
    }

    override fun updateToolCall(id: String, status: String, update: ChatPanelApi.ToolCallUpdate) {
        val chipId = registry.findChipIdByClientId(id)
        var did = if (chipId != null) "t-$chipId" else domId(id)

        if (update.arguments != null && status == "running") {
            did = reCorrelateChipIfNeeded(id, update.arguments, did, update.title, update.kind)
        }

        val resultLen = update.details?.length ?: 0
        LOG.debug("updateToolCall: id=$id, chipId=$chipId, status=$status, resultLen=$resultLen, hasDesc=${update.description != null}, denied=${update.autoDenied}")
        applyEntryDataUpdate(
            did,
            ToolCallStatusData(
                chipId,
                update.details,
                status,
                update.autoDenied,
                update.denialReason,
                update.description,
                update.kind
            )
        )

        // For intermediate running state, update DOM immediately
        if (status == "running") {
            executeJs("ChatController.setToolChipState('$did','running')")
            if (update.kind != null) {
                val jsKind = update.kind.replace("'", "\\'")
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

        if (update.autoDenied) {
            executeJs("ChatController.setToolChipState('$did','denied')")
        }
    }

    /** Re-correlates a tool-chip's DOM id and entry maps when arguments become available at runtime. Returns the updated did. */
    private fun reCorrelateChipIfNeeded(
        id: String, arguments: String, currentDid: String, title: String?, kind: String?
    ): String {
        return try {
            val argsObj = JsonParser.parseString(arguments).asJsonObject
            val registration = registry.reregisterWithArgs(id, argsObj)
            val newDid = "t-${registration.chipId()}"
            if (newDid == currentDid) return currentDid

            val entry = toolCallEntries.remove(currentDid)
            if (entry != null) toolCallEntries[newDid] = entry
            val name = toolCallNames.remove(currentDid)
            if (name != null) toolCallNames[newDid] = name

            // Remove old chip DOM element
            executeJs("ChatController.removeToolChip('$currentDid')")

            // Create new chip with correct hash-based ID
            val cleanTitle = (title ?: name ?: "Tool").trim('\'', '"')
            val kindFallback = entry?.kind ?: "other"
            val resolvedKind = kind ?: kindFallback
            val label = toolChipTitle(cleanTitle, arguments)
            val hasCustomRenderer = ToolRenderers.hasRenderer(cleanTitle, toolRegistry)
            val paramsJson = if (!hasCustomRenderer) escJs(arguments) else ""

            executeJs(
                "ChatController.upsertToolChip('$currentTurnId','main','$newDid','${escJs(label)}','$paramsJson',{kind:'${
                    escJs(
                        resolvedKind
                    )
                }',status:'running'})"
            )

            if (registration.initialState() == ToolChipRegistry.ChipState.RUNNING) {
                executeJs("ChatController.markMcpHandled('$newDid')")
                toolCallEntries[newDid]?.pluginTool = toolCallNames[newDid]
            }
            LOG.debug("updateToolCall: re-correlated chip $id: $currentDid -> $newDid")
            newDid
        } catch (e: Exception) {
            LOG.warn("updateToolCall: failed to re-correlate chip $id", e)
            currentDid
        }
    }

    private data class ToolCallStatusData(
        val chipId: String?, val details: String?, val status: String,
        val autoDenied: Boolean, val denialReason: String?, val description: String?, val kind: String?
    )

    /** Updates the in-memory entry data for a tool-call chip from the latest status report. */
    private fun applyEntryDataUpdate(did: String, update: ToolCallStatusData) {
        val storedResult = update.chipId?.let { cid -> registry.getStoredPluginResult(cid) }
        toolCallEntries[did]?.let {
            // Prefer the actual MCP execution result over what the ACP reported.
            // Copilot CLI may send tool_call_update:failed with no error text even when our MCP
            // tool returned a detailed error message. The stored plugin result is more accurate.
            it.result = storedResult ?: update.details
            it.status = update.status
            it.autoDenied = update.autoDenied
            it.denialReason = update.denialReason
            if (update.description != null) it.description = update.description
            if (update.kind != null) it.kind = update.kind
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
        val resolvedKind = kind ?: "other"
        val cleanTitle = title.trim('\'', '"')

        val reg = registerToolChip(cleanTitle, arguments, resolvedKind, toolId)
        val isExternal = !reg.isMcpHandled

        val entry = EntryData.ToolCall(
            cleanTitle, arguments, resolvedKind,
            timestamp = timestamp(), agent = currentAgent
        )
        if (reg.isMcpHandled) entry.pluginTool = cleanTitle
        toolCallNames[reg.domId] = cleanTitle
        toolCallEntries[reg.domId] = entry
        entries.add(entry)

        executeJs("ChatController.addSubAgentToolCall('$saDid','${reg.domId}','${escJs(reg.label)}','${reg.paramsJson}','${reg.safeKind}',$isExternal)")
        if (reg.isMcpHandled) {
            executeJs("ChatController.markMcpHandled('${reg.domId}')")
        }
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
        val chipId = registry.findChipIdByClientId(toolId)
        val did = if (chipId != null) "t-$chipId" else domId(toolId)
        toolCallEntries[did]?.let {
            it.result = details
            it.status = status
            it.autoDenied = autoDenied
            it.denialReason = denialReason
            if (description != null) it.description = description
        }
        val jsStatus = if (autoDenied) ChipStatus.DENIED else when (status) {
            "failed" -> ChipStatus.FAILED
            "running" -> ChipStatus.RUNNING
            else -> ChipStatus.COMPLETE
        }
        executeJs("ChatController.updateToolCall('$did','$jsStatus','$jsStatus')")
    }

    override fun addSubAgentEntry(
        id: String, agentType: String, description: String, prompt: String?,
        initialState: ChatPanelApi.SubAgentInitialState
    ) {
        maybeStartNewSegment()
        finalizeCurrentText()
        val colorIndex = nextSubAgentColor++ % ChatTheme.SA_COLOR_COUNT
        val entry = EntryData.SubAgent(
            agentType, description, prompt,
            colorIndex = colorIndex, callId = id,
            autoDenied = initialState.autoDenied, denialReason = initialState.denialReason,
            timestamp = timestamp(), agent = currentAgent
        )
        if (initialState.result != null) {
            entry.result = initialState.result; entry.status = initialState.status
            if (initialState.description != null) entry.result = "${initialState.description}\n\n${initialState.result}"
        }
        entries.add(entry)
        val did = domId(id)
        val info = SUB_AGENT_INFO[agentType]
        val displayName = info?.displayName ?: agentType.replaceFirstChar { it.uppercaseChar() }
        val promptText = prompt ?: description
        val promptHtml = encodeBase64(markdownToHtml(promptText))
        val ts = displayTs(entry.timestamp)
        executeJs(
            "ChatController.addSubAgent('$currentTurnId','main','$did','${escJs(displayName)}',$colorIndex,'$promptHtml','${
                escJs(
                    ts
                )
            }')"
        )
        if (initialState.autoDenied || !initialState.result.isNullOrBlank() || initialState.status == "completed" || initialState.status == "failed") {
            val status = if (initialState.autoDenied) "denied" else (initialState.status ?: "completed")
            renderSubAgentResult(did, status, initialState.autoDenied, initialState.result)
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
        renderSubAgentResult(did, status, autoDenied, result)
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
        fireEntriesChanged()
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
        fireEntriesChanged()
    }

    override fun finishResponse(toolCallCount: Int, modelId: String, multiplier: String) {
        setFrameRate(IDLE_FRAME_RATE)
        repaintTimer.stop()
        val smooth = McpServerSettings.getInstance(project).isSmoothScrollEnabled
        executeJs("document.querySelector('chat-container')?.setStreaming(false, $smooth)")
        toolJustCompleted = false
        finalizeCurrentText()
        collapseThinking()
        val statsJson = """{"tools":$toolCallCount,"model":"${escJs(modelId)}","mult":"${escJs(multiplier)}"}"""

        // Clear the chip registry for this turn
        registry.clearTurn()

        executeJs("ChatController.finalizeTurn('$currentTurnId',$statsJson)")
        ChatWebServer.getInstance(project)
            ?.pushNotification("Turn complete", "Agent finished ($toolCallCount tool calls)")
    }

    override fun emitTurnStats(stats: TurnStatsData) {
        val prev = entries.filterIsInstance<EntryData.TurnStats>().lastOrNull()
        entries.add(
            EntryData.TurnStats(
                turnId = currentTurnId,
                durationMs = stats.durationMs,
                inputTokens = stats.inputTokens.toLong(),
                outputTokens = stats.outputTokens.toLong(),
                costUsd = stats.costUsd,
                toolCallCount = stats.toolCallCount,
                linesAdded = stats.linesAdded,
                linesRemoved = stats.linesRemoved,
                model = stats.model,
                multiplier = stats.multiplier,
                totalDurationMs = (prev?.totalDurationMs ?: 0) + stats.durationMs,
                totalInputTokens = (prev?.totalInputTokens ?: 0) + stats.inputTokens.toLong(),
                totalOutputTokens = (prev?.totalOutputTokens ?: 0) + stats.outputTokens.toLong(),
                totalCostUsd = (prev?.totalCostUsd ?: 0.0) + stats.costUsd,
                totalToolCalls = (prev?.totalToolCalls ?: 0) + stats.toolCallCount,
                totalLinesAdded = (prev?.totalLinesAdded ?: 0) + stats.linesAdded,
                totalLinesRemoved = (prev?.totalLinesRemoved ?: 0) + stats.linesRemoved,
                timestamp = java.time.Instant.now().toString(),
                commitHashes = stats.commitHashes,
            )
        )
        val statsJson = buildTurnSummaryJson(stats)
        executeJs("ChatController.renderTurnSummary($statsJson)")
        fireEntriesChanged()
    }

    private fun buildTurnSummaryJson(stats: TurnStatsData): String {
        val parts = mutableListOf<String>()
        parts.add("\"duration\":${stats.durationMs}")
        parts.add("\"inputTokens\":${stats.inputTokens}")
        parts.add("\"outputTokens\":${stats.outputTokens}")
        parts.add("\"tools\":${stats.toolCallCount}")
        parts.add("\"added\":${stats.linesAdded}")
        parts.add("\"removed\":${stats.linesRemoved}")
        parts.add("\"model\":\"${escJs(stats.model)}\"")
        if (stats.multiplier.isNotEmpty()) parts.add("\"multiplier\":\"${escJs(stats.multiplier)}\"")
        return "{${parts.joinToString(",")}}"
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
        setFrameRate(IDLE_FRAME_RATE)
        repaintTimer.stop()
        val smooth = McpServerSettings.getInstance(project).isSmoothScrollEnabled
        executeJs("document.querySelector('chat-container')?.setStreaming(false, $smooth)")
        clearPendingAskUserRequest(null)
        executeJs("ChatController.cancelAllRunning()")
    }

    // ── Conversation export ────────────────────────────────────────

    private val exporter: ConversationExporter get() = ConversationExporter(entries)
    override fun getConversationText(): String = exporter.getConversationText()
    override fun getCompressedSummary(maxChars: Int): String = exporter.getCompressedSummary(maxChars)
    override fun getConversationHtml(): String = exporter.getConversationHtml()

    override fun getLastResponseText(): String =
        entries.filterIsInstance<EntryData.Text>().lastOrNull()?.raw ?: ""

    // ── History / persistence API ──────────────────────────────────

    var onLoadMoreRequested: (() -> Unit)? = null

    /**
     * Listeners notified whenever the entry list changes (prompt added/removed, batch appended,
     * or conversation replayed). Used by side panels (Prompts tab) to keep their views in sync.
     * Listeners run on the caller's thread — typically EDT since mutations happen from UI flows.
     */
    private val entriesChangeListeners = java.util.concurrent.CopyOnWriteArrayList<Runnable>()

    fun addEntriesChangeListener(listener: Runnable) {
        entriesChangeListeners.add(listener)
    }

    fun removeEntriesChangeListener(listener: Runnable) {
        entriesChangeListeners.remove(listener)
    }

    private fun fireEntriesChanged() {
        entriesChangeListeners.forEach { it.run() }
    }

    fun getEntries(): List<EntryData> = entries.toList()

    /**
     * Scrolls the JCEF chat to the chat-message with the given entry id. No-op if the element
     * does not exist (e.g. the entry was restored from disk into the deferred history pile).
     *
     * Autoscroll is disabled for the duration of the navigation animation (900 ms) so the
     * ResizeObserver / MutationObserver cannot re-anchor to the bottom while the smooth-scroll
     * is in flight. After the animation, autoscroll is silently restored so future incoming
     * messages continue to scroll into view.
     */
    fun scrollToEntry(entryId: String) {
        val safe = escJs(entryId)
        executeJs(
            """
            (function(){
                var el = document.getElementById('$safe');
                if (!el) return;
                ChatController.setAutoScroll(false);
                el.scrollIntoView({behavior:'smooth', block:'center'});
                el.classList.add('prompt-flash');
                setTimeout(function(){
                    el.classList.remove('prompt-flash');
                    ChatController.resumeAutoScroll();
                }, 900);
            })()
            """.trimIndent()
        )
    }

    fun showLoadMore(deferredCount: Int) {
        executeJs("ChatController.showLoadMore($deferredCount)")
    }

    fun hideLoadMore() {
        executeJs("ChatController.removeLoadMore()")
    }

    fun setDomMessageLimit(limit: Int) {
        executeJs("ChatController.setDomMessageLimit($limit)")
    }

    fun appendEntries(entries: List<EntryData>, totalPromptCount: Int = -1) {
        if (entries.isEmpty()) return
        this.entries.addAll(entries)
        val count = if (totalPromptCount >= 0) totalPromptCount
        else entries.count { it is EntryData.Prompt }
        if (count > 0) turnCounter += count
        val json = serializeBatchTurns(entries)
        if (json != "[]") {
            val smooth = McpServerSettings.getInstance(project).isSmoothScrollEnabled
            executeJs("ChatController.restoreBatchFinal('${encodeBase64(json)}', $smooth)")
        }
        fireEntriesChanged()
    }

    fun prependEntries(entries: List<EntryData>) {
        if (entries.isEmpty()) return
        this.entries.addAll(0, entries)
        val json = serializeBatchTurns(entries)
        if (json != "[]") {
            executeJs("ChatController.prependBatch('${encodeBase64(json)}')")
        }
        fireEntriesChanged()
    }

    private fun buildRestoredBubbleHtml(text: String, ctxFiles: List<ContextFileRef>): String {
        var result = esc(text)
        for (ref in ctxFiles) {
            val href = if (ref.line > 0) "openfile://${ref.path}:${ref.line}" else "openfile://${ref.path}"
            val title = esc(if (ref.line > 0) "${ref.path}:${ref.line}" else ref.path)
            val chip = "<a class='prompt-ctx-chip' href='$href' title='$title'>${esc(ref.name)}</a>"
            result = result.replaceFirst("`${esc(ref.name)}`", chip)
        }
        return result
    }

    private var batchIdCounter = 0

    private fun normalizeChipStatus(raw: String?): String = ToolCallArgParser.normalizeChipStatus(raw)

    private fun serializeBatchTurns(entries: List<EntryData>): String {
        val turns = mutableListOf<Map<String, Any?>>()
        var i = 0
        while (i < entries.size) {
            when (val e = entries[i]) {
                is EntryData.Prompt -> {
                    turns.add(serializeUserTurn(e))
                    i++
                }

                is EntryData.Nudge -> {
                    // Only sent nudges appear in replay; pending ones are transient UI state.
                    if (e.sent) turns.add(serializeNudgeTurn(e))
                    i++
                }

                is EntryData.SessionSeparator -> {
                    turns.add(
                        mapOf(
                            "type" to "separator",
                            "timestamp" to displayTsSeparator(e.timestamp),
                            "agent" to e.agent
                        )
                    )
                    i++
                }

                is EntryData.Status, is EntryData.ContextFiles -> i++
                is EntryData.TurnStats -> {
                    turns.add(serializeTurnStatsTurn(e))
                    i++
                }

                else -> {
                    val (turn, nextI) = serializeAgentTurn(entries, i)
                    turns.add(turn)
                    i = nextI
                }
            }
        }
        return com.google.gson.Gson().toJson(turns)
    }

    private fun serializeUserTurn(e: EntryData.Prompt): Map<String, Any?> {
        val bubbleHtml = if (!e.contextFiles.isNullOrEmpty()) {
            buildRestoredBubbleHtml(e.text, e.contextFiles)
        } else {
            esc(e.text)
        }
        return mapOf(
            "type" to "user",
            "html" to bubbleHtml,
            "timestamp" to displayTs(e.timestamp),
            "entryId" to e.id.ifEmpty { e.entryId }
        )
    }

    private fun serializeNudgeTurn(e: EntryData.Nudge): Map<String, Any?> =
        mapOf(
            "type" to "nudge_sent",
            "html" to esc(e.text),
            "timestamp" to displayTs(e.timestamp)
        )

    private fun serializeTurnStatsTurn(e: EntryData.TurnStats): Map<String, Any?> {
        val m = mutableMapOf<String, Any?>(
            "type" to "stats",
            "duration" to e.durationMs,
            "inputTokens" to e.inputTokens,
            "outputTokens" to e.outputTokens,
            "tools" to e.toolCallCount,
            "added" to e.linesAdded,
            "removed" to e.linesRemoved,
            "model" to e.model,
        )
        if (e.multiplier.isNotEmpty()) m["multiplier"] = e.multiplier
        return m
    }

    /** Adds a sent nudge entry to the in-memory entries list for persistence. */
    override fun addNudgeEntry(id: String, text: String) {
        val ts = java.time.Instant.now().toString()
        entries.add(EntryData.Nudge(text = text, id = id, sent = true, timestamp = ts))
    }

    private fun serializeAgentTurn(
        entries: List<EntryData>, startI: Int
    ): Pair<Map<String, Any?>, Int> {
        var i = startI
        var currentSegmentEntries = mutableListOf<Map<String, Any?>>()
        var currentTimestamp = ""
        var hadToolOrSubagent = false
        var agent = ""
        val segments = mutableListOf<Map<String, Any?>>()

        fun flushSegment() {
            if (currentSegmentEntries.isEmpty()) return
            segments.add(
                mapOf(
                    "timestamp" to displayTs(currentTimestamp),
                    "entries" to currentSegmentEntries
                )
            )
            currentSegmentEntries = mutableListOf()
            currentTimestamp = ""
            hadToolOrSubagent = false
        }

        while (i < entries.size) {
            val e = entries[i]
            if (isAgentTurnEnd(e)) break
            if (e is EntryData.ContextFiles) {
                i++; continue
            }
            if (shouldStartNewSegment(hadToolOrSubagent, e)) flushSegment()

            if (currentSegmentEntries.isEmpty()) {
                currentTimestamp = e.timestamp
                agent = agent.ifEmpty { resolveEntryAgent(e) }
            }

            serializeEntry(e)?.let { currentSegmentEntries.add(it) }
            if (isToolOrSubagentEntry(e)) hadToolOrSubagent = true
            i++
        }
        flushSegment()

        val turn = mapOf(
            "type" to "agent",
            "agent" to agent,
            "segments" to segments
        )
        return turn to i
    }

    /** Returns true when the entry signals the end of an agent turn (prompt, nudge, separator, status, or stats). */
    private fun isAgentTurnEnd(e: EntryData): Boolean =
        e is EntryData.Prompt || e is EntryData.Nudge || e is EntryData.SessionSeparator
            || e is EntryData.Status || e is EntryData.TurnStats

    /** Returns true when a new segment should be started: there was a prior tool/subagent call and the next entry is text or thinking. */
    private fun shouldStartNewSegment(hadToolOrSubagent: Boolean, e: EntryData): Boolean =
        hadToolOrSubagent && (e is EntryData.Text || e is EntryData.Thinking)

    /** Returns true when [e] is a ToolCall or SubAgent entry (marks that a segment break may follow). */
    private fun isToolOrSubagentEntry(e: EntryData): Boolean =
        e is EntryData.ToolCall || e is EntryData.SubAgent

    /** Resolves the agent name from any AgentTurn entry type; returns an empty string for entry types that carry no agent. */
    private fun resolveEntryAgent(e: EntryData): String = when (e) {
        is EntryData.Text -> e.agent
        is EntryData.Thinking -> e.agent
        is EntryData.ToolCall -> e.agent
        is EntryData.SubAgent -> e.agent
        else -> ""
    }

    private fun serializeEntry(e: EntryData): Map<String, Any?>? {
        return when (e) {
            is EntryData.Thinking -> {
                val raw = e.raw
                if (raw.isBlank()) return null
                val id = "batch-think-${batchIdCounter++}"
                mapOf("type" to "thinking", "id" to id, "html" to markdownToHtml(raw))
            }

            is EntryData.ToolCall -> {
                val title = e.title.trim('\'', '"')
                val label = toolChipTitle(title, e.arguments)
                val id = "batch-tool-${batchIdCounter++}"
                val status = normalizeChipStatus(e.status)
                toolCallNames[id] = title
                toolCallEntries[id] = EntryData.ToolCall(
                    title, e.arguments, e.kind,
                    result = e.result, status = status, description = e.description,
                    pluginTool = e.pluginTool
                )
                val map = mutableMapOf<String, Any?>(
                    "type" to "tool",
                    "id" to id,
                    "label" to label,
                    "kind" to e.kind,
                    "status" to status
                )
                if (e.arguments != null) map["params"] = e.arguments
                if (e.pluginTool != null) map["pluginTool"] = e.pluginTool
                map
            }

            is EntryData.Text -> {
                val raw = e.raw
                if (raw.isBlank()) return null
                val clean = raw.replace(QUICK_REPLY_TAG_REGEX, "").trimEnd()
                if (clean.isBlank()) return null
                mapOf("type" to "text", "html" to markdownToHtml(clean))
            }

            is EntryData.SubAgent -> {
                val saInfo = SUB_AGENT_INFO[e.agentType]
                val displayName = saInfo?.displayName ?: e.agentType.replaceFirstChar { it.uppercaseChar() }
                val resultHtml = if (!e.result.isNullOrBlank()) markdownToHtml(e.result!!) else "Completed"
                val id = "batch-sa-${batchIdCounter++}"
                val status = if (e.autoDenied) ChipStatus.DENIED else (e.status ?: ChipStatus.COMPLETE)
                mapOf(
                    "type" to "subagent",
                    "id" to id,
                    "label" to displayName,
                    "status" to status,
                    "colorIndex" to e.colorIndex,
                    "resultHtml" to resultHtml
                )
            }

            else -> null
        }
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
        val rawText = data.raw
        if (rawText.isBlank()) {
            executeJs("ChatController.finalizeAgentText('$turnId','main',null)")
            entries.remove(data); return
        }
        val cleanText = rawText.replace(QUICK_REPLY_TAG_REGEX, "").trimEnd()
        val html = markdownToHtml(cleanText)
        val encoded = encodeBase64(html)
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

    private fun handleFileLink(href: String) = fileNavigator.handleFileLink(href)

    private fun markdownToHtml(text: String): String = fileNavigator.markdownToHtml(text)

    // ── Tool result panel rendering ─────────────────────────────

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
                border = JBUI.Borders.empty(0, 0, 8, 0)
                alignmentX = LEFT_ALIGNMENT
            })
        }

        // 1. Show natural language description/explanation if available
        if (!description.isNullOrBlank()) {
            container.add(JBLabel("<html><body style='width: 450px'>${markdownToHtml(description)}</body></html>").apply {
                border = JBUI.Borders.empty(0, 0, 8, 0)
                alignmentX = LEFT_ALIGNMENT
            })
        }

        // 2. For failed tools, show error details prominently
        if (status == "failed") {
            return renderFailedToolPanel(container, baseName, details)
        }

        // 3. Resolve the result to display — fall back to showing arguments when no output was produced
        val finalDetails = resolveDisplayDetails(details, arguments)
        if (finalDetails.isNullOrBlank()) {
            return buildEmptyResultContainer(container, baseName, status)
        }

        // 4. Attempt to use a custom renderer for the result
        if (baseName != null && tryRenderWithCustomRenderer(container, baseName, finalDetails, arguments)) {
            return container
        }

        // 5. Fallback: monospace code or JSON editor; long text gets a scratch-file link
        val fallbackContent = if (isJson(finalDetails)) {
            ToolRenderers.jsonEditor(prettyJson(finalDetails), project)
        } else {
            ToolRenderers.codeOrScratchPanel(finalDetails)
        }
        container.add(fallbackContent)

        return container
    }

    /**
     * Returns the result string to display.
     * Falls back to "Parameters: $arguments" when [details] is blank but arguments are present,
     * so the user can see what was called even when the agent did not stream raw tool output.
     */
    private fun resolveDisplayDetails(details: String?, arguments: String?): String? =
        if (details.isNullOrBlank() && !arguments.isNullOrBlank()) "Parameters: $arguments" else details

    /** Adds an empty-result notice label to [container] and returns [container]. */
    private fun buildEmptyResultContainer(container: JBPanel<*>, baseName: String?, status: String?): JComponent {
        val label = when (status) {
            "running" -> "⏳ Running…"
            else -> if (baseName != null) "Tool $baseName completed with no output." else "Completed"
        }
        container.add(JBLabel(label).apply {
            foreground = ToolRenderers.MUTED_COLOR
            border = JBUI.Borders.empty(4, 0)
            alignmentX = LEFT_ALIGNMENT
        })
        return container
    }

    /**
     * Attempts to render [finalDetails] with a registered custom renderer for [baseName].
     * Adds the rendered component to [container] and returns `true`; returns `false` when no renderer is available.
     */
    private fun tryRenderWithCustomRenderer(
        container: JBPanel<*>, baseName: String, finalDetails: String, arguments: String?
    ): Boolean {
        val renderer = ToolRenderers[baseName, toolRegistry]
        LOG.debug("Renderer for $baseName: ${renderer?.javaClass?.simpleName ?: "null"}")
        val rendered = when (renderer) {
            is ArgumentAwareRenderer -> renderer.render(finalDetails, arguments)
            else -> renderer?.render(finalDetails)
        }
        if (rendered != null) {
            container.add(rendered)
            return true
        }
        return false
    }

    private fun renderFailedToolPanel(
        container: JBPanel<*>,
        baseName: String?,
        details: String?
    ): JComponent {
        if (details.isNullOrBlank()) {
            val toolLabel = if (baseName != null) "✖ $baseName — Denied or Failed" else "✖ Denied or Failed"
            container.add(JBLabel(toolLabel).apply {
                foreground = ToolRenderers.FAIL_COLOR
                font = JBUI.Fonts.label().asBold()
                border = JBUI.Borders.empty(4, 0, 4, 0)
                alignmentX = LEFT_ALIGNMENT
            })
            container.add(JBLabel("<html><body style='width:450px'>No error details were provided. This typically happens when the tool was denied in the terminal's permission prompt, or when the agent exited before producing output.</body></html>").apply {
                foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground()
                border = JBUI.Borders.empty(0, 0, 4, 0)
                alignmentX = LEFT_ALIGNMENT
            })
            return container
        }

        // Error header
        container.add(JBLabel("✖ Error").apply {
            foreground = ToolRenderers.FAIL_COLOR
            font = JBUI.Fonts.label().asBold()
            border = JBUI.Borders.empty(4, 0, 4, 0)
            alignmentX = LEFT_ALIGNMENT
        })

        // Error details in a red-bordered panel
        val errorBorderColor = com.intellij.ui.JBColor(
            java.awt.Color(0xCF, 0x22, 0x2E, 0x40),
            java.awt.Color(0xF8, 0x53, 0x49, 0x40)
        )
        val errorBgColor = com.intellij.ui.JBColor(
            java.awt.Color(0xCF, 0x22, 0x2E, 0x0A),
            java.awt.Color(0xF8, 0x53, 0x49, 0x0A)
        )
        val errorContent = ToolRenderers.codeOrScratchPanel(details)
        val errorPanel = JBPanel<JBPanel<*>>().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            background = errorBgColor
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(errorBorderColor, 1),
                JBUI.Borders.empty(6)
            )
            alignmentX = LEFT_ALIGNMENT
            add(errorContent)
        }
        container.add(errorPanel)

        return container
    }

    // ── Helpers (delegated to MessageFormatter) ─────────────────────

    private fun escJs(s: String) = MessageFormatter.escapeJs(s)
    private fun esc(s: String) = MessageFormatter.escapeHtml(s)
    private fun encodeBase64(s: String) = MessageFormatter.encodeBase64(s)
    private fun timestamp() = MessageFormatter.timestamp()
    private fun displayTs(iso: String) = MessageFormatter.formatTimestamp(iso)
    private fun displayTsSeparator(iso: String) =
        MessageFormatter.formatTimestamp(iso, MessageFormatter.TimestampStyle.FULL)

    private fun formatToolSubtitle(baseName: String, arguments: String?) =
        MessageFormatter.formatToolSubtitle(baseName, arguments)

    private fun domId(id: String) = id.replace(Regex("[^a-zA-Z0-9_-]"), "_")

    // ── Theme ──────────────────────────────────────────────────────

    private fun buildCssVars(): String = ChatTheme.buildCssVars(McpServerSettings.getInstance(project))

    private fun updateThemeColors() {
        var css = buildCssVars().replace("'", "\\'")
        if (currentProfileId.isNotEmpty()) {
            css += ChatTheme.activeAgentCss(currentProfileId).replace("'", "\\'")
        }
        executeJs("document.documentElement.style.cssText='$css'")
        val panelBg = JBUI.CurrentTheme.ToolWindow.background()
        browser?.setPageBackgroundColor("rgb(${panelBg.red},${panelBg.green},${panelBg.blue})")
    }

    private var monitorRecovery: MonitorSwitchRecovery? = null

    /**
     * Manually trigger JCEF monitor-switch recovery. Exposed for the
     * `AgentBridge: Force JCEF Refresh` diagnostic action — gives users a
     * recovery path when the panel renders with wrong DPI or freezes on
     * monitor switch (issue #237), without needing to restart the IDE.
     */
    fun forceJcefRefresh() {
        monitorRecovery?.forceRefresh()
    }

    /**
     * Detects when the panel moves to a different monitor (or the underlying
     * display changes) and forces JCEF's OSR renderer to recover. All logic
     * lives in [MonitorSwitchRecovery]; see issue #237.
     */
    private fun setupMonitorChangeListener() {
        val b = browser ?: return
        monitorRecovery = MonitorSwitchRecovery(
            browser = b,
            onRecovered = { updateThemeColors() },
            parentDisposable = this,
        ).also { it.install() }
    }

    // ── Permission requests ────────────────────────────────────────

    fun handleWebPermissionResponse(data: String) {
        parsePermissionResponse(data)?.let { (reqId, response) ->
            pendingPermissionCallbacks.remove(reqId)?.invoke(response)
        }
    }

    private fun parsePermissionResponse(data: String): Pair<String, PermissionResponse>? {
        val colonIdx = data.indexOf(':')
        if (colonIdx <= 0) return null
        val reqId = data.substring(0, colonIdx)
        val response = when (data.substring(colonIdx + 1)) {
            "once" -> PermissionResponse.ALLOW_ONCE
            "session" -> PermissionResponse.ALLOW_SESSION
            "always" -> PermissionResponse.ALLOW_ALWAYS
            else -> PermissionResponse.DENY
        }
        return reqId to response
    }

    override fun showPermissionRequest(
        reqId: String,
        toolDisplayName: String,
        description: String,
        onRespond: (PermissionResponse) -> Unit
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
        val mcpDescription = if (toolDef != null && !toolDef.isBuiltIn) toolDef.description() else null
        val autoDenied = entry?.autoDenied ?: false
        val denialReason = entry?.denialReason
        val failed = entry?.status == "failed"

        // When a tool failed, always show the error popup so the user can see what went wrong.
        // Don't redirect to tool windows, git log, or diff viewer — those won't have the error.
        if (!failed) {
            val toolWindowId = resolveToolWindowId(baseName)
            if (toolWindowId != null) {
                val tabName = extractTabName(baseName, entry?.arguments)
                ApplicationManager.getApplication().invokeLater {
                    activateToolWindowTab(toolWindowId, tabName)
                }
                return
            }
            if (baseName?.trim('\'', '"') == "git_commit" && tryNavigateToCommit(entry?.result)) {
                return
            }
            if (baseName?.trim('\'', '"') in GIT_HISTORY_TOOLS && tryNavigateToGitLog(entry?.result)) {
                return
            }
            // If the tool arguments contain old_str/new_str, open IntelliJ's diff viewer directly.
            val diff = extractDiffFromArgs(entry?.arguments)
            if (diff != null) {
                ApplicationManager.getApplication().invokeLater {
                    val left = com.intellij.diff.DiffContentFactory.getInstance().create(diff.first)
                    val right = com.intellij.diff.DiffContentFactory.getInstance().create(diff.second)
                    val request = com.intellij.diff.requests.SimpleDiffRequest(
                        chipTitle, left, right, "Before", "After"
                    )
                    com.intellij.diff.DiffManager.getInstance().showDiff(project, request)
                }
                return
            }
        }

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
                denialReason,
                failed
            )
        }
    }

    private fun resolveToolWindowId(baseName: String?): String? = ToolCallArgParser.resolveToolWindowId(baseName)

    private fun extractTabName(baseName: String?, arguments: String?): String? =
        ToolCallArgParser.extractTabName(baseName, arguments)

    private fun extractDiffFromArgs(arguments: String?): Pair<String, String>? =
        ToolCallArgParser.extractDiffFromArgs(arguments)

    private fun activateToolWindowTab(toolWindowId: String, tabName: String?) {
        val toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            .getToolWindow(toolWindowId) ?: return
        toolWindow.activate {
            if (tabName != null) {
                val cm = toolWindow.contentManager
                for (content in cm.contents) {
                    val displayName = content.displayName ?: continue
                    if (displayName.contains(tabName, ignoreCase = true)) {
                        cm.setSelectedContent(content)
                        break
                    }
                }
            }
        }
    }

    /**
     * Extracts abbreviated commit hash from git commit output (e.g. `[master f63d935] ...`)
     * resolves it to a full hash via `git rev-parse`, and navigates to it in the VCS Log.
     * Returns true if a hash was found (navigation is async), false otherwise.
     */
    private fun tryNavigateToCommit(result: String?): Boolean {
        if (result.isNullOrBlank()) return false
        val match = Regex("""\[[\w/.#-]+\s+([0-9a-f]{7,40})]""").find(result) ?: return false
        val abbreviatedHash = match.groupValues[1]
        val basePath = project.basePath ?: return false

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val process = ProcessBuilder("git", "rev-parse", abbreviatedHash)
                    .directory(java.io.File(basePath))
                    .redirectErrorStream(true)
                    .start()
                val fullHash = process.inputStream.bufferedReader().readText().trim()
                process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                if (fullHash.length == 40) {
                    ApplicationManager.getApplication().invokeLater {
                        PlatformApiCompat
                            .showRevisionInLogAfterRefresh(project, fullHash)
                    }
                }
            } catch (_: Exception) {
                // best-effort navigation
            }
        }
        return true
    }

    /**
     * Extracts a full 40-char commit hash from the first `commit <hash>` line of git_log/git_show
     * output and navigates to it in the VCS Log. Returns true if a hash was found (async nav),
     * false if the result doesn't contain a valid commit line (falls through to popup).
     */
    private fun tryNavigateToGitLog(result: String?): Boolean {
        if (result.isNullOrBlank()) return false
        val hash = Regex("""^commit ([0-9a-f]{40})$""", RegexOption.MULTILINE)
            .find(result)?.groupValues?.get(1) ?: return false
        ApplicationManager.getApplication().invokeLater {
            PlatformApiCompat.showRevisionInLogAfterRefresh(project, hash)
        }
        return true
    }

    private fun isJson(text: String): Boolean = ToolCallArgParser.isJson(text)

    /** Resolves the display label for a tool chip: "DisplayName — subtitle" or just "DisplayName". */
    private fun toolChipTitle(baseName: String?, arguments: String?): String {
        if (baseName == null) return "Tool Call"
        val clean = baseName.trim('\'', '"')
        val toolDef = toolRegistry?.findById(clean)
        val displayFallback = toolDisplayInfo(clean)?.displayName ?: clean.replaceFirstChar { it.uppercaseChar() }
        val display = toolDef?.displayName() ?: displayFallback
        val subtitle = formatToolSubtitle(clean, arguments)
        return if (subtitle != null) "$display — $subtitle" else display
    }

    private data class ChipRegistration(
        val label: String,
        val paramsJson: String,
        val safeKind: String,
        val chipId: String,
        val domId: String,
        val isMcpHandled: Boolean
    )

    private fun registerToolChip(
        cleanTitle: String,
        arguments: String?,
        resolvedKind: String,
        correlationId: String
    ): ChipRegistration {
        val label = toolChipTitle(cleanTitle, arguments)
        val hasCustomRenderer = ToolRenderers.hasRenderer(cleanTitle, toolRegistry)
        val paramsJson = if (!arguments.isNullOrBlank() && !hasCustomRenderer) escJs(arguments) else ""
        val safeKind = escJs(resolvedKind)
        val argsObj = arguments?.let {
            try {
                JsonParser.parseString(it).takeIf { e -> e.isJsonObject }?.asJsonObject
            } catch (_: Exception) {
                null
            }
        }
        val registration = registry.registerClientSide(cleanTitle, argsObj, correlationId)
        val chipId = registration.chipId()
        return ChipRegistration(
            label = label,
            paramsJson = paramsJson,
            safeKind = safeKind,
            chipId = chipId,
            domId = "t-$chipId",
            isMcpHandled = registration.initialState() == ToolChipRegistry.ChipState.RUNNING
        )
    }

    private fun renderSubAgentResult(did: String, status: String, autoDenied: Boolean, result: String?) {
        val jsStatus = if (autoDenied) "denied" else status
        val resultHtml = when {
            autoDenied -> FAILED_SPAN
            !result.isNullOrBlank() -> markdownToHtml(result)
            status == "completed" -> "Completed"
            else -> FAILED_SPAN
        }
        val encoded = encodeBase64(resultHtml)
        executeJs("ChatController.updateSubAgent('$did','$jsStatus','$encoded')")
    }

    private fun prettyJson(json: String): String = ToolCallArgParser.prettyJson(json)

    private fun extractFilePathFromArgs(arguments: String?): String? =
        ToolCallArgParser.extractFilePathFromArgs(arguments)

    private fun extractTaskCompleteSummary(arguments: String?): String =
        ToolCallArgParser.extractTaskCompleteSummary(arguments)

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
                cancelQueuedMessage: function(id, text) { $cancelQueuedMessageBridgeJs },
                autoScrollDisabled: function() { $autoScrollDisabledBridgeJs },
                autoScrollEnabled: function() { $autoScrollEnabledBridgeJs }
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
