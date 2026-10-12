package com.github.catatafishen.agentbridge.ui.renderers

import com.github.catatafishen.agentbridge.client.acp.OpenCodeServerEndpoint
import com.github.catatafishen.agentbridge.client.acp.OpenCodeSessionHttpClient
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import javax.swing.JComponent

object TaskToolRenderer : ToolResultRenderer {
    private val TASK_ID_LINE = Regex("""^task_id:\s*(.+?)(?:\s+\(.*\))?\s*$""")
    private val TASK_RESULT_WRAPPER_LINE = Regex("""^\s*</?task_result>\s*$""")

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
            .filterNot { TASK_RESULT_WRAPPER_LINE.matches(it) }
            .joinToString("\n")
            .trim()

        return ParsedTaskResult(taskId, content.ifBlank { trimmed })
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

        if (parsed.taskId != null && OpenCodeServerEndpoint.baseUrl() != null) {
            panel.add(transcriptSection(parsed.taskId))
        }

        return panel
    }

    /**
     * A link that loads the subagent's child session from the OpenCode HTTP server and shows the
     * full transcript in place of the link. [taskId] is the child session ID.
     */
    private fun transcriptSection(taskId: String): JComponent {
        val section = ToolRenderers.listPanel().apply {
            border = JBUI.Borders.emptyTop(4)
            alignmentX = JComponent.LEFT_ALIGNMENT
        }
        val link = HyperlinkLabel("Show subagent transcript")
        link.addHyperlinkListener {
            link.isEnabled = false
            ApplicationManager.getApplication().executeOnPooledThread {
                val result = runCatching { OpenCodeSessionHttpClient.fetchTranscriptMarkdown(taskId) }
                ApplicationManager.getApplication().invokeLater {
                    section.removeAll()
                    section.add(
                        result.fold(
                            onSuccess = { HtmlToolRendererSupport.markdownPane(it.ifBlank { "_Empty transcript_" }) },
                            onFailure = {
                                ToolRenderers.statusHeader(
                                    ToolIcons.FAILURE,
                                    "Could not load subagent transcript: ${it.message}",
                                    ToolRenderers.FAIL_COLOR
                                )
                            }
                        )
                    )
                    section.revalidate()
                    section.repaint()
                }
            }
        }
        section.add(link)
        return section
    }
}
