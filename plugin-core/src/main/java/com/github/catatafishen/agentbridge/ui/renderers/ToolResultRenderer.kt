package com.github.catatafishen.agentbridge.ui.renderers

import com.github.catatafishen.agentbridge.services.ToolRegistry
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.ide.scratch.ScratchFileService
import com.intellij.ide.scratch.ScratchRootType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Interface for custom tool-result renderers in the tool-call popup.
 * Each implementation transforms raw tool output text into a Swing component.
 */
fun interface ToolResultRenderer {
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
interface ArgumentAwareRenderer : ToolResultRenderer {
    fun render(output: String, arguments: String?): JComponent?
    override fun render(output: String): JComponent? = render(output, null)
}

object ToolIcons {
    val SUCCESS: Icon = AllIcons.RunConfigurations.TestPassed
    val FAILURE: Icon = AllIcons.RunConfigurations.TestFailed
    val WARNING: Icon = AllIcons.General.Warning
    val SEARCH: Icon = AllIcons.Actions.Find
    val TIMEOUT: Icon = AllIcons.Actions.StopWatch
    val EXECUTE: Icon = AllIcons.Actions.Execute
    val COVERAGE: Icon = AllIcons.RunConfigurations.TrackCoverage
    val STASH: Icon = AllIcons.Vcs.ShelveSilent
    val FOLDER: Icon = AllIcons.Nodes.Folder
    val TEST: Icon = AllIcons.Nodes.Test
    val TAG: Icon = AllIcons.General.Pin_tab
}

object ToolRenderers {

    /**
     * Best-effort renderers for built-in Copilot CLI tools that are not registered
     * in our MCP ToolRegistry. Keyed by the bare tool name (after prefix stripping).
     * Renderers are defensive: they return null when output doesn't match expectations,
     * and the caller falls back to codePanel().
     */
    private val BUILTIN_RENDERERS: Map<String, ToolResultRenderer> = mapOf(
        // Copilot CLI built-in tools
        "update_todo" to TodoRenderer,
        "glob" to GlobRenderer,
        "Glob" to GlobRenderer,
        "view" to WriteFileRenderer,
        "View" to WriteFileRenderer,
        "edit" to WriteFileRenderer,
        "Edit" to WriteFileRenderer,
        "write" to WriteFileRenderer,
        "Write" to WriteFileRenderer,
        "Read" to ReadFileRenderer,
        "edit_text" to WriteFileRenderer,
        "read_file" to ReadFileRenderer,
        "write_file" to WriteFileRenderer,
        "create_file" to WriteFileRenderer,
        // OpenCode / Claude built-in tools
        "task" to TaskToolRenderer,
        "bash" to RunCommandRenderer,
        "todowrite" to TodoRenderer,
        "TodoWrite" to TodoRenderer,
        "list" to ListProjectFilesRenderer,
        "ls" to ListProjectFilesRenderer,
        "patch" to WriteFileRenderer,
        "webfetch" to WebFetchRenderer,
        "websearch" to WebSearchRenderer,
        "grep" to GlobRenderer,  // similar list output format
        "Grep" to GlobRenderer,
    )

    /**
     * Resolves a renderer for a tool. Checks the MCP registry first, then falls
     * back to the built-in renderer map for Copilot CLI tools.
     * Returns null if no renderer is found.
     */
    fun get(
        toolName: String,
        registry: ToolRegistry?
    ): ToolResultRenderer? {
        val def = registry?.findById(toolName)
        if (def != null) return def.resultRenderer() as? ToolResultRenderer
        return BUILTIN_RENDERERS[toolName]
    }

    /**
     * Checks whether a tool has a custom renderer, either via its registry
     * definition or via the built-in renderer map.
     */
    fun hasRenderer(
        toolName: String,
        registry: ToolRegistry?
    ): Boolean {
        val def = registry?.findById(toolName)
        if (def != null) return def.resultRenderer() != null
        return BUILTIN_RENDERERS.containsKey(toolName)
    }

    // ── Semantic colors — shared across all renderers ────────

    val SUCCESS_COLOR: JBColor = JBColor(Color(0x1A, 0x7F, 0x37), Color(0x3F, 0xB9, 0x50))
    val FAIL_COLOR: JBColor = JBColor(Color(0xCF, 0x22, 0x2E), Color(0xF8, 0x53, 0x49))
    val WARN_COLOR: JBColor = JBColor(Color(0x9A, 0x6D, 0x00), Color(0xD2, 0x9B, 0x22))
    val ADD_COLOR: JBColor = SUCCESS_COLOR
    val DEL_COLOR: JBColor = FAIL_COLOR
    val MOD_COLOR: JBColor = WARN_COLOR
    val MUTED_COLOR: JBColor = JBColor(Color(0x6E, 0x77, 0x81), Color(0x8B, 0x94, 0x9E))
    val INFO_COLOR: JBColor = JBColor(Color(0x3A, 0x95, 0x95), Color(100, 185, 185))
    val CLASS_COLOR: JBColor = JBColor(Color(0x08, 0x69, 0xDA), Color(0x58, 0xA6, 0xFF))
    val INTERFACE_COLOR: JBColor = JBColor(Color(0x1A, 0x7F, 0x37), Color(0x3F, 0xB9, 0x50))
    val METHOD_COLOR: JBColor = JBColor(Color(0x9A, 0x6D, 0x00), Color(0xD2, 0x9B, 0x22))
    val FIELD_COLOR: JBColor = JBColor(Color(0x8E, 0x44, 0xAD), Color(0xBB, 0x6B, 0xD9))

    /** Maximum entries rendered in list-style renderers before truncation. */
    const val MAX_LIST_ENTRIES = 50

    // ── Shared rendering utilities ────────────────────────────

    private const val MONO_FONT = "JetBrains Mono"

    fun headerPanel(icon: Icon, count: Int, label: String): JBPanel<*> {
        val header = JBLabel("$count $label").apply {
            this.icon = icon
            font = UIUtil.getLabelFont().deriveFont(java.awt.Font.BOLD)
            border = JBUI.Borders.emptyBottom(4)
            alignmentX = JComponent.LEFT_ALIGNMENT
        }
        return JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(header)
        }
    }

    fun listPanel(): JBPanel<*> = object : JBPanel<JBPanel<*>>() {
        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        override fun getMaximumSize(): java.awt.Dimension {
            val pref = preferredSize
            return java.awt.Dimension(Int.MAX_VALUE, pref.height)
        }
    }

    fun rowPanel(): JBPanel<*> =
        object : JBPanel<JBPanel<*>>(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(1))) {
            init {
                isOpaque = false
                alignmentX = LEFT_ALIGNMENT
            }

            override fun getMaximumSize(): java.awt.Dimension {
                val pref = preferredSize
                return java.awt.Dimension(Int.MAX_VALUE, pref.height)
            }
        }

    fun sectionPanel(label: String, count: Int, topGap: Int = 4): JBPanel<*> {
        val section = listPanel().apply {
            border = JBUI.Borders.emptyTop(topGap)
            alignmentX = JComponent.LEFT_ALIGNMENT
        }
        val header = rowPanel()
        header.add(JBLabel(label).apply {
            font = UIUtil.getLabelFont().deriveFont(java.awt.Font.BOLD)
        })
        header.add(mutedLabel("$count"))
        section.add(header)
        return section
    }

    fun statusHeader(icon: Icon, text: String, color: Color): JBPanel<*> {
        return rowPanel().also { row ->
            row.add(JBLabel(text).apply {
                this.icon = icon
                font = UIUtil.getLabelFont().deriveFont(java.awt.Font.BOLD)
                foreground = color
            })
        }
    }

    /**
     * Creates a monospace JBLabel.
     */
    fun monoLabel(text: String): JBLabel = JBLabel(text).apply {
        font = JBUI.Fonts.create(MONO_FONT, UIUtil.getLabelFont().size)
    }

    fun mutedLabel(text: String): JBLabel = JBLabel(text).apply {
        foreground = UIUtil.getContextHelpForeground()
        font = JBUI.Fonts.smallFont()
    }

    /**
     * Creates a bold monospace badge label (e.g., status codes like "M", "A", "D").
     * Sets an accessible name so screen readers announce meaningful text.
     */
    fun badgeLabel(text: String, color: Color): JBLabel = JBLabel(text).apply {
        font = JBUI.Fonts.create(MONO_FONT, UIUtil.getLabelFont().size - 1).deriveFont(java.awt.Font.BOLD)
        foreground = color
        accessibleContext.accessibleName = BADGE_ACCESSIBLE_NAMES[text] ?: text
    }

    private val BADGE_ACCESSIBLE_NAMES = mapOf(
        "A" to "Added", "M" to "Modified", "D" to "Deleted",
        "U" to "Unmerged", "R" to "Renamed", "C" to "Copied",
        "?" to "Untracked", "+" to "Staged",
        "E" to "Error", "W" to "Warning", "w" to "Weak warning", "I" to "Info",
    )

    /**
     * Adds a "⋯ N more" truncation indicator to a list panel.
     */
    fun addTruncationIndicator(panel: JPanel, remaining: Int, noun: String = "entries") {
        panel.add(mutedLabel("⋯ $remaining more $noun").apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyTop(4)
        })
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

    fun codeBlock(text: String): JBTextArea {
        val scheme = EditorColorsManager.getInstance().globalScheme
        return object : JBTextArea(text) {
            override fun getMaximumSize(): java.awt.Dimension {
                val pref = preferredSize
                return java.awt.Dimension(Int.MAX_VALUE, pref.height)
            }
        }.apply {
            isEditable = false
            font = JBUI.Fonts.create(MONO_FONT, UIUtil.getLabelFont().size)
            background = scheme.defaultBackground
            foreground = scheme.defaultForeground
            border = JBUI.Borders.empty(6)
            lineWrap = true
            wrapStyleWord = false
            alignmentX = JComponent.LEFT_ALIGNMENT
        }
    }

    fun codePanel(text: String): JComponent {
        val scheme = EditorColorsManager.getInstance().globalScheme
        return JBTextArea(text).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = JBUI.Fonts.create(MONO_FONT, UIUtil.getLabelFont().size - 1)
            background = scheme.defaultBackground
            foreground = scheme.defaultForeground
            border = JBUI.Borders.empty(6, 8)
        }
    }

    /** Lines at or below this count are rendered inline; above it a scratch link is offered instead. */
    const val CODE_SCRATCH_THRESHOLD = 10

    /**
     * Renders text inline when short (≤ CODE_SCRATCH_THRESHOLD lines), or shows a truncated preview
     * plus a "View in scratch file" hyperlink when long.
     */
    fun codeOrScratchPanel(text: String, extension: String = "txt"): JComponent {
        val lines = text.lines()
        if (lines.size <= CODE_SCRATCH_THRESHOLD) return codePanel(text)

        val panel = listPanel().apply { alignmentX = JComponent.LEFT_ALIGNMENT }
        val previewText = lines.take(CODE_SCRATCH_THRESHOLD).joinToString("\n")
        panel.add(codePanel(previewText))
        val row = rowPanel()
        row.add(mutedLabel("⋯ ${lines.size - CODE_SCRATCH_THRESHOLD} more lines  "))
        row.add(scratchLink("Open in scratch file", text, extension).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
        })
        panel.add(row)
        return panel
    }

    /**
     * Creates a hyperlink that opens [content] as a scratch file with the given [extension].
     */
    fun scratchLink(label: String, content: String, extension: String = "txt"): HyperlinkLabel {
        return HyperlinkLabel(label).apply {
            toolTipText = "Open full content in a scratch file"
            addHyperlinkListener {
                val project = ProjectManager.getInstance().openProjects
                    .firstOrNull { !it.isDisposed } ?: return@addHyperlinkListener
                val name = "snippet.$extension"
                ApplicationManager.getApplication().invokeLater {
                    val scratchService = ScratchFileService.getInstance()
                    val scratchRoot = ScratchRootType.getInstance()
                    val file = ApplicationManager.getApplication().runWriteAction(Computable {
                        try {
                            val f = scratchService.findFile(
                                scratchRoot, name, ScratchFileService.Option.create_new_always
                            )
                            f?.getOutputStream(null)?.use { out -> out.write(content.toByteArray(Charsets.UTF_8)) }
                            f
                        } catch (_: Exception) {
                            null
                        }
                    })
                    if (file != null) FileEditorManager.getInstance(project).openFile(file, true)
                }
            }
        }
    }

    /**
     * Creates a hyperlink that opens a two-panel diff viewer with [leftContent] vs [rightContent].
     */
    fun diffViewerLink(
        label: String,
        leftTitle: String,
        leftContent: String,
        rightTitle: String,
        rightContent: String,
    ): HyperlinkLabel {
        return HyperlinkLabel(label).apply {
            toolTipText = "Open in diff viewer"
            addHyperlinkListener {
                val project = ProjectManager.getInstance().openProjects
                    .firstOrNull { !it.isDisposed } ?: return@addHyperlinkListener
                ApplicationManager.getApplication().invokeLater {
                    val left = DiffContentFactory.getInstance().create(leftContent)
                    val right = DiffContentFactory.getInstance().create(rightContent)
                    val request = SimpleDiffRequest(label, left, right, leftTitle, rightTitle)
                    DiffManager.getInstance().showDiff(project, request)
                }
            }
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
