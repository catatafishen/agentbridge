package com.github.catatafishen.agentbridge.ui.renderers

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Font
import javax.swing.JComponent

/**
 * Renders git show output as a commit card with metadata and optional diff stats.
 */
object GitShowRenderer : ToolResultRenderer {

    private val SUMMARY_PATTERN = Regex(""".*\d+ files? changed.*""")
    private const val COMMIT_PREFIX = "commit "

    private data class ParsedShow(
        val hash: String,
        var author: String = "",
        var date: String = "",
        val messageLines: MutableList<String> = mutableListOf(),
        val statLines: MutableList<String> = mutableListOf(),
        var summaryLine: String = "",
    )

    override fun render(output: String): JComponent? {
        val lines = output.trimEnd().lines()
        if (lines.isEmpty() || !lines.first().startsWith(COMMIT_PREFIX)) return null

        val parsed = parseLines(lines)
        val message = parsed.messageLines.joinToString(" ").trim()
        if (message.isEmpty() && parsed.statLines.isEmpty()) return null

        val panel = ToolRenderers.listPanel()
        addHeader(panel, parsed)
        if (parsed.date.isNotEmpty()) addDate(panel, parsed.date)
        if (message.isNotEmpty()) addMessage(panel, message)
        if (parsed.summaryLine.isNotEmpty()) addStats(panel, parsed.summaryLine)
        addStatLines(panel, parsed.statLines)
        return panel
    }

    private fun parseLines(lines: List<String>): ParsedShow {
        val parsed = ParsedShow(hash = lines.first().removePrefix(COMMIT_PREFIX).trim().take(8))
        var inMessage = false
        var inDiff = false

        for (line in lines.drop(1)) {
            when {
                inDiff -> { /* skip diff content */
                }

                line.startsWith("diff --git") -> inDiff = true
                line.startsWith("Author:") -> parsed.author = line.removePrefix("Author:").trim()
                line.startsWith("Date:") -> {
                    parsed.date = line.removePrefix("Date:").trim(); inMessage = true
                }

                line.isBlank() && !inMessage && parsed.author.isNotEmpty() -> inMessage = true
                inMessage && line.isBlank() -> inMessage = false
                inMessage -> parsed.messageLines.add(line.trim())
                line.matches(SUMMARY_PATTERN) -> parsed.summaryLine = line.trim()
                line.contains(" | ") -> parsed.statLines.add(line)
            }
        }
        return parsed
    }

    private fun addHeader(panel: javax.swing.JPanel, parsed: ParsedShow) {
        val headerRow = ToolRenderers.rowPanel()
        headerRow.add(ToolRenderers.monoLabel(parsed.hash).apply {
            foreground = JBColor.namedColor("Link.activeForeground", UIUtil.getLabelForeground())
        })
        if (parsed.author.isNotEmpty()) headerRow.add(ToolRenderers.mutedLabel(parsed.author))
        panel.add(headerRow)
    }

    private fun addDate(panel: javax.swing.JPanel, date: String) {
        panel.add(ToolRenderers.mutedLabel(date).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
        })
    }

    private fun addMessage(panel: javax.swing.JPanel, message: String) {
        panel.add(JBLabel(message).apply {
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyTop(2)
        })
    }

    private fun addStats(panel: javax.swing.JPanel, summaryLine: String) {
        val stats = ToolRenderers.parseDiffStats(summaryLine)
        val statsRow = ToolRenderers.rowPanel()
        statsRow.border = JBUI.Borders.emptyTop(4)
        statsRow.add(ToolRenderers.mutedLabel(stats.files))
        if (stats.insertions.isNotEmpty()) statsRow.add(JBLabel("+${stats.insertions}").apply {
            foreground = ToolRenderers.ADD_COLOR
        })
        if (stats.deletions.isNotEmpty()) statsRow.add(JBLabel("-${stats.deletions}").apply {
            foreground = ToolRenderers.DEL_COLOR
        })
        panel.add(statsRow)
    }

    private fun addStatLines(panel: javax.swing.JPanel, statLines: List<String>) {
        for (stat in statLines) {
            val parts = stat.trim().split("|", limit = 2)
            if (parts.size == 2) {
                val row = ToolRenderers.rowPanel()
                row.add(
                    ToolRenderers.badgeLabel(
                        "M",
                        JBColor.namedColor("Link.activeForeground", UIUtil.getLabelForeground())
                    )
                )
                row.add(ToolRenderers.monoLabel(parts[0].trim()))
                row.add(ToolRenderers.mutedLabel(parts[1].trim()))
                panel.add(row)
            }
        }
    }
}
