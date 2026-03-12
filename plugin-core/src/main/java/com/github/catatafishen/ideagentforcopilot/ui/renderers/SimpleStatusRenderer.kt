package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import java.awt.Font
import javax.swing.JComponent

/**
 * Renders simple status/confirmation tool results as a single-line
 * card with success/failure icon and optional file link.
 *
 * Handles: delete_file, undo, format_code, optimize_imports,
 * add_to_dictionary, suppress_inspection, open_in_editor, set_theme,
 * mark_directory, download_sources, reload_from_disk, apply_action.
 */
object SimpleStatusRenderer : ToolResultRenderer {

    private val DELETE_FILE = Regex("""^Deleted file:\s+(.+)""")
    private val UNDO = Regex("""^Undid (\d+) action\(s\) on (.+?):\s*(.*)""")
    private val FORMAT_CODE = Regex("""^Code formatted:\s+(.+)""")
    private val OPTIMIZE = Regex("""^Imports optimized:\s+(.+)""")
    private val DICTIONARY = Regex("""^Added '(.+)' to project dictionary""")
    private val SUPPRESS = Regex("""^Suppressed '(.+)' at line (\d+) in (.+)""")
    private val OPENED = Regex("""^Opened (.+?)(?:\s+\(line (\d+)\))?$""")
    private val THEME = Regex("""^Theme set to:\s+(.+)""")
    private val MARK_DIR = Regex("""^Directory (.+) marked as (.+)""")
    private val DOWNLOAD = Regex("""^Sources downloaded""")
    private val RELOAD = Regex("""^(?:File (.+)|Project root) reloaded from disk""")
    private val GENERIC_SUCCESS = Regex("""^✓\s+(.+)""")
    private val ERROR = Regex("""^Error:\s+(.+)""", RegexOption.DOT_MATCHES_ALL)

    override fun render(output: String): JComponent? {
        val text = output.trimEnd()
        if (text.isEmpty()) return null
        val firstLine = text.lines().first().trim()

        return tryDelete(firstLine)
            ?: tryUndo(firstLine)
            ?: tryFormatCode(firstLine)
            ?: tryOptimize(firstLine)
            ?: tryDictionary(firstLine)
            ?: trySuppress(firstLine)
            ?: tryOpened(firstLine)
            ?: tryTheme(firstLine)
            ?: tryMarkDir(firstLine)
            ?: tryDownload(firstLine)
            ?: tryReload(firstLine)
            ?: tryGenericSuccess(firstLine)
            ?: tryError(text)
    }

    private fun successPanel(action: String, filePath: String? = null, detail: String? = null): JComponent {
        val panel = ToolRenderers.listPanel()
        val row = ToolRenderers.rowPanel()
        row.add(JBLabel(action).apply {
            icon = ToolIcons.SUCCESS
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            foreground = ToolRenderers.SUCCESS_COLOR
        })
        if (filePath != null) {
            row.add(ToolRenderers.fileLink(filePath.substringAfterLast('/'), filePath))
        }
        panel.add(row)
        if (detail != null) {
            panel.add(ToolRenderers.mutedLabel(detail).apply {
                alignmentX = JComponent.LEFT_ALIGNMENT
            })
        }
        return panel
    }

    private fun errorPanel(message: String): JComponent {
        val panel = ToolRenderers.listPanel()
        val row = ToolRenderers.rowPanel()
        row.add(JBLabel("Error").apply {
            icon = ToolIcons.FAILURE
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            foreground = ToolRenderers.FAIL_COLOR
        })
        panel.add(row)
        panel.add(ToolRenderers.mutedLabel(message).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
        })
        return panel
    }

    private fun tryDelete(line: String): JComponent? {
        val m = DELETE_FILE.find(line) ?: return null
        return successPanel("Deleted", m.groupValues[1].trim())
    }

    private fun tryUndo(line: String): JComponent? {
        val m = UNDO.find(line) ?: return null
        val count = m.groupValues[1]
        val path = m.groupValues[2].trim()
        val actions = m.groupValues[3].trim()
        val detail = "$count action(s)" + if (actions.isNotEmpty()) ": $actions" else ""
        return successPanel("Undone", path, detail)
    }

    private fun tryFormatCode(line: String): JComponent? {
        val m = FORMAT_CODE.find(line) ?: return null
        return successPanel("Formatted", m.groupValues[1].trim())
    }

    private fun tryOptimize(line: String): JComponent? {
        val m = OPTIMIZE.find(line) ?: return null
        return successPanel("Imports optimized", m.groupValues[1].trim())
    }

    private fun tryDictionary(line: String): JComponent? {
        val m = DICTIONARY.find(line) ?: return null
        return successPanel("Added to dictionary", detail = m.groupValues[1])
    }

    private fun trySuppress(line: String): JComponent? {
        val m = SUPPRESS.find(line) ?: return null
        val inspection = m.groupValues[1]
        val lineNum = m.groupValues[2].toIntOrNull() ?: 0
        val path = m.groupValues[3].trim()
        return successPanel("Suppressed", path, "$inspection at line $lineNum")
    }

    private fun tryOpened(line: String): JComponent? {
        val m = OPENED.find(line) ?: return null
        val path = m.groupValues[1].trim()
        val lineNum = m.groupValues[2].toIntOrNull()
        val detail = if (lineNum != null) "line $lineNum" else null
        return successPanel("Opened", path, detail)
    }

    private fun tryTheme(line: String): JComponent? {
        val m = THEME.find(line) ?: return null
        return successPanel("Theme set", detail = m.groupValues[1].trim())
    }

    private fun tryMarkDir(line: String): JComponent? {
        val m = MARK_DIR.find(line) ?: return null
        return successPanel("Marked as ${m.groupValues[2].trim()}", m.groupValues[1].trim())
    }

    private fun tryDownload(line: String): JComponent? {
        DOWNLOAD.find(line) ?: return null
        return successPanel("Sources downloaded")
    }

    private fun tryReload(line: String): JComponent? {
        val m = RELOAD.find(line) ?: return null
        val path = m.groupValues[1].trim().ifEmpty { null }
        return successPanel("Reloaded from disk", path)
    }

    private fun tryGenericSuccess(line: String): JComponent? {
        val m = GENERIC_SUCCESS.find(line) ?: return null
        return successPanel(m.groupValues[1].trim())
    }

    private fun tryError(text: String): JComponent? {
        val m = ERROR.find(text.lines().first().trim()) ?: return null
        return errorPanel(m.groupValues[1].trim())
    }
}
