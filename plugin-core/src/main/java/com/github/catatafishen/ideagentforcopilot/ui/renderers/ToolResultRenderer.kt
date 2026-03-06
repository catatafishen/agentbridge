package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.UIManager

/**
 * Interface for custom tool-result renderers in the tool-call popup.
 * Each implementation transforms raw tool output text into a Swing component.
 */
internal fun interface ToolResultRenderer {
    /**
     * Render tool output as a Swing component, or return null to fall back
     * to a default monospace text area.
     */
    fun render(output: String): JComponent?
}

/**
 * Registry of tool-result renderers and shared rendering utilities.
 */
internal object ToolRenderers {

    private val registry: Map<String, ToolResultRenderer> = mapOf(
        // Git
        "git_commit" to GitCommitRenderer,
        "git_status" to GitStatusRenderer,
        "git_diff" to GitDiffRenderer,
        "git_log" to GitLogRenderer,
        "git_show" to GitShowRenderer,
        "git_blame" to GitBlameRenderer,
        "git_branch" to GitBranchRenderer,
        "git_tag" to GitTagRenderer,
        "git_stash" to GitStashRenderer,
        "git_stage" to GitStageRenderer,
        // Build & test
        "build_project" to BuildResultRenderer,
        "run_tests" to TestResultRenderer,
        "list_tests" to ListTestsRenderer,
        "list_run_configurations" to RunConfigRenderer,
        "get_coverage" to CoverageRenderer,
        // Code quality
        "run_inspections" to InspectionResultRenderer,
        "get_compilation_errors" to InspectionResultRenderer,
        "get_highlights" to InspectionResultRenderer,
        "get_problems" to InspectionResultRenderer,
        // Search & navigation
        "search_text" to SearchResultRenderer,
        "search_symbols" to SearchResultRenderer,
        "find_references" to SearchResultRenderer,
        "get_file_outline" to FileOutlineRenderer,
        "get_class_outline" to ClassOutlineRenderer,
        // Navigation
        "go_to_declaration" to GoToDeclarationRenderer,
        "get_type_hierarchy" to TypeHierarchyRenderer,
        // Refactoring
        "refactor" to RefactorRenderer,
        // Project & files
        "list_project_files" to ListProjectFilesRenderer,
        "get_project_info" to ProjectInfoRenderer,
        // Infrastructure
        "run_command" to RunCommandRenderer,
        // HTTP
        "http_request" to HttpRequestRenderer,
        // File I/O
        "intellij_read_file" to ReadFileRenderer,
        "read_file" to ReadFileRenderer,
        "intellij_write_file" to WriteFileRenderer,
        "write_file" to WriteFileRenderer,
        "create_file" to WriteFileRenderer,
        // Symbol editing
        "replace_symbol_body" to ReplaceSymbolRenderer,
        "insert_before_symbol" to ReplaceSymbolRenderer,
        "insert_after_symbol" to ReplaceSymbolRenderer,
    )

    fun get(toolName: String): ToolResultRenderer? = registry[toolName]

    fun hasRenderer(toolName: String): Boolean = toolName in registry

    // ── Shared rendering utilities ────────────────────────────

    private const val MONO_FONT = "JetBrains Mono"

    /**
     * Creates a header panel with icon, count, and label (e.g., "🏷 5 tags").
     */
    fun headerPanel(icon: String, count: Int, label: String): JPanel {
        val header = JBLabel("$icon $count $label").apply {
            font = UIUtil.getLabelFont().deriveFont(java.awt.Font.BOLD)
            border = JBUI.Borders.emptyBottom(4)
            alignmentX = JComponent.LEFT_ALIGNMENT
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(header)
        }
    }

