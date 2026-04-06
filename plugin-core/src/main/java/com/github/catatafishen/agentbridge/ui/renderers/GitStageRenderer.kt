package com.github.catatafishen.agentbridge.ui.renderers

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Font
import javax.swing.JComponent

/**
 * Renders git stage results as a card with staged file list.
 *
 * Expected formats:
 * - `✓ Staged N file(s):\npath1\npath2`
 * - `✓ Staged all changes:\nM\tpath1\nA\tpath2`
 * - `✓ Nothing to stage`
 * - Error output (fallback)
 */
object GitStageRenderer : ToolResultRenderer {

    private val SUCCESS_PATTERN = Regex("""^✓\s+(.+)""")

    override fun render(output: String): JComponent? {
        val lines = output.trimEnd().lines()
        if (lines.isEmpty()) return null
        val firstMatch = SUCCESS_PATTERN.find(lines.first()) ?: return null

        val panel = ToolRenderers.listPanel()

        val header = JBLabel(firstMatch.groupValues[1]).apply {
            icon = ToolIcons.SUCCESS
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            foreground = ToolRenderers.SUCCESS_COLOR
            alignmentX = JComponent.LEFT_ALIGNMENT
        }
        panel.add(header)

        val fileLines = lines.drop(1).filter { it.isNotBlank() }
        for (fileLine in fileLines) {
            val row = ToolRenderers.rowPanel()
            val tabIdx = fileLine.indexOf('\t')
            if (tabIdx >= 0) {
                val status = fileLine.substring(0, tabIdx).trim()
                val path = fileLine.substring(tabIdx + 1).trim()
                row.add(ToolRenderers.badgeLabel(status, badgeColor(status)))
                row.add(ToolRenderers.monoLabel(path))
            } else {
                row.add(ToolRenderers.badgeLabel("+", ToolRenderers.SUCCESS_COLOR))
                row.add(ToolRenderers.monoLabel(fileLine.trim()))
            }
            panel.add(row)
        }

        return panel
    }

    private fun badgeColor(status: String): Color = when (status) {
        "A" -> ToolRenderers.ADD_COLOR
        "D" -> ToolRenderers.DEL_COLOR
        "R", "C" -> ToolRenderers.MOD_COLOR
        else -> JBColor.namedColor("Link.activeForeground", UIUtil.getLabelForeground())
    }
}
