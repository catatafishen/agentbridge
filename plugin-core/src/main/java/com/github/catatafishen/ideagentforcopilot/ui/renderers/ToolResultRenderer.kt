package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import javax.swing.*

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
 * Extended renderer that can access the tool's JSON arguments for richer
 * rendering (e.g. showing a diff of old_str → new_str for write operations).
 */
internal interface ArgumentAwareRenderer : ToolResultRenderer {
    fun render(output: String, arguments: String?): JComponent?
    override fun render(output: String): JComponent? = render(output, null)
}

/**
 * Standard icons for tool-result status indicators.
 */
internal object ToolIcons {
    val SUCCESS: Icon = AllIcons.RunConfigurations.TestPassed
    val FAILURE: Icon = AllIcons.RunConfigurations.TestFailed
    val WARNING: Icon = AllIcons.General.Warning
    val SEARCH: Icon = AllIcons.Actions.Find
    val TIMEOUT: Icon = AllIcons.Actions.StopWatch
    val EXECUTE: Icon = AllIcons.Actions.Execute
    val COVERAGE: Icon = AllIcons.RunConfigurations.TrackCoverage
    val STASH: Icon = AllIcons.Vcs.ShelveSilent
    val FOLDER: Icon = AllIcons.Nodes.Folder
}

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
        "git_unstage" to GitOperationRenderer,
        "git_push" to GitOperationRenderer,
        "git_pull" to GitOperationRenderer,
        "git_fetch" to GitOperationRenderer,
        "git_merge" to GitOperationRenderer,
        "git_rebase" to GitOperationRenderer,
        "git_cherry_pick" to GitOperationRenderer,
        "git_reset" to GitOperationRenderer,
        "git_revert" to GitOperationRenderer,
        "git_remote" to GitOperationRenderer,
        // Build & test
        "build_project" to BuildResultRenderer,
        "run_tests" to TestResultRenderer,
        "list_tests" to ListTestsRenderer,
        "list_run_configurations" to RunConfigRenderer,
        "get_coverage" to CoverageRenderer,
        // Run configuration CRUD
        "create_run_configuration" to RunConfigCrudRenderer,
        "edit_run_configuration" to RunConfigCrudRenderer,
        "delete_run_configuration" to RunConfigCrudRenderer,
        "run_configuration" to RunConfigCrudRenderer,
        // Code quality
        "run_inspections" to InspectionResultRenderer,
        "get_compilation_errors" to InspectionResultRenderer,
        "get_highlights" to InspectionResultRenderer,
        "get_problems" to InspectionResultRenderer,
        "run_qodana" to InspectionResultRenderer,
        "run_sonarqube_analysis" to InspectionResultRenderer,
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
        "glob" to GlobRenderer,
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
        // Simple status operations
        "delete_file" to SimpleStatusRenderer,
        "undo" to SimpleStatusRenderer,
        "format_code" to SimpleStatusRenderer,
        "optimize_imports" to SimpleStatusRenderer,
        "add_to_dictionary" to SimpleStatusRenderer,
        "suppress_inspection" to SimpleStatusRenderer,
        "open_in_editor" to SimpleStatusRenderer,
        "set_theme" to SimpleStatusRenderer,
        "mark_directory" to SimpleStatusRenderer,
        "download_sources" to SimpleStatusRenderer,
        "reload_from_disk" to SimpleStatusRenderer,
        "apply_quickfix" to SimpleStatusRenderer,
        // Terminal & run output
        "read_run_output" to TerminalOutputRenderer,
        "run_in_terminal" to TerminalOutputRenderer,
        "read_terminal_output" to TerminalOutputRenderer,
        "write_terminal_input" to TerminalOutputRenderer,
        // Scratch files
        "create_scratch_file" to ScratchFileRenderer,
        "run_scratch_file" to ScratchFileRenderer,
        // IDE info
        "get_active_file" to IdeInfoRenderer,
        "get_open_editors" to IdeInfoRenderer,
        "get_indexing_status" to IdeInfoRenderer,
        "get_notifications" to IdeInfoRenderer,
        "list_themes" to IdeInfoRenderer,
        "list_scratch_files" to IdeInfoRenderer,
        "get_documentation" to IdeInfoRenderer,
        "read_ide_log" to IdeInfoRenderer,
        "show_diff" to IdeInfoRenderer,
        "edit_project_structure" to IdeInfoRenderer,
        "get_chat_html" to IdeInfoRenderer,
        "search_conversation_history" to IdeInfoRenderer,
    )

    fun get(toolName: String): ToolResultRenderer? = registry[toolName]

    fun hasRenderer(toolName: String): Boolean = toolName in registry

    // ── Shared rendering utilities ────────────────────────────

    private const val MONO_FONT = "JetBrains Mono"

    /**
     * Creates a header panel with icon, count, and label (e.g., search icon + "5 results").
     */
    fun headerPanel(icon: Icon, count: Int, label: String): JPanel {
        val header = JBLabel("$count $label").apply {
            this.icon = icon
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
    fun listPanel(): JPanel = object : JPanel() {
        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        override fun getMaximumSize(): java.awt.Dimension {
            val pref = preferredSize
            return java.awt.Dimension(Int.MAX_VALUE, pref.height)
        }
    }

    /**
     * Creates a horizontal row panel for list items.
     */
    fun rowPanel(): JPanel = object : JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, JBUI.scale(4), 1)) {
        init {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
        }

        override fun getMaximumSize(): java.awt.Dimension {
            val pref = preferredSize
            return java.awt.Dimension(Int.MAX_VALUE, pref.height)
        }
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
        foreground = UIUtil.getContextHelpForeground()
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
     * Creates a clickable file-path label that opens the file in the editor.
     */
    fun fileLink(displayName: String, filePath: String, lineNumber: Int = 0): JComponent {
        return HyperlinkLabel(displayName).apply {
            toolTipText = if (lineNumber > 0) "$filePath:$lineNumber" else filePath
            addHyperlinkListener { navigateToFile(filePath, lineNumber) }
        }
    }

    private fun navigateToFile(path: String, line: Int) {
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            val basePath = project.basePath ?: continue
            val absPath = if (java.io.File(path).isAbsolute) path else "$basePath/$path"
            val vFile = LocalFileSystem.getInstance().findFileByPath(absPath) ?: continue
            OpenFileDescriptor(project, vFile, maxOf(0, line - 1), 0).navigate(true)
            return
        }
    }

    /**
     * Creates a read-only code block with monospace font and editor-matching colors.
     */
    fun codeBlock(text: String): JTextArea {
        val scheme = EditorColorsManager.getInstance().globalScheme
        return JTextArea(text).apply {
            isEditable = false
            font = JBUI.Fonts.create(MONO_FONT, UIUtil.getLabelFont().size)
            background = scheme.defaultBackground
            foreground = scheme.defaultForeground
            border = JBUI.Borders.empty(6)
            lineWrap = false
            alignmentX = JComponent.LEFT_ALIGNMENT
        }
    }

    /**
     * Creates a read-only monospace text area for displaying plain text output.
     */
    fun codePanel(text: String): JComponent {
        val scheme = EditorColorsManager.getInstance().globalScheme
        return JTextArea(text).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = JBUI.Fonts.create(MONO_FONT, UIUtil.getLabelFont().size - 1)
            background = scheme.defaultBackground
            foreground = scheme.defaultForeground
            border = JBUI.Borders.empty(6, 8)
        }
    }

    /**
     * Creates a read-only IntelliJ editor component with JSON syntax highlighting
     * and code folding support.
     */
    fun jsonEditor(jsonText: String, project: com.intellij.openapi.project.Project): JComponent {
        val jsonFileType = com.intellij.openapi.fileTypes.FileTypeManager.getInstance()
            .getFileTypeByExtension("json")
        val doc = com.intellij.openapi.editor.EditorFactory.getInstance()
            .createDocument(jsonText)
        doc.setReadOnly(true)

        val editor = com.intellij.ui.EditorTextField(doc, project, jsonFileType, true, false).apply {
            setOneLineMode(false)
            addSettingsProvider { editorEx ->
                editorEx.settings.apply {
                    isLineNumbersShown = false
                    isWhitespacesShown = false
                    isFoldingOutlineShown = true
                    additionalLinesCount = 0
                    additionalColumnsCount = 0
                    isRightMarginShown = false
                    isCaretRowShown = false
                    isUseSoftWraps = false
                }
                editorEx.setHorizontalScrollbarVisible(true)
                editorEx.setVerticalScrollbarVisible(true)
                editorEx.setBorder(JBUI.Borders.empty())
            }
            border = JBUI.Borders.empty()
        }

        return editor
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

    fun blendColor(fg: Color, bg: Color, alpha: Double): Color = Color(
        (fg.red * alpha + bg.red * (1 - alpha)).toInt().coerceIn(0, 255),
        (fg.green * alpha + bg.green * (1 - alpha)).toInt().coerceIn(0, 255),
        (fg.blue * alpha + bg.blue * (1 - alpha)).toInt().coerceIn(0, 255),
    )
}
