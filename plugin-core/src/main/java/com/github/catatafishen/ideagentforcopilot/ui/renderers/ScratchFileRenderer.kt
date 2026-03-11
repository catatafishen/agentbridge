package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import java.awt.Font
import javax.swing.JComponent

/**
 * Renders scratch file operation results (create, run).
 */
internal object ScratchFileRenderer : ToolResultRenderer {

    private val CREATED = Regex("""^Created scratch file:\s*(.+)""")
    private val EXIT_CODE = Regex("""Exit code:\s*(\d+)""")

    override fun render(output: String): JComponent? {
        val text = output.trimEnd()
        if (text.isEmpty()) return null
        val lines = text.lines()
        val firstLine = lines.first().trim()

        return tryCreated(firstLine, lines)
            ?: tryRunOutput(text, lines)
    }

    private fun tryCreated(firstLine: String, lines: List<String>): JComponent? {
        val m = CREATED.find(firstLine) ?: return null
        val fileName = m.groupValues[1].trim()
        val panel = ToolRenderers.listPanel()
        val row = ToolRenderers.rowPanel()
        row.add(JBLabel("Created").apply {
            icon = ToolIcons.SUCCESS
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            foreground = ToolRenderers.SUCCESS_COLOR
        })
        row.add(JBLabel(fileName).apply { font = UIUtil.getLabelFont().deriveFont(Font.BOLD) })
        panel.add(row)

        val body = lines.drop(1).joinToString("\n").trim()
        if (body.isNotEmpty()) panel.add(ToolRenderers.codeBlock(body))
        return panel
    }

    private fun tryRunOutput(text: String, lines: List<String>): JComponent? {
        val exitMatch = EXIT_CODE.find(text)
        val exitCode = exitMatch?.groupValues?.get(1)?.toIntOrNull()
        val succeeded = exitCode == null || exitCode == 0

        val panel = ToolRenderers.listPanel()
        val headerRow = ToolRenderers.rowPanel()
        if (succeeded) {
            headerRow.add(JBLabel("Executed").apply {
                icon = ToolIcons.SUCCESS
                font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
                foreground = ToolRenderers.SUCCESS_COLOR
            })
        } else {
            headerRow.add(JBLabel("Failed").apply {
                icon = ToolIcons.FAILURE
                font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
                foreground = ToolRenderers.FAIL_COLOR
            })
            headerRow.add(ToolRenderers.mutedLabel("exit code $exitCode"))
        }
        panel.add(headerRow)

        val body = lines.filterNot { EXIT_CODE.containsMatchIn(it) }
            .joinToString("\n").trim()
        if (body.isNotEmpty()) panel.add(ToolRenderers.codeBlock(body))
        return panel
    }
}
