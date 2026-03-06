package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.intellij.ui.JBColor
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
internal object GitStageRenderer : ToolResultRenderer {

    private val SUCCESS_PATTERN = Regex("""^✓\s+(.+)""")
    private val SUCCESS_COLOR = JBColor(Color(0x1A, 0x7F, 0x37), Color(0x3F, 0xB9, 0x50))

    override fun render(output: String): JComponent? {
        val lines = output.trimEnd().lines()
        if (lines.isEmpty()) return null
        val firstMatch = SUCCESS_PATTERN.find(lines.first()) ?: return null

        val panel = ToolRenderers.listPanel()

        val header = com.intellij.ui.components.JBLabel("✓ ${firstMatch.groupValues[1]}").apply {
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            foreground = SUCCESS_COLOR
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
                row.add(ToolRenderers.badgeLabel("+", SUCCESS_COLOR))
                row.add(ToolRenderers.monoLabel(fileLine.trim()))
            }
            panel.add(row)
        }

        return panel
    }

    private fun badgeColor(status: String): Color = when (status) {
        "A" -> SUCCESS_COLOR
        "D" -> JBColor(Color(0xCF, 0x22, 0x2E), Color(0xF8, 0x53, 0x49))
        "R", "C" -> JBColor(Color(0x9A, 0x6D, 0x00), Color(0xD2, 0x9B, 0x22))
        else -> JBColor.namedColor("Link.activeForeground", UIUtil.getLabelForeground())
    }
}
