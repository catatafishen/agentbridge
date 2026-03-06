package com.github.catatafishen.ideagentforcopilot.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Dimension
import java.awt.Point
import javax.swing.JEditorPane
import javax.swing.UIManager

internal object ToolCallPopup {

    private var currentPopup: com.intellij.openapi.ui.popup.JBPopup? = null

    /** Kind → chip background color mapping (10% alpha blend onto panel background). */
    private val KIND_COLORS = mapOf(
        "read" to JBColor(Color(0x3A, 0x95, 0x95), Color(100, 185, 185)),
        "edit" to JBColor(Color(0xA0, 0x7A, 0x3A), Color(205, 155, 95)),
        "execute" to JBColor(Color(0x4A, 0x90, 0x4A), Color(130, 190, 130)),
        "search" to JBColor(Color(0x3A, 0x95, 0x95), Color(100, 185, 185)),
        "think" to JBColor(Color(0x7A, 0x70, 0xA8), Color(170, 155, 210)),
        "other" to JBColor(Color(0x78, 0x7C, 0x80), Color(160, 165, 170)),
    )

    /**
     * @param kind       tool kind ("read", "edit", "execute", etc.) — drives the background tint
     * @param paramsHtml pre-rendered HTML for the parameters section, or null to omit
     * @param resultHtml pre-rendered HTML for the result section
     */
    fun show(project: Project, title: String, kind: String, paramsHtml: String?, resultHtml: String) {
        currentPopup?.cancel()

        val kindColor = KIND_COLORS[kind] ?: KIND_COLORS["other"]!!
        val panelBg = UIUtil.getPanelBackground()
        val tintedBg = blendColor(kindColor, panelBg, 0.07)

        val htmlDoc = buildHtmlDocument(tintedBg, paramsHtml, resultHtml)
        val editorPane = JEditorPane().apply {
            contentType = "text/html"
            isEditable = false
            text = htmlDoc
            caretPosition = 0
            border = JBUI.Borders.empty()
            background = tintedBg
        }

        val scrollPane = com.intellij.ui.components.JBScrollPane(editorPane).apply {
            preferredSize = Dimension(JBUI.scale(520), JBUI.scale(400))
            border = JBUI.Borders.empty()
        }

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(scrollPane, editorPane)
            .setTitle(title)
            .setMovable(true)
            .setResizable(true)
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(false)
            .setCancelKeyEnabled(true)
            .setMinSize(Dimension(JBUI.scale(300), JBUI.scale(150)))
            .createPopup()
        currentPopup = popup

        val frame = WindowManager.getInstance().getFrame(project)
        if (frame != null) {
            val center = Point(
                frame.x + (frame.width - JBUI.scale(520)) / 2,
                frame.y + (frame.height - JBUI.scale(400)) / 2
            )
            popup.showInScreenCoordinates(frame.rootPane, center)
        } else {
            popup.showInFocusCenter()
        }
    }

    private fun buildHtmlDocument(bg: Color, paramsHtml: String?, resultHtml: String): String {
        val css = buildPopupCss(bg)
        val body = StringBuilder()
        body.append("<div class='section'><div class='section-label'>Result</div>")
        body.append("<div class='section-content'>$resultHtml</div></div>")
        if (paramsHtml != null) {
            body.append("<div class='section'><div class='section-label'>Parameters</div>")
            body.append("<div class='section-content'>$paramsHtml</div></div>")
        }

        return """
            <html><head><style>$css</style></head>
            <body>$body</body></html>
        """.trimIndent()
    }

