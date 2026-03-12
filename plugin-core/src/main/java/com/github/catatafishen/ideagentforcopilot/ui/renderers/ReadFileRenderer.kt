package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Font
import javax.swing.JComponent

/**
 * Renders intellij_read_file output as a code block with line numbers
 * and automatic truncation for very large files.
 */
object ReadFileRenderer : ToolResultRenderer {

    private const val MAX_DISPLAY_LINES = 80
    private val NUMBERED_LINE = Regex("""^(\d+): (.*)""")

    override fun render(output: String): JComponent? {
        if (output.startsWith("Error") || output.startsWith("File not found")) return null

        val raw = output.trimEnd()
        if (raw.isEmpty()) {
            val panel = ToolRenderers.listPanel()
            panel.add(ToolRenderers.mutedLabel("Empty file").apply {
                alignmentX = JComponent.LEFT_ALIGNMENT
            })
            return panel
        }

        val lines = raw.lines()
        val isNumbered = lines.firstOrNull()?.let { NUMBERED_LINE.matches(it) } == true

        val panel = ToolRenderers.listPanel()

        // Header with line range
        val headerRow = ToolRenderers.rowPanel()
        headerRow.border = JBUI.Borders.emptyBottom(4)
        if (isNumbered) {
            val firstNum = NUMBERED_LINE.find(lines.first())?.groupValues?.get(1) ?: "?"
            val lastNum = NUMBERED_LINE.find(lines.last())?.groupValues?.get(1) ?: "?"
            headerRow.add(JBLabel("Lines $firstNum–$lastNum").apply {
                font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            })
        } else {
            headerRow.add(JBLabel("${lines.size} lines").apply {
                font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            })
        }
        panel.add(headerRow)

        // Code content
        val truncated = lines.size > MAX_DISPLAY_LINES
        val displayLines = if (truncated) lines.take(MAX_DISPLAY_LINES) else lines
        val codeText = if (isNumbered) {
            displayLines.joinToString("\n") { line ->
                val m = NUMBERED_LINE.find(line)
                if (m != null) "${m.groupValues[1].padStart(4)}: ${m.groupValues[2]}" else line
            }
        } else {
            displayLines.joinToString("\n")
        }

        panel.add(ToolRenderers.codeBlock(codeText))

        if (truncated) {
            val remaining = lines.size - MAX_DISPLAY_LINES
            panel.add(ToolRenderers.mutedLabel("⋯ $remaining more lines").apply {
                alignmentX = JComponent.LEFT_ALIGNMENT
                border = JBUI.Borders.emptyTop(4)
            })
        }

        return panel
    }
}
