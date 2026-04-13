package com.github.catatafishen.agentbridge.ui.renderers

// Terminal output renderer for run_in_terminal tool results.
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Font
import javax.swing.JComponent

/**
 * Renders terminal and run output tool results with a tab-name header
 * and the output content in a monospace code block.
 *
 * Handles: read_run_output, run_in_terminal, read_terminal_output,
 * write_terminal_input.
 */
object TerminalOutputRenderer : ToolResultRenderer {

    val TAB_HEADER = Regex("""^Tab:\s*(.+)""")
    val TOTAL_LENGTH = Regex("""^Total length:\s*(\d+)\s*chars""")
    val TERMINAL_OUTPUT = Regex("""^Terminal '(.+?)' output:""")
    val TERMINAL_RUNNING = Regex("""^Running in terminal '(.+?)':\s*(.*)""")
    val TERMINAL_SENT = Regex("""^Sent to terminal '(.+?)':\s*(.*)""")

    override fun render(output: String): JComponent? {
        val text = output.trimEnd()
        if (text.isEmpty()) return null
        val lines = text.lines()
        val firstLine = lines.first().trim()

        return tryTabOutput(firstLine, lines)
            ?: tryTerminalOutput(firstLine, lines)
            ?: tryTerminalRunning(firstLine, lines)
            ?: tryTerminalSent(firstLine, lines)
    }

    private fun tryTabOutput(firstLine: String, lines: List<String>): JComponent? {
        val m = TAB_HEADER.find(firstLine) ?: return null
        val tabName = m.groupValues[1].trim()

        val panel = ToolRenderers.listPanel()
        val headerRow = ToolRenderers.rowPanel()
        headerRow.add(JBLabel(tabName).apply {
            icon = ToolIcons.EXECUTE
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            foreground = ToolRenderers.INFO_COLOR
        })

        val lengthLine = lines.getOrNull(1)?.trim()
        val lengthMatch = lengthLine?.let { TOTAL_LENGTH.find(it) }
        if (lengthMatch != null) {
            headerRow.add(ToolRenderers.mutedLabel("${lengthMatch.groupValues[1]} chars"))
        }
        panel.add(headerRow)

        val bodyStart = if (lengthMatch != null) 2 else 1
        val body = lines.drop(bodyStart).joinToString("\n").trim()
        if (body.isNotEmpty()) {
            panel.add(ToolRenderers.codeBlock(body).apply {
                border = JBUI.Borders.emptyTop(4)
            })
        }
        return panel
    }

    private fun tryTerminalOutput(firstLine: String, lines: List<String>): JComponent? {
        val m = TERMINAL_OUTPUT.find(firstLine) ?: return null
        val tabName = m.groupValues[1]

        val panel = ToolRenderers.listPanel()
        val headerRow = ToolRenderers.rowPanel()
        headerRow.add(JBLabel(tabName).apply {
            icon = ToolIcons.EXECUTE
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            foreground = ToolRenderers.INFO_COLOR
        })
        panel.add(headerRow)

        val body = lines.drop(1).joinToString("\n").trim()
        if (body.isNotEmpty()) {
            panel.add(ToolRenderers.codeBlock(body).apply {
                border = JBUI.Borders.emptyTop(4)
            })
        }
        return panel
    }

    private fun tryTerminalRunning(firstLine: String, lines: List<String>): JComponent? {
        val m = TERMINAL_RUNNING.find(firstLine) ?: return null
        val tabName = m.groupValues[1]
        val command = m.groupValues[2].trim()

        val panel = ToolRenderers.listPanel()
        val headerRow = ToolRenderers.rowPanel()
        headerRow.add(JBLabel("Running").apply {
            icon = ToolIcons.EXECUTE
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            foreground = ToolRenderers.INFO_COLOR
        })
        headerRow.add(JBLabel(tabName).apply { font = UIUtil.getLabelFont().deriveFont(Font.BOLD) })
        panel.add(headerRow)

        val body = if (command.isNotEmpty()) {
            command + "\n" + lines.drop(1).joinToString("\n")
        } else {
            lines.drop(1).joinToString("\n")
        }.trim()
        if (body.isNotEmpty()) panel.add(ToolRenderers.codeBlock(body))
        return panel
    }

    private fun tryTerminalSent(firstLine: String, lines: List<String>): JComponent? {
        val m = TERMINAL_SENT.find(firstLine) ?: return null
        val tabName = m.groupValues[1]
        val input = m.groupValues[2].trim()

        val panel = ToolRenderers.listPanel()
        val headerRow = ToolRenderers.rowPanel()
        headerRow.add(JBLabel("Sent to").apply {
            icon = ToolIcons.SUCCESS
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
        })
        headerRow.add(JBLabel(tabName).apply { font = UIUtil.getLabelFont().deriveFont(Font.BOLD) })
        panel.add(headerRow)

        if (input.isNotEmpty()) {
            panel.add(ToolRenderers.monoLabel(input).apply {
                alignmentX = JComponent.LEFT_ALIGNMENT
            })
        }

        val body = lines.drop(1).joinToString("\n").trim()
        if (body.isNotEmpty()) panel.add(ToolRenderers.codeBlock(body))
        return panel
    }
}
