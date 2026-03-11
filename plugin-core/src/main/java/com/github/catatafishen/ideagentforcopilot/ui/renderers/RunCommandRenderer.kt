package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import java.awt.Font
import javax.swing.JComponent

/**
 * Renders run_command output with a status header (success/fail),
 * exit code, and the command output in a monospace block.
 */
internal object RunCommandRenderer : ToolResultRenderer {

    private val SUCCESS_HEADER = Regex("""^Command succeeded""")
    private val FAIL_HEADER = Regex("""^Command failed\s*\(exit code (\d+)\)""")
    private val TIMEOUT_HEADER = Regex("""^Command timed out after (\d+) seconds""")
    private val PAGINATION_NOTE = Regex("""\(showing last \d+ chars.*\)""")

    override fun render(output: String): JComponent? {
        val lines = output.trimEnd().lines()
        if (lines.isEmpty()) return null

        val firstLine = lines.first().trim()
        val successMatch = SUCCESS_HEADER.find(firstLine)
        val failMatch = FAIL_HEADER.find(firstLine)
        val timeoutMatch = TIMEOUT_HEADER.find(firstLine)

        if (successMatch == null && failMatch == null && timeoutMatch == null) return null

        val panel = ToolRenderers.listPanel()

        val headerRow = ToolRenderers.rowPanel()
        when {
            successMatch != null -> {
                headerRow.add(JBLabel("Command succeeded").apply {
                    icon = ToolIcons.SUCCESS
                    font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
                    foreground = ToolRenderers.SUCCESS_COLOR
                })
            }

            failMatch != null -> {
                headerRow.add(JBLabel("Command failed").apply {
                    icon = ToolIcons.FAILURE
                    font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
                    foreground = ToolRenderers.FAIL_COLOR
                })
                headerRow.add(ToolRenderers.mutedLabel("exit code ${failMatch.groupValues[1]}"))
            }

            timeoutMatch != null -> {
                headerRow.add(JBLabel("Timed out").apply {
                    icon = ToolIcons.TIMEOUT
                    font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
                    foreground = ToolRenderers.WARN_COLOR
                })
                headerRow.add(ToolRenderers.mutedLabel("after ${timeoutMatch.groupValues[1]}s"))
            }
        }
        panel.add(headerRow)

        val body = lines.drop(1)
            .filterNot { PAGINATION_NOTE.containsMatchIn(it) }
            .joinToString("\n")
            .trim()

        if (body.isNotEmpty()) {
            panel.add(ToolRenderers.codeBlock(body))
        }

        return panel
    }
}
