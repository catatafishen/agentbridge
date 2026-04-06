package com.github.catatafishen.agentbridge.ui.renderers

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import java.awt.Font
import javax.swing.JComponent

/**
 * Renders run configuration CRUD results (create, edit, delete, run)
 * as a status card with the operation summary.
 */
object RunConfigCrudRenderer : ToolResultRenderer {

    private val CREATED = Regex("""^Created run configuration '(.+?)'\s*(?:\[(.+?)])?""")
    private val UPDATED = Regex("""^Updated run configuration '(.+?)'""")
    private val DELETED = Regex("""^Deleted run configuration '(.+?)'""")
    private val STARTED = Regex("""^(?:Started|Executed|Running) '(.+?)'""")
    private val RUN_TAB = Regex("""^Run tab:\s*(.+)""")
    private val ERROR = Regex("""^Error:\s+(.+)""", RegexOption.DOT_MATCHES_ALL)

    override fun render(output: String): JComponent? {
        val text = output.trimEnd()
        if (text.isEmpty()) return null
        val lines = text.lines()
        val firstLine = lines.first().trim()

        return tryCreated(firstLine, lines)
            ?: tryUpdated(firstLine)
            ?: tryDeleted(firstLine)
            ?: tryStarted(firstLine, lines)
            ?: tryRunTab(firstLine, lines)
            ?: tryError(firstLine)
    }

    private fun tryCreated(line: String, lines: List<String>): JComponent? {
        val m = CREATED.find(line) ?: return null
        val name = m.groupValues[1]
        val type = m.groupValues[2].ifEmpty { null }
        val panel = ToolRenderers.listPanel()
        val row = ToolRenderers.rowPanel()
        row.add(JBLabel("Created").apply {
            icon = ToolIcons.SUCCESS
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            foreground = ToolRenderers.SUCCESS_COLOR
        })
        row.add(JBLabel(name).apply { font = UIUtil.getLabelFont().deriveFont(Font.BOLD) })
        if (type != null) row.add(ToolRenderers.mutedLabel(type))
        panel.add(row)
        addDetails(panel, lines.drop(1))
        return panel
    }

    private fun tryUpdated(line: String): JComponent? {
        val m = UPDATED.find(line) ?: return null
        val panel = ToolRenderers.listPanel()
        val row = ToolRenderers.rowPanel()
        row.add(JBLabel("Updated").apply {
            icon = ToolIcons.SUCCESS
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            foreground = ToolRenderers.SUCCESS_COLOR
        })
        row.add(JBLabel(m.groupValues[1]).apply { font = UIUtil.getLabelFont().deriveFont(Font.BOLD) })
        panel.add(row)
        return panel
    }

    private fun tryDeleted(line: String): JComponent? {
        val m = DELETED.find(line) ?: return null
        val panel = ToolRenderers.listPanel()
        val row = ToolRenderers.rowPanel()
        row.add(JBLabel("Deleted").apply {
            icon = ToolIcons.SUCCESS
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            foreground = ToolRenderers.SUCCESS_COLOR
        })
        row.add(JBLabel(m.groupValues[1]).apply { font = UIUtil.getLabelFont().deriveFont(Font.BOLD) })
        panel.add(row)
        return panel
    }

    private fun tryStarted(line: String, lines: List<String>): JComponent? {
        val m = STARTED.find(line) ?: return null
        val panel = ToolRenderers.listPanel()
        val row = ToolRenderers.rowPanel()
        row.add(JBLabel("Started").apply {
            icon = ToolIcons.EXECUTE
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            foreground = ToolRenderers.SUCCESS_COLOR
        })
        row.add(JBLabel(m.groupValues[1]).apply { font = UIUtil.getLabelFont().deriveFont(Font.BOLD) })
        panel.add(row)
        addDetails(panel, lines.drop(1))
        return panel
    }

    private fun tryRunTab(line: String, lines: List<String>): JComponent? {
        val m = RUN_TAB.find(line) ?: return null
        val panel = ToolRenderers.listPanel()
        val row = ToolRenderers.rowPanel()
        row.add(JBLabel("Run tab").apply {
            icon = ToolIcons.EXECUTE
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
        })
        row.add(JBLabel(m.groupValues[1]).apply { font = UIUtil.getLabelFont().deriveFont(Font.BOLD) })
        panel.add(row)
        addDetails(panel, lines.drop(1))
        return panel
    }

    private fun tryError(line: String): JComponent? {
        val m = ERROR.find(line) ?: return null
        val panel = ToolRenderers.listPanel()
        val row = ToolRenderers.rowPanel()
        row.add(JBLabel("Error").apply {
            icon = ToolIcons.FAILURE
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            foreground = ToolRenderers.FAIL_COLOR
        })
        panel.add(row)
        panel.add(ToolRenderers.mutedLabel(m.groupValues[1]).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
        })
        return panel
    }

    private fun addDetails(panel: javax.swing.JPanel, remaining: List<String>) {
        val body = remaining.joinToString("\n").trim()
        if (body.isNotEmpty()) panel.add(ToolRenderers.codeBlock(body))
    }
}
