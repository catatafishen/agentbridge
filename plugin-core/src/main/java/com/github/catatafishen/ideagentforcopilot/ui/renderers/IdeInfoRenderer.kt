package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.intellij.util.ui.JBUI
import javax.swing.JComponent

/**
 * Renders IDE information tool results as structured info cards.
 *
 * Handles: get_active_file, get_open_editors, get_indexing_status,
 * get_notifications, list_themes, list_scratch_files, get_documentation,
 * read_ide_log, show_diff, edit_project_structure, search_conversation_history,
 * get_action_options.
 */
object IdeInfoRenderer : ToolResultRenderer {

    private val ACTIVE_FILE = Regex("""^(.+?)\s+\(line (\d+),\s*column (\d+)\)""")
    private val NO_EDITOR = Regex("""^No active editor""")
    private val OPEN_EDITORS = Regex("""^Open editors?\s*\((\d+)\):""")
    private val INDEXING_IDLE = Regex("""^Indexing:\s*idle""", RegexOption.IGNORE_CASE)
    private val INDEXING_PROGRESS = Regex("""^Indexing in progress""", RegexOption.IGNORE_CASE)
    private val LIST_HEADER = Regex("""^(\d+)\s+(.+?):?\s*$""")
    private val DIFF_SHOWN = Regex("""^Diff shown""")

    override fun render(output: String): JComponent? {
        val text = output.trimEnd()
        if (text.isEmpty()) return null
        val lines = text.lines()
        val firstLine = lines.first().trim()

        return tryActiveFile(firstLine)
            ?: tryNoEditor(firstLine)
            ?: tryOpenEditors(firstLine, lines)
            ?: tryIndexing(firstLine)
            ?: tryDiffShown(firstLine)
            ?: tryListOutput(firstLine, lines)
    }

    private fun tryActiveFile(line: String): JComponent? {
        val m = ACTIVE_FILE.find(line) ?: return null
        val path = m.groupValues[1].trim()
        val lineNum = m.groupValues[2].toIntOrNull() ?: 0

        val panel = ToolRenderers.listPanel()
        val row = ToolRenderers.rowPanel()
        row.add(ToolRenderers.fileLink(path.substringAfterLast('/'), path, lineNum))
        row.add(ToolRenderers.mutedLabel("line ${m.groupValues[2]}, col ${m.groupValues[3]}"))
        panel.add(row)
        return panel
    }

    private fun tryNoEditor(line: String): JComponent? {
        NO_EDITOR.find(line) ?: return null
        val panel = ToolRenderers.listPanel()
        panel.add(ToolRenderers.mutedLabel("No active editor").apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
        })
        return panel
    }

    private fun tryOpenEditors(firstLine: String, lines: List<String>): JComponent? {
        val m = OPEN_EDITORS.find(firstLine) ?: return null
        val count = m.groupValues[1].toIntOrNull() ?: 0

        val panel = ToolRenderers.listPanel()
        panel.add(ToolRenderers.headerPanel(ToolIcons.FOLDER, count, if (count == 1) "editor" else "editors"))

        for (fileLine in lines.drop(1)) {
            val trimmed = fileLine.trim()
            if (trimmed.isEmpty()) continue
            val isActive = trimmed.startsWith("*")
            val cleaned = trimmed.removePrefix("*").trim()
            val modified = cleaned.endsWith("[modified]")
            val path = cleaned.removeSuffix("[modified]").trim()

            val row = ToolRenderers.rowPanel()
            if (isActive) {
                row.add(ToolRenderers.badgeLabel("●", ToolRenderers.SUCCESS_COLOR))
            }
            row.add(ToolRenderers.fileLink(path.substringAfterLast('/'), path))
            if (modified) {
                row.add(ToolRenderers.mutedLabel("modified"))
            }
            panel.add(row)
        }
        return panel
    }

    private fun tryIndexing(firstLine: String): JComponent? {
        return when {
            INDEXING_IDLE.containsMatchIn(firstLine) -> {
                val panel = ToolRenderers.listPanel()
                panel.add(ToolRenderers.statusHeader(ToolIcons.SUCCESS, "Indexing idle", ToolRenderers.SUCCESS_COLOR))
                panel
            }

            INDEXING_PROGRESS.containsMatchIn(firstLine) -> {
                val panel = ToolRenderers.listPanel()
                panel.add(
                    ToolRenderers.statusHeader(
                        ToolIcons.EXECUTE,
                        "Indexing in progress",
                        ToolRenderers.WARN_COLOR
                    )
                )
                panel.add(ToolRenderers.mutedLabel(firstLine).apply {
                    alignmentX = JComponent.LEFT_ALIGNMENT
                })
                panel
            }

            else -> null
        }
    }

    private fun tryDiffShown(line: String): JComponent? {
        DIFF_SHOWN.find(line) ?: return null
        val panel = ToolRenderers.listPanel()
        panel.add(ToolRenderers.statusHeader(ToolIcons.SUCCESS, line, ToolRenderers.SUCCESS_COLOR))
        return panel
    }

    private fun tryListOutput(firstLine: String, lines: List<String>): JComponent? {
        val m = LIST_HEADER.find(firstLine) ?: return null
        val count = m.groupValues[1].toIntOrNull() ?: return null
        val label = m.groupValues[2].trim()

        val panel = ToolRenderers.listPanel()
        panel.add(ToolRenderers.headerPanel(ToolIcons.FOLDER, count, label))

        val items = lines.drop(1).filter { it.isNotBlank() }
        for (item in items) {
            val row = ToolRenderers.rowPanel()
            row.border = JBUI.Borders.emptyLeft(4)
            row.add(ToolRenderers.monoLabel(item.trim()))
            panel.add(row)
        }
        return panel
    }
}
