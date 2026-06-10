package com.github.catatafishen.agentbridge.ui.graph

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat
import com.github.catatafishen.agentbridge.psi.graph.CodeGraphStore
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.JBUI
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.HierarchyEvent
import java.io.IOException
import javax.swing.*
import javax.swing.event.DocumentEvent

/**
 * Graph tab for the Knowledge Graph tool window.
 *
 * Uses JCEF + a self-contained Canvas 2D force-directed renderer (no external JS libraries)
 * to display files, commits, and prompts as nodes connected by their relationships:
 * - File → File: code dependency (blue circle)
 * - Commit → File: commit touched this file (amber diamond)
 * - Prompt → File: agent prompt referenced this file (green hexagon)
 *
 * Double-click a file node to open it in the editor.
 * Pan by dragging, zoom by scroll wheel. The view mode combo controls which node types are rendered.
 */
class KnowledgeGraphDiagramPanel(private val project: Project) : Disposable {

    companion object {
        private val LOG = Logger.getInstance(KnowledgeGraphDiagramPanel::class.java)
        private val VIEW_LABELS = arrayOf("All", "Files", "Commits", "Prompts")
        private val VIEW_MODES = arrayOf("all", "files", "commits", "prompts")
    }

    private val root = JPanel(BorderLayout())
    private val browser: JBCefBrowser?
    private var navigateQuery: JBCefJSQuery? = null

    @Volatile
    private var browserReady = false

    private val viewCombo = JComboBox(VIEW_LABELS)
    private val searchField = SearchTextField()
    private val commitsSpinner = JSpinner(SpinnerNumberModel(20, 0, 50, 1))
    private val promptsSpinner = JSpinner(SpinnerNumberModel(15, 0, 50, 1))
    private val depthSpinner = JSpinner(SpinnerNumberModel(1, 0, 3, 1))

    init {
        root.add(buildToolbar(), BorderLayout.NORTH)

        if (JBCefApp.isSupported()) {
            val b = JBCefBrowser()
            Disposer.register(this, b)
            browser = b

            val q = PlatformApiCompat.createJSQuery(b as com.intellij.ui.jcef.JBCefBrowserBase)
            q.addHandler { path -> navigateToFile(path); null }
            Disposer.register(this, q)
            navigateQuery = q

            b.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(cef: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                    if (frame.isMain) onBrowserReady()
                }

                override fun onLoadError(
                    cef: CefBrowser, frame: CefFrame,
                    errorCode: org.cef.handler.CefLoadHandler.ErrorCode,
                    errorText: String?, failedUrl: String?
                ) {
                    LOG.warn("JCEF graph load error: $errorCode $errorText")
                }
            }, b.cefBrowser)

            b.loadHTML(buildPage())
            root.add(b.component, BorderLayout.CENTER)

            viewCombo.addActionListener {
                val idx = viewCombo.selectedIndex
                if (idx in VIEW_MODES.indices) executeJs("window.setViewMode('${VIEW_MODES[idx]}')")
            }

