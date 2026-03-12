package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Font
import javax.swing.JComponent

/**
 * Renderer for http_request output.
 * Input: "HTTP {code} {message}\n\n--- Headers ---\n...\n\n--- Body ---\n..."
 */
object HttpRequestRenderer : ToolResultRenderer {

    private val STATUS_LINE = Regex("""^HTTP\s+(\d+)\s+(.*)""")
    private const val MAX_BODY_LINES = 100

    override fun render(output: String): JComponent? {
        val statusMatch = output.lines().firstOrNull()?.let { STATUS_LINE.find(it.trim()) } ?: return null
        val code = statusMatch.groupValues[1].toIntOrNull() ?: return null
        val message = statusMatch.groupValues[2]

        val statusColor = when (code) {
            in 200..299 -> ToolRenderers.SUCCESS_COLOR
            in 300..399 -> ToolRenderers.WARN_COLOR
            else -> ToolRenderers.FAIL_COLOR
        }

        val headersStart = output.indexOf("--- Headers ---")
        val bodyStart = output.indexOf("--- Body ---")

        val headers = if (headersStart >= 0) {
            val end = if (bodyStart >= 0) bodyStart else output.length
            output.substring(headersStart + "--- Headers ---".length, end)
                .trim().lines().filter { it.isNotBlank() }
        } else emptyList()

        val body = if (bodyStart >= 0) output.substring(bodyStart + "--- Body ---".length).trim() else ""

        val panel = ToolRenderers.listPanel()

        // Status header
        val statusRow = ToolRenderers.rowPanel()
        statusRow.add(JBLabel("HTTP $code $message").apply {
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            foreground = statusColor
        })
        panel.add(statusRow)

        // Headers
        if (headers.isNotEmpty()) {
            val section = ToolRenderers.sectionPanel("Headers", headers.size)
            for (h in headers) {
                val colonIdx = h.indexOf(':')
                val row = ToolRenderers.rowPanel()
                row.border = JBUI.Borders.emptyLeft(8)
                if (colonIdx > 0) {
                    row.add(ToolRenderers.mutedLabel(h.substring(0, colonIdx).trim() + ":"))
                    row.add(ToolRenderers.monoLabel(h.substring(colonIdx + 1).trim()))
                } else {
                    row.add(ToolRenderers.monoLabel(h))
                }
                section.add(row)
            }
            panel.add(section)
        }

        // Body with truncation
        if (body.isNotEmpty()) {
            val bodySection = ToolRenderers.listPanel().apply {
                border = JBUI.Borders.emptyTop(6)
                alignmentX = JComponent.LEFT_ALIGNMENT
            }
            bodySection.add(JBLabel("Body").apply {
                font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
                alignmentX = JComponent.LEFT_ALIGNMENT
            })
            val bodyLines = body.lines()
            val truncatedBody = if (bodyLines.size > MAX_BODY_LINES) {
                bodyLines.take(MAX_BODY_LINES).joinToString("\n")
            } else {
                body
            }
            bodySection.add(ToolRenderers.codeBlock(truncatedBody))
            if (bodyLines.size > MAX_BODY_LINES) {
                ToolRenderers.addTruncationIndicator(bodySection, bodyLines.size - MAX_BODY_LINES, "lines")
            }
            panel.add(bodySection)
        }

        return panel
    }
}
