package com.github.catatafishen.agentbridge.ui.side

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat
import com.github.catatafishen.agentbridge.services.LiveToolCallEntry
import com.github.catatafishen.agentbridge.services.LiveToolCallService
import com.github.catatafishen.agentbridge.services.ToolRegistry
import com.github.catatafishen.agentbridge.session.db.ConversationQuery
import com.github.catatafishen.agentbridge.session.db.ConversationService
import com.github.catatafishen.agentbridge.settings.McpServerSettings
import com.github.catatafishen.agentbridge.ui.ChatTheme
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.JBUI
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefContextMenuParams
import org.cef.callback.CefMenuModel
import org.cef.handler.CefContextMenuHandlerAdapter
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.event.ChangeListener

class ToolCallsWebPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val browser: JBCefBrowser?
    private var browserReady = false
    private var serviceListener: ChangeListener? = null
    private var diffQuery: JBCefJSQuery? = null
    private var loadMoreQuery: JBCefJSQuery? = null

    init {
        if (JBCefApp.isSupported()) {
            browser = JBCefBrowser()
            val panelBg = JBUI.CurrentTheme.ToolWindow.background()
            browser.setPageBackgroundColor("rgb(${panelBg.red},${panelBg.green},${panelBg.blue})")
            Disposer.register(this, browser)

            val query = PlatformApiCompat.createJSQuery(browser)
            query.addHandler { request ->
                try {
                    val parsed = com.google.gson.JsonParser.parseString(request).asJsonObject
                    val original = parsed.get("original").asString
                    val modified = parsed.get("modified").asString
                    val toolName = parsed.get("tool").asString
                    // ModalityState.any(): safe here because showDiff is UI-only (no model writes),
                    // and using defaultModalityState would fail during a phantom modality leak.
                    ApplicationManager.getApplication().invokeLater({
                        ToolCallInputDiffViewer.showDiff(project, original, modified, toolName)
                    }, com.intellij.openapi.application.ModalityState.any())
                } catch (e: Exception) {
                    LOG.warn("openInputDiff: failed to parse request", e)
                }
                null
            }
            Disposer.register(this, query)
            diffQuery = query

            val loadMore = PlatformApiCompat.createJSQuery(browser)
            loadMore.addHandler { beforeEventId ->
                loadHistoryPage(if (beforeEventId.isNullOrBlank()) null else beforeEventId)
                null
            }
            Disposer.register(this, loadMore)
            loadMoreQuery = loadMore

            browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                    if (frame.isMain) onBrowserReady()
                }

                override fun onLoadError(
                    cefBrowser: CefBrowser, frame: CefFrame,
                    errorCode: org.cef.handler.CefLoadHandler.ErrorCode,
                    errorText: String?, failedUrl: String?
                ) {
                    LOG.warn("JCEF load error: $errorCode $errorText")
                }
            }, browser.cefBrowser)

            browser.jbCefClient.addContextMenuHandler(object : CefContextMenuHandlerAdapter() {
                override fun onBeforeContextMenu(
                    cefBrowser: CefBrowser, frame: CefFrame,
                    params: CefContextMenuParams, model: CefMenuModel
                ) {
                    model.clear()
                }
            }, browser.cefBrowser)

            browser.loadHTML(buildPage())
            add(browser.component, BorderLayout.CENTER)

            instances[project] = this
            PlatformApiCompat.subscribeLafChanges(this) { updateThemeColors() }
            PlatformApiCompat.subscribeUiSettingsChanges(this) { updateThemeColors() }
            PlatformApiCompat.subscribeEditorColorSchemeChanges(this) { updateThemeColors() }

            val service = LiveToolCallService.getInstance(project)
            val listener = ChangeListener { pushAllEntries(service.entries) }
            serviceListener = listener
            service.addChangeListener(listener)
        } else {
            browser = null
            val fallback = ToolCallListPanel(project)
            Disposer.register(this, fallback)
            add(fallback, BorderLayout.CENTER)
        }
    }

    private fun onBrowserReady() {
        browser?.cefBrowser?.executeJavaScript(
            "window.addEventListener('wheel',function(e){if(e.ctrlKey){e.preventDefault();e.stopPropagation();}},{passive:false,capture:true});",
            "", 0
        )
        diffQuery?.let { query ->
            browser?.cefBrowser?.executeJavaScript(
                "window.openInputDiff = function(original, modified, tool) { " +
                    query.inject("JSON.stringify({original:original,modified:modified,tool:tool})") + " };",
                "", 0
            )
        }
        loadMoreQuery?.let { query ->
            browser?.cefBrowser?.executeJavaScript(
                "window.loadMoreToolCalls = function(beforeEventId) { " +
                    query.inject("beforeEventId || ''") + " };",
                "", 0
            )
        }
        browserReady = true
        ApplicationManager.getApplication().invokeLater {
            loadHistoryPage(null)
            val service = LiveToolCallService.getInstance(project)
            pushAllEntries(service.entries)
        }
    }

    /**
     * Loads a page of historic tool calls from the conversation DB and pushes them
     * to the JS ToolCallsController as prepended items.
     */
    private fun loadHistoryPage(beforeEventId: String?) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val service = ConversationService.getInstance(project)
            val entries = service.loadToolCallHistory(HISTORY_PAGE_SIZE, beforeEventId).toList()
            val registry = ToolRegistry.getInstance(project)
            if (entries.isEmpty()) {
                ApplicationManager.getApplication().invokeLater {
                    executeJs("ToolCallsController.setHistoryExhausted(true);")
                }
                return@executeOnPooledThread
            }
            // If we got fewer entries than requested, there are no more pages
            val exhausted = entries.size < HISTORY_PAGE_SIZE
            val sb = StringBuilder()
            for (entry in entries) {
                sb.append("ToolCallsController.prependHistoric(")
                    .append(historyEntryToJson(entry, registry))
                    .append(");")
            }
            sb.append("ToolCallsController.notifyChange();")
            if (exhausted) {
                sb.append("ToolCallsController.setHistoryExhausted(true);")
            }
            ApplicationManager.getApplication().invokeLater {
                executeJs(sb.toString())
            }
        }
    }

    private fun pushAllEntries(entries: List<LiveToolCallEntry>) {
        if (!browserReady || browser == null) return
        val registry = ToolRegistry.getInstance(project)
        val sb = StringBuilder("ToolCallsController.clear();")
        for (entry in entries) {
            sb.append("ToolCallsController.upsert(").append(entryToJson(entry, registry)).append(");")
        }
        executeJs(sb.toString())
    }

    private fun executeJs(js: String) {
        if (browser != null && browserReady) {
            browser.cefBrowser.executeJavaScript(js, "bridge://push", 0)
        }
    }

    private fun buildPage(): String {
        val cssVars = ChatTheme.buildCssVars(McpServerSettings.getInstance(project))
        val bubbleStyle = ChatTheme.sanitizeBubbleStyle(McpServerSettings.getInstance(project).bubbleStyle)
        val css = loadResource("/chat/chat.css")
        val webAppCss = loadResource("/chat/web-app.css")
        val js = loadResource("/chat/chat-components.js")
        return """<!DOCTYPE html><html lang="en" data-bubble-style="${bubbleStyle}"><head><meta charset="utf-8">
            <style>$css</style>
            <style>$webAppCss</style>
            <style>:root { $cssVars }</style>
            <style>body { margin: 0; height: 100vh; overflow: hidden; background: var(--bg); }
            tool-calls-view { display: block; height: 100%; }</style>
            <script>window.addEventListener('wheel',function(e){if(e.ctrlKey)e.preventDefault();},{passive:false});</script>
            </head><body>
            <tool-calls-view></tool-calls-view>
            <script>$js</script>
            <script>document.querySelector('tool-calls-view').setPushMode(true);</script>
            </body></html>"""
    }

    private fun loadResource(path: String): String =
        javaClass.getResourceAsStream(path)?.bufferedReader()?.readText()
            ?: run { LOG.warn("Missing resource: $path"); "" }

    private fun updateThemeColors() {
        val vars = ChatTheme.buildCssVars(McpServerSettings.getInstance(project)).replace("'", "\\'")
        val bubbleStyle = ChatTheme.sanitizeBubbleStyle(McpServerSettings.getInstance(project).bubbleStyle)
        executeJs("document.documentElement.style.cssText='$vars';document.documentElement.dataset.bubbleStyle='$bubbleStyle'")
        val panelBg = JBUI.CurrentTheme.ToolWindow.background()
        browser?.setPageBackgroundColor("rgb(${panelBg.red},${panelBg.green},${panelBg.blue})")
    }

    override fun dispose() {
        instances.remove(project, this)
        serviceListener?.let {
            LiveToolCallService.getInstance(project).removeChangeListener(it)
            serviceListener = null
        }
    }

    companion object {
        private val LOG = Logger.getInstance(ToolCallsWebPanel::class.java)
        private const val HISTORY_PAGE_SIZE = 50
        private const val JSON_DURATION_MS = ",\"durationMs\":"

        private val instances = java.util.concurrent.ConcurrentHashMap<Project, ToolCallsWebPanel>()

        /** Refreshes the active tool-calls panel for [project] when appearance settings change. */
        fun refreshForProject(project: Project) {
            instances[project]?.updateThemeColors()
        }

        fun entryToJson(entry: LiveToolCallEntry, registry: ToolRegistry? = null): String {
            val sb = StringBuilder(256)
            sb.append("{\"id\":").append(entry.callId())
            sb.append(",\"title\":").append(escapeJson(entry.displayName()))
            sb.append(",\"toolName\":").append(escapeJson(entry.toolName()))
            // Prefer the live tool definition's kind so that any reclassification of tools
            // (e.g. RunCommandTool changed from EDIT to EXECUTE) is reflected immediately
            // even for entries that were recorded before the change.
            val kind = registry?.findById(entry.toolName())?.kind()?.value() ?: entry.category()
            kind?.let { sb.append(",\"kind\":").append(escapeJson(it)) }
            val status = when {
                entry.isRunning -> "running"
                entry.success() == true -> "success"
                else -> "error"
            }
            sb.append(",\"status\":").append(escapeJson(status))
            sb.append(",\"timestamp\":").append(escapeJson(entry.timestamp().toString()))
            sb.append(",\"arguments\":").append(escapeJson(entry.input()))
            sb.append(",\"result\":").append(escapeJson(entry.output()))
            sb.append(JSON_DURATION_MS).append(entry.durationMs())
            sb.append(",\"hasHooks\":").append(entry.hasHooks())

            entry.originalInput()?.let { orig ->
                sb.append(",\"originalArguments\":").append(escapeJson(orig))
            }
            val stages = entry.hookStages().toList()
            if (stages.isNotEmpty()) {
                sb.append(",\"hookStages\":[")
                stages.forEachIndexed { i, s ->
                    if (i > 0) sb.append(',')
                    sb.append("{\"trigger\":").append(escapeJson(s.trigger()))
                    sb.append(",\"scriptName\":").append(escapeJson(s.scriptName()))
                    sb.append(",\"outcome\":").append(escapeJson(s.outcome()))
                    sb.append(JSON_DURATION_MS).append(s.durationMs())
                    s.detail()?.let { sb.append(",\"detail\":").append(escapeJson(it)) }
                    sb.append('}')
                }
                sb.append(']')
            }
            sb.append('}')
            return sb.toString()
        }

        fun historyEntryToJson(
            entry: ConversationQuery.ToolCallHistoryEntry,
            registry: ToolRegistry? = null
        ): String {
            val sb = StringBuilder(256)
            sb.append("{\"id\":").append(escapeJson(entry.eventId()))
            sb.append(",\"title\":").append(escapeJson(entry.displayName()))
            sb.append(",\"toolName\":").append(escapeJson(entry.toolName()))
            val kind = registry?.findById(entry.toolName())?.kind()?.value() ?: entry.category()
            kind?.let { sb.append(",\"kind\":").append(escapeJson(it)) }
            val status = entry.status() ?: if (entry.success()) "success" else "error"
            sb.append(",\"status\":").append(escapeJson(status))
            sb.append(",\"timestamp\":").append(escapeJson(entry.timestamp().toString()))
            sb.append(",\"arguments\":").append(escapeJson(entry.arguments()))
            sb.append(",\"result\":").append(escapeJson(entry.result()))
            sb.append(JSON_DURATION_MS).append(entry.durationMs())
            sb.append(",\"hasHooks\":").append(entry.hasHooks())
            sb.append(",\"historic\":true")

            val stages = entry.hookStages().toList()
            if (stages.isNotEmpty()) {
                sb.append(",\"hookStages\":[")
                stages.forEachIndexed { i, s ->
                    if (i > 0) sb.append(',')
                    sb.append("{\"trigger\":").append(escapeJson(s.trigger()))
                    sb.append(",\"scriptName\":").append(escapeJson(s.scriptName()))
                    sb.append(",\"outcome\":").append(escapeJson(s.outcome()))
                    sb.append(JSON_DURATION_MS).append(s.durationMs())
                    s.detail()?.let { sb.append(",\"detail\":").append(escapeJson(it)) }
                    sb.append('}')
                }
                sb.append(']')
            }
            sb.append('}')
            return sb.toString()
        }

        private fun escapeJson(value: String?): String {
            if (value == null) return "null"
            val sb = StringBuilder(value.length + 2)
            sb.append('"')
            for (c in value) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (c < '\u0020') sb.append("\\u${c.code.toString(16).padStart(4, '0')}")
                    else sb.append(c)
                }
            }
            sb.append('"')
            return sb.toString()
        }
    }
}
