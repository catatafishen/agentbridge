package com.github.catatafishen.agentbridge.ui.renderers

import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import javax.swing.JComponent

object TaskToolRenderer : ToolResultRenderer {
    private val TASK_ID_LINE = Regex("""^task_id:\s*(.+?)(?:\s+\(.*\))?\s*$""")

    data class ParsedTaskResult(val taskId: String?, val content: String)

    fun parseTaskOutput(output: String): ParsedTaskResult {
        val trimmed = output.trim()
        if (trimmed.isEmpty()) {
            return ParsedTaskResult(null, "")
        }

        val lines = trimmed.lines().toMutableList()
        val taskId = TASK_ID_LINE.find(lines.firstOrNull()?.trim().orEmpty())?.groupValues?.getOrNull(1)?.trim()
        if (taskId != null && lines.isNotEmpty()) {
            lines.removeAt(0)
        }

        val content = lines
            .dropWhile { it.isBlank() }
            .joinToString("\n")
            .replace("<task_result>", "")
            .replace("</task_result>", "")
            .trim()

        return ParsedTaskResult(taskId, if (content.isNotBlank()) content else trimmed)
    }

    override fun render(output: String): JComponent? {
        val parsed = parseTaskOutput(output)
        if (parsed.taskId == null && parsed.content.isBlank()) return null

        val panel = ToolRenderers.listPanel()
        panel.add(ToolRenderers.statusHeader(ToolIcons.EXECUTE, "Subagent Result", ToolRenderers.INFO_COLOR))

        if (parsed.taskId != null) {
            val row = ToolRenderers.rowPanel()
            row.border = JBUI.Borders.emptyTop(2)
            row.add(ToolRenderers.mutedLabel("Task ID"))
            row.add(JBTextField(parsed.taskId).apply {
                isEditable = false
                border = JBUI.Borders.empty(0, 4)
                columns = parsed.taskId.length.coerceIn(12, 36)
                maximumSize = preferredSize
            })
            panel.add(row)
        }

        if (parsed.content.isNotBlank()) {
            panel.add(HtmlToolRendererSupport.markdownPane(parsed.content))
        } else {
            panel.add(ToolRenderers.mutedLabel("No subagent output").apply {
                alignmentX = JComponent.LEFT_ALIGNMENT
            })
        }

        return panel
    }
}