            searchField.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    executeJs("window.setSearch('${escapeForJs(searchField.text)}')")
                }
            })

            // Auto-refresh when tab becomes visible
            root.addHierarchyListener { e ->
                if ((e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong()) != 0L
                    && root.isShowing && browserReady
                ) {
                    refresh()
                }
            }
        } else {
            browser = null
            val msg = JBLabel(
                "<html>Graph visualization requires JCEF (Chromium Embedded Framework).<br>" +
                    "Enable it in <b>Help → Find Action → Enable JetBrains Chromium Embedded Framework</b>.</html>",
                SwingConstants.CENTER
            )
            msg.border = JBUI.Borders.empty(24)
            root.add(msg, BorderLayout.CENTER)
        }
    }

    fun getComponent() = root

    fun refresh() {
        if (browser == null || !browserReady) return
        val commitLimit = commitsSpinner.value as Int
        val promptLimit = promptsSpinner.value as Int
        val fileDepth = depthSpinner.value as Int
        ApplicationManager.getApplication().executeOnPooledThread {
            val store = CodeGraphStore.getInstance(project)
            val data = store.getGraphData(commitLimit, promptLimit, fileDepth)
            val json = buildJson(data)
            val escaped = escapeForJs(json)
            ApplicationManager.getApplication().invokeLater {
                executeJs("window.loadGraph('$escaped')")
            }
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun onBrowserReady() {
        val q = navigateQuery
        val b = browser ?: return
        if (q != null) {
            val navBridge = "window._navigateToFile = function(path) { ${q.inject("path")} };"
            b.cefBrowser.executeJavaScript(navBridge, "", 0)
        }
        browserReady = true
        ApplicationManager.getApplication().invokeLater { refresh() }
    }

    private fun navigateToFile(relativePath: String) {
        ApplicationManager.getApplication().invokeLater {
            val base = project.basePath ?: return@invokeLater
            val fullPath = "$base/$relativePath"
            val vf = LocalFileSystem.getInstance().findFileByPath(fullPath)
            if (vf != null) {
                FileEditorManager.getInstance(project).openFile(vf, true)
            } else {
                LOG.debug("Could not navigate: file not found: $fullPath")
            }
        }
    }

    private fun executeJs(js: String) {
        browser?.cefBrowser?.executeJavaScript(js, "bridge://graph", 0)
    }

    private fun buildToolbar(): JPanel {
        val bar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(3)))
        bar.add(JBLabel("Commits:"))
        commitsSpinner.toolTipText = "Number of most-recent commits to include in the graph"
        bar.add(commitsSpinner)
        bar.add(JBLabel("Prompts:"))
        promptsSpinner.toolTipText = "Number of most-recent prompts (turns) to include"
        bar.add(promptsSpinner)
        bar.add(JBLabel("Depth:"))
        depthSpinner.toolTipText =
            "How many hops of file→file dependencies to traverse from files touched by the loaded commits/prompts"
        bar.add(depthSpinner)
        bar.add(Box.createHorizontalStrut(JBUI.scale(8)))
        bar.add(JBLabel("View:"))
        bar.add(viewCombo)
        bar.add(Box.createHorizontalStrut(JBUI.scale(8)))
        searchField.toolTipText = "Search by file name — matching nodes are highlighted"
        bar.add(searchField)

        val refreshOnChange = javax.swing.event.ChangeListener { refresh() }
        commitsSpinner.addChangeListener(refreshOnChange)
        promptsSpinner.addChangeListener(refreshOnChange)
        depthSpinner.addChangeListener(refreshOnChange)
        return bar
    }

    private fun buildPage(): String {
        val css = loadResource("/graph/graph.css")
        val js = loadResource("/graph/graph.js")
        return """<!DOCTYPE html><html lang="en"><head><meta charset="utf-8">
<style>$css</style>
</head><body>
<canvas id="g"></canvas>
<script>$js</script>
</body></html>"""
    }

    private fun loadResource(path: String): String =
        try {
            javaClass.getResourceAsStream(path)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: run { LOG.warn("Missing resource: $path"); "" }
        } catch (e: IOException) {
            LOG.warn("Failed to load resource: $path", e); ""
        }

    // ── JSON building ──────────────────────────────────────────────────────────

    private fun buildJson(data: CodeGraphStore.GraphData): String {
        val sb = StringBuilder((data.nodes.size * 128 + data.edges.size * 64).coerceAtLeast(64))
        sb.append("{\"nodes\":[")
        data.nodes.forEachIndexed { i, n -> if (i > 0) sb.append(','); appendNode(sb, n) }
        sb.append("],\"edges\":[")
        data.edges.forEachIndexed { i, e -> if (i > 0) sb.append(','); appendEdge(sb, e) }
        sb.append("]}")
        return sb.toString()
    }

    private fun appendNode(sb: StringBuilder, n: CodeGraphStore.GraphDataNode) {
        sb.append("{\"id\":").append(jsonStr(n.id()))
        sb.append(",\"type\":").append(jsonStr(n.type()))
        sb.append(",\"label\":").append(jsonStr(n.label()))
        n.path()?.let { sb.append(",\"path\":").append(jsonStr(it)) }
        n.hash()?.let { sb.append(",\"hash\":").append(jsonStr(it)) }
        n.author()?.let { sb.append(",\"author\":").append(jsonStr(it)) }
        n.timestamp()?.let { sb.append(",\"timestamp\":").append(jsonStr(it)) }
        n.preview()?.let { sb.append(",\"preview\":").append(jsonStr(it)) }
        sb.append(",\"depCount\":").append(n.depCount())
        sb.append(",\"dependentCount\":").append(n.dependentCount())
        sb.append(",\"sizeMetric\":").append(n.sizeMetric())
        sb.append('}')
    }

    private fun appendEdge(sb: StringBuilder, e: CodeGraphStore.GraphDataEdge) {
        sb.append("{\"source\":").append(jsonStr(e.source()))
        sb.append(",\"target\":").append(jsonStr(e.target()))
        sb.append(",\"type\":").append(jsonStr(e.type()))
        sb.append('}')
    }

    /** Encodes a String as a JSON string literal (double-quoted). */
    private fun jsonStr(s: String?): String {
        if (s == null) return "null"
        val sb = StringBuilder(s.length + 8)
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < '\u0020') sb.append("\\u${c.code.toString(16).padStart(4, '0')}")
            else sb.append(c)
        }
        sb.append('"')
        return sb.toString()
    }

    /**
     * Escapes a JSON string for embedding as a JS single-quoted string literal.
     * Since input is already valid JSON (backslashes already escaped), we only need
     * to additionally escape single quotes and the two Unicode line terminators.
     */
    private fun escapeForJs(json: String): String = json
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")

    override fun dispose() {
        // browser and navigateQuery are registered with Disposer; nothing extra needed
    }
}