    /**
     * Builds a CSS stylesheet with colors resolved from the current IDE theme.
     * JEditorPane supports CSS1 only — no variables, flexbox, or modern features.
     */
    private fun buildPopupCss(bg: Color): String {
        val fg = UIUtil.getLabelForeground()
        val muted = blendColor(fg, bg, 0.55)
        val codeBg = UIManager.getColor("Editor.backgroundColor")
            ?: JBColor(Color(0xF0, 0xF0, 0xF0), Color(0x2B, 0x2D, 0x30))
        val borderColor = blendColor(fg, bg, 0.12)
        val subtleBg = blendColor(fg, bg, 0.06)
        val linkColor = UIManager.getColor("link.foreground")
            ?: JBColor(Color(0x28, 0x7B, 0xDE), Color(0x58, 0x9D, 0xF6))
        val successColor = JBColor(Color(0x1A, 0x7F, 0x37), Color(0x3F, 0xB9, 0x50))
        val dangerColor = JBColor(Color(0xCF, 0x22, 0x2E), Color(0xF8, 0x53, 0x49))
        val warningColor = JBColor(Color(0x9A, 0x6D, 0x00), Color(0xD2, 0x9B, 0x22))
        val font = UIUtil.getLabelFont()
        val monoFont = "JetBrains Mono, monospace"

        return """
            body {
                font-family: '${font.family}', sans-serif;
                font-size: ${font.size - 1}pt;
                color: ${css(fg)};
                background: ${css(bg)};
                margin: 6px 10px;
                line-height: 1.45;
            }
            .section { margin-bottom: 10px; }
            .section-label {
                color: ${css(muted)};
                font-size: ${font.size - 2}pt;
                font-weight: bold;
                margin-bottom: 4px;
            }
            pre, code {
                font-family: $monoFont;
                font-size: ${font.size - 2}pt;
            }
            pre {
                background: ${css(codeBg)};
                padding: 6px 8px;
                margin: 2px 0;
                white-space: pre-wrap;
                word-wrap: break-word;
            }
            .tool-output { font-size: ${font.size - 2}pt; }
            .tool-params-code { background: ${css(codeBg)}; }

            /* ── Git file badges ── */
            .git-file-badge {
                font-family: $monoFont;
                font-size: ${font.size - 3}pt;
                font-weight: bold;
                padding: 0 4px;
                margin-right: 4px;
            }
            .git-file-add { color: ${css(successColor)}; }
            .git-file-mod { color: ${css(linkColor)}; }
            .git-file-del { color: ${css(dangerColor)}; }
            .git-file-rename { color: ${css(warningColor)}; }
            .git-file-untracked { color: ${css(muted)}; }
            .git-file-conflict { color: ${css(dangerColor)}; }
            .git-file-path { font-family: $monoFont; font-size: ${font.size - 2}pt; }
            .git-file-entry { padding: 1px 0; }

            /* ── Git status ── */
            .git-status-result { line-height: 1.5; }
            .git-status-branch-name { font-weight: bold; }
            .git-status-tracking { color: ${css(muted)}; font-size: ${font.size - 2}pt; }
            .git-status-clean { color: ${css(muted)}; font-style: italic; }
            .git-status-section { margin-top: 6px; }
            .git-status-section-header {
                font-size: ${font.size - 2}pt;
                font-weight: bold;
                margin-bottom: 2px;
                padding-bottom: 2px;
                border-bottom: 1px solid ${css(borderColor)};
            }
            .git-status-staged { color: ${css(successColor)}; }
            .git-status-unstaged { color: ${css(linkColor)}; }
            .git-status-untracked { color: ${css(muted)}; }
            .git-status-conflict { color: ${css(dangerColor)}; }

            /* ── Git stage ── */
            .git-stage-header {
                color: ${css(successColor)};
                font-weight: bold;
                padding: 4px 6px;
                margin-bottom: 6px;
            }

            /* ── Git diff ── */
            .git-diff-result { line-height: 1.45; }
            .git-diff-file { margin-bottom: 8px; }
            .git-diff-file-header {
                font-weight: bold;
                padding: 3px 6px;
                background: ${css(subtleBg)};
                border-bottom: 1px solid ${css(borderColor)};
            }
            .git-diff-hunks { font-family: $monoFont; font-size: ${font.size - 2}pt; }
            .git-diff-hunk-header {
                color: ${css(muted)};
                font-family: $monoFont;
                font-size: ${font.size - 3}pt;
                padding: 2px 6px;
                background: ${css(subtleBg)};
            }
            .git-diff-line {
                padding: 0 6px;
                white-space: pre-wrap;
                font-family: $monoFont;
            }
            .git-diff-add { background: rgba(80,180,80,0.12); color: ${css(successColor)}; }
            .git-diff-del { background: rgba(220,80,80,0.12); color: ${css(dangerColor)}; }
            .git-diff-ctx { color: ${css(fg)}; }
            .git-diff-meta { color: ${css(muted)}; font-style: italic; }
            .git-diff-stat-summary {
                font-weight: bold;
                margin-top: 6px;
                padding-top: 6px;
                border-top: 1px solid ${css(borderColor)};
            }
            .git-diff-stat-count { font-family: $monoFont; color: ${css(muted)}; }
            .git-diff-stat-bar { font-family: $monoFont; }

            /* ── Git log ── */
            .git-log-result { line-height: 1.55; }
            .git-log-entry { padding: 2px 0; }
            .git-log-hash {
                font-family: $monoFont;
                font-weight: bold;
                color: ${css(linkColor)};
            }
            .git-log-message { color: ${css(fg)}; }
            .git-log-author { color: ${css(muted)}; font-size: ${font.size - 2}pt; }
            .git-log-date { color: ${css(muted)}; font-size: ${font.size - 2}pt; }

            /* ── Search results ── */
            .search-result { line-height: 1.5; }
            .search-header { font-weight: bold; margin-bottom: 4px; }
            .search-count { color: ${css(linkColor)}; margin-right: 4px; }
            .search-empty { color: ${css(muted)}; font-style: italic; }
            .search-file { margin-bottom: 6px; }
            .search-file-header { font-weight: bold; padding: 2px 0; }
            .search-file-count { color: ${css(muted)}; margin-left: 6px; font-size: ${font.size - 2}pt; }
            .search-match { padding: 1px 0; font-family: $monoFont; font-size: ${font.size - 2}pt; }
            .search-line { color: ${css(muted)}; }
            .search-badge {
                color: ${css(linkColor)};
                font-size: ${font.size - 3}pt;
                margin: 0 4px;
            }

            /* ── Build / test results ── */
            .build-result { line-height: 1.5; }
            .build-status-success { color: ${css(successColor)}; font-weight: bold; }
            .build-status-fail { color: ${css(dangerColor)}; font-weight: bold; }
            .test-result-summary { font-weight: bold; margin-bottom: 4px; }
            .test-pass { color: ${css(successColor)}; }
            .test-fail { color: ${css(dangerColor)}; }
            .test-skip { color: ${css(muted)}; }

            /* ── Inspection results ── */
            .inspection-result { line-height: 1.5; }
            .inspection-error { color: ${css(dangerColor)}; }
            .inspection-warning { color: ${css(warningColor)}; }
            .inspection-info { color: ${css(muted)}; }

            /* ── File outline / class outline ── */
            .outline-result { line-height: 1.5; }
            .outline-kind { color: ${css(linkColor)}; font-weight: bold; font-size: ${font.size - 2}pt; }
            .outline-name { font-family: $monoFont; }

            /* ── Misc / fallback ── */
            .git-blame-result { line-height: 1.5; }
            .file-content-result { line-height: 1.4; }
            .write-result { line-height: 1.5; }
            .write-success { color: ${css(successColor)}; }
            .cmd-result { line-height: 1.4; }
            .refactor-result { line-height: 1.5; }
            .project-info-result { line-height: 1.5; }
            .project-info-label { color: ${css(muted)}; font-weight: bold; }
            .project-info-value { font-family: $monoFont; }
            .http-result { line-height: 1.45; }
            .http-status-ok { color: ${css(successColor)}; font-weight: bold; }
            .http-status-err { color: ${css(dangerColor)}; font-weight: bold; }
        """.trimIndent()
    }

    private fun css(c: Color): String = "rgb(${c.red},${c.green},${c.blue})"

    private fun blendColor(fg: Color, bg: Color, alpha: Double): Color = Color(
        (fg.red * alpha + bg.red * (1 - alpha)).toInt().coerceIn(0, 255),
        (fg.green * alpha + bg.green * (1 - alpha)).toInt().coerceIn(0, 255),
        (fg.blue * alpha + bg.blue * (1 - alpha)).toInt().coerceIn(0, 255),
    )
}