    /**
     * Creates a vertical list panel with standard spacing.
     */
    fun listPanel(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    /**
     * Creates a horizontal row panel for list items.
     */
    fun rowPanel(): JPanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 1)).apply {
        isOpaque = false
        alignmentX = JComponent.LEFT_ALIGNMENT
    }

    /**
     * Creates a monospace JBLabel.
     */
    fun monoLabel(text: String): JBLabel = JBLabel(text).apply {
        font = JBUI.Fonts.create(MONO_FONT, UIUtil.getLabelFont().size)
    }

    /**
     * Creates a muted-color JBLabel for secondary information.
     */
    fun mutedLabel(text: String): JBLabel = JBLabel(text).apply {
        foreground = blendColor(UIUtil.getLabelForeground(), UIUtil.getPanelBackground(), 0.55)
        font = UIUtil.getLabelFont().deriveFont(UIUtil.getLabelFont().size2D - 1f)
    }

    /**
     * Creates a bold monospace badge label (e.g., status codes like "M", "A", "D").
     */
    fun badgeLabel(text: String, color: Color): JBLabel = JBLabel(text).apply {
        font = JBUI.Fonts.create(MONO_FONT, UIUtil.getLabelFont().size - 1).deriveFont(java.awt.Font.BOLD)
        foreground = color
    }

    /**
     * Creates a read-only code block with monospace font and subtle background.
     */
    fun codeBlock(text: String): JTextArea = JTextArea(text).apply {
        isEditable = false
        font = JBUI.Fonts.create(MONO_FONT, UIUtil.getLabelFont().size)
        background = blendColor(UIUtil.getPanelBackground(), UIUtil.getLabelForeground(), 0.05)
        foreground = UIUtil.getLabelForeground()
        border = JBUI.Borders.empty(6)
        lineWrap = false
        alignmentX = JComponent.LEFT_ALIGNMENT
    }

    /**
     * Creates a read-only monospace text area for displaying plain text output.
     */
    fun codePanel(text: String): JComponent {
        return JTextArea(text).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = JBUI.Fonts.create("JetBrains Mono", UIUtil.getLabelFont().size - 1)
            background = UIManager.getColor("Editor.backgroundColor")
                ?: JBColor(Color(0xF0, 0xF0, 0xF0), Color(0x2B, 0x2D, 0x30))
            foreground = UIUtil.getLabelForeground()
            border = JBUI.Borders.empty(6, 8)
        }
    }

    /**
     * Transitional bridge: wraps pre-rendered HTML in a JEditorPane.
     * Use this only during migration; new renderers should build native Swing components.
     */
    fun htmlPanel(html: String): JComponent {
        val css = buildBridgeCss()
        val doc = "<html><head><style>$css</style></head><body>$html</body></html>"
        return JEditorPane().apply {
            contentType = "text/html"
            isEditable = false
            text = doc
            caretPosition = 0
            border = JBUI.Borders.empty()
            background = UIUtil.getPanelBackground()
        }
    }

    // ── Shared parsing utilities ──────────────────────────────

    data class DiffStats(val files: String, val insertions: String, val deletions: String)

    fun parseDiffStats(line: String): DiffStats {
        val filesMatch = Regex("""\d+ files? changed""").find(line)
        val insMatch = Regex("""(\d+) insertions?\(\+\)""").find(line)
        val delMatch = Regex("""(\d+) deletions?\(-\)""").find(line)
        return DiffStats(
            files = filesMatch?.value ?: line,
            insertions = insMatch?.groupValues?.get(1) ?: "",
            deletions = delMatch?.groupValues?.get(1) ?: "",
        )
    }

    fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;")

    private fun buildBridgeCss(): String {
        val fg = UIUtil.getLabelForeground()
        val bg = UIUtil.getPanelBackground()
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
                margin: 4px 0;
                line-height: 1.45;
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
            .git-stage-header {
                color: ${css(successColor)};
                font-weight: bold;
                padding: 4px 6px;
                margin-bottom: 6px;
            }
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
            .build-result { line-height: 1.5; }
            .build-status-success { color: ${css(successColor)}; font-weight: bold; }
            .build-status-fail { color: ${css(dangerColor)}; font-weight: bold; }
            .test-result-summary { font-weight: bold; margin-bottom: 4px; }
            .test-pass { color: ${css(successColor)}; }
            .test-fail { color: ${css(dangerColor)}; }
            .test-skip { color: ${css(muted)}; }
            .inspection-result { line-height: 1.5; }
            .inspection-error { color: ${css(dangerColor)}; }
            .inspection-warning { color: ${css(warningColor)}; }
            .inspection-info { color: ${css(muted)}; }
            .inspection-file-count { color: ${css(muted)}; font-size: ${font.size - 2}pt; }
            .outline-result { line-height: 1.5; }
            .outline-header { font-weight: bold; margin-bottom: 4px; }
            .outline-kind { color: ${css(linkColor)}; font-weight: bold; font-size: ${font.size - 2}pt; }
            .outline-name { font-family: $monoFont; }
            .outline-section-items { padding-left: 4px; }
            .outline-item { padding: 1px 0; }
            .git-blame-result { line-height: 1.5; }
            .file-content-result { line-height: 1.4; }
            .write-result { line-height: 1.5; }
            .write-success { color: ${css(successColor)}; }
            .cmd-result { line-height: 1.4; }
            .refactor-result { line-height: 1.5; }
            .project-info { line-height: 1.5; }
            .project-info-header { font-weight: bold; font-size: ${font.size}pt; margin-bottom: 4px; }
            .project-info-kv { padding: 1px 0; }
            .project-info-key { color: ${css(muted)}; font-weight: bold; margin-right: 6px; }
            .project-info-value { font-family: $monoFont; }
            .project-info-section { margin-top: 6px; }
            .project-info-item { padding: 1px 0; padding-left: 12px; font-family: $monoFont; }
            .http-result { line-height: 1.45; }
            .http-status-ok { color: ${css(successColor)}; font-weight: bold; }
            .http-status-err { color: ${css(dangerColor)}; font-weight: bold; }
            .git-commit-hash { font-family: $monoFont; color: ${css(linkColor)}; }
            .badge-field { color: ${css(linkColor)}; }
            .badge-enum { color: ${css(warningColor)}; }
            .coverage-bar { display: inline-block; height: 8px; }
            .coverage-bar-filled { background: ${css(successColor)}; }
            .coverage-bar-empty { background: ${css(borderColor)}; }
            a { color: ${css(linkColor)}; }
        """.trimIndent()
    }

    private fun css(c: Color): String = "rgb(${c.red},${c.green},${c.blue})"

    fun blendColor(fg: Color, bg: Color, alpha: Double): Color = Color(
        (fg.red * alpha + bg.red * (1 - alpha)).toInt().coerceIn(0, 255),
        (fg.green * alpha + bg.green * (1 - alpha)).toInt().coerceIn(0, 255),
        (fg.blue * alpha + bg.blue * (1 - alpha)).toInt().coerceIn(0, 255),
    )
}
