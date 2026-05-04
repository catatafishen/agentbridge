package com.github.catatafishen.agentbridge.ui.side

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat
import com.github.catatafishen.agentbridge.services.LiveToolCallEntry
import com.github.catatafishen.agentbridge.services.LiveToolCallService
import com.github.catatafishen.agentbridge.ui.ChatTheme
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
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

/**
 * JCEF-based panel displaying MCP tool calls with interactive pipeline visualization.
 * Renders the same `<tool-calls-view>` web component used in the PWA, fed by
 * [LiveToolCallService] via `ToolCallsController.upsert()` calls.
 *
 * Falls back to the Swing [ToolCallListPanel] when JCEF is unavailable.
 */
class ToolCallsWebPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val browser: JBCefBrowser?
    private var browserReady = false
    private var serviceListener: ChangeListener? = null

    init {
        if (JBCefApp.isSupported()) {
            browser = JBCefBrowser()
            val panelBg = JBUI.CurrentTheme.ToolWindow.background()
            browser.setPageBackgroundColor("rgb(${panelBg.red},${panelBg.green},${panelBg.blue})")
            Disposer.register(this, browser)

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

            PlatformApiCompat.subscribeLafChanges(this) { updateThemeColors() }

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
        browser?.zoomLevel = 0.0
        browserReady = true
        ApplicationManager.getApplication().invokeLater {
            val service = LiveToolCallService.getInstance(project)
            pushAllEntries(service.entries)
        }
    }

    private fun pushAllEntries(entries: List<LiveToolCallEntry>) {
        if (!browserReady || browser == null) return
        val sb = StringBuilder("ToolCallsController.clear();")
        for (entry in entries) {
            sb.append("ToolCallsController.upsert(").append(entryToJson(entry)).append(");")
        }
        executeJs(sb.toString())
    }

    private fun executeJs(js: String) {
        if (browser != null && browserReady) {
            browser.cefBrowser.executeJavaScript(js, "bridge://push", 0)
        }
    }

    private fun buildPage(): String {
        val cssVars = ChatTheme.buildCssVars()
        val css = loadResource("/chat/chat.css")
        val webAppCss = loadResource("/chat/web-app.css")
        val js = loadResource("/chat/chat-components.js")
        return """<!DOCTYPE html><html lang="en"><head><meta charset="utf-8">
            <style>$css</style>
            <style>$webAppCss</style>
            <style>:root { $cssVars }</style>
            <style>body { margin: 0; background: var(--bg); }</style>
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
        val vars = ChatTheme.buildCssVars().replace("'", "\\'")
        executeJs("document.documentElement.style.cssText='$vars'")
        val panelBg = JBUI.CurrentTheme.ToolWindow.background()
        browser?.setPageBackgroundColor("rgb(${panelBg.red},${panelBg.green},${panelBg.blue})")
    }

    override fun dispose() {
        serviceListener?.let {
            LiveToolCallService.getInstance(project).removeChangeListener(it)
            serviceListener = null
        }
    }

    companion object {
        private val LOG = Logger.getInstance(ToolCallsWebPanel::class.java)

        fun entryToJson(entry: LiveToolCallEntry): String {
            val sb = StringBuilder(256)
            sb.append("{\"id\":").append(entry.callId())
            sb.append(",\"title\":").append(escapeJson(entry.displayName()))
            sb.append(",\"toolName\":").append(escapeJson(entry.toolName()))
            entry.category()?.let { sb.append(",\"kind\":").append(escapeJson(it)) }
            val status = when {
                entry.isRunning -> "running"
                entry.success() == true -> "success"
                else -> "error"
            }
            sb.append(",\"status\":").append(escapeJson(status))
            sb.append(",\"timestamp\":").append(escapeJson(entry.timestamp().toString()))
            sb.append(",\"arguments\":").append(escapeJson(entry.input()))
            sb.append(",\"result\":").append(escapeJson(entry.output()))
            sb.append(",\"durationMs\":").append(entry.durationMs())
            sb.append(",\"hasHooks\":").append(entry.hasHooks())

            val stages = entry.hookStages()
            if (stages.isNotEmpty()) {
                sb.append(",\"hookStages\":[")
                stages.forEachIndexed { i, s ->
                    if (i > 0) sb.append(',')
                    sb.append("{\"trigger\":").append(escapeJson(s.trigger()))
                    sb.append(",\"scriptName\":").append(escapeJson(s.scriptName()))
                    sb.append(",\"outcome\":").append(escapeJson(s.outcome()))
                    sb.append(",\"durationMs\":").append(s.durationMs())
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
