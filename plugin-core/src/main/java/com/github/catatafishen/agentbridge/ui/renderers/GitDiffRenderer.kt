package com.github.catatafishen.agentbridge.ui.renderers

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import javax.swing.JComponent

/**
 * Renders unified diff output with syntax-highlighted additions/deletions,
 * file headers, and hunk markers. Also handles stat-only output.
 */
object GitDiffRenderer : ToolResultRenderer {

    val STAT_SUMMARY = Regex("""\s*\d+ files? changed.*""")
    val STAT_FILE = Regex("""^\s*(.+?)\s*\|\s*(\d+)\s*([+\-]*)\s*$""")
    val DIFF_GIT = Regex("""^diff --git a/(.+) b/(.+)""")

    override fun render(output: String): JComponent? {
        val lines = output.trimEnd().lines()
        if (lines.isEmpty()) return null

        val lastNonBlank = lines.lastOrNull { it.isNotBlank() } ?: return null
        return if (lastNonBlank.matches(STAT_SUMMARY)) renderStatOutput(lines)
        else renderUnifiedDiff(lines)
    }

    private fun renderStatOutput(lines: List<String>): JComponent {
        val panel = ToolRenderers.listPanel()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.matches(STAT_SUMMARY)) {
                panel.add(buildSummaryRow(trimmed))
            } else {
                panel.add(buildStatFileRow(trimmed))
            }
        }
        return panel
    }

    private fun buildSummaryRow(trimmed: String): JComponent {
        val stats = ToolRenderers.parseDiffStats(trimmed)
        val row = ToolRenderers.rowPanel()
        row.add(ToolRenderers.mutedLabel(stats.files))
        if (stats.insertions.isNotEmpty()) row.add(JBLabel("+${stats.insertions}").apply {
            foreground = ToolRenderers.ADD_COLOR
        })
        if (stats.deletions.isNotEmpty()) row.add(JBLabel("-${stats.deletions}").apply {
            foreground = ToolRenderers.DEL_COLOR
        })
        return row
    }

    private fun buildStatFileRow(trimmed: String): JComponent {
        val match = STAT_FILE.find(trimmed)
        if (match != null) {
            val row = ToolRenderers.rowPanel()
            row.add(ToolRenderers.fileLink(match.groupValues[1].trim(), match.groupValues[1].trim()))
            row.add(ToolRenderers.mutedLabel(match.groupValues[2]))
            val bar = match.groupValues[3]
            if (bar.isNotEmpty()) row.add(renderStatBar(bar))
            return row
        }
        return ToolRenderers.monoLabel(trimmed).apply { alignmentX = JComponent.LEFT_ALIGNMENT }
    }

    private fun renderStatBar(bar: String): JBLabel {
        val adds = bar.count { it == '+' }
        val dels = bar.count { it == '-' }
        return JBLabel("+$adds -$dels").apply {
            foreground = if (dels > adds) ToolRenderers.DEL_COLOR else ToolRenderers.ADD_COLOR
        }
    }

    private fun renderUnifiedDiff(lines: List<String>): JComponent? {
        val panel = ToolRenderers.listPanel()
        val currentFileLines = mutableListOf<String>()
        var currentFile = ""

        fun flushFile() {
            if (currentFile.isNotEmpty() && currentFileLines.isNotEmpty()) {
                addFileSection(panel, currentFile, currentFileLines.toList())
                currentFileLines.clear()
            }
        }

        for (line in lines) {
            val trimmedLine = line.trim()
            val diffMatch = DIFF_GIT.find(trimmedLine)
            if (diffMatch != null) {
                flushFile()
                currentFile = diffMatch.groupValues[2]
            } else if (currentFile.isNotEmpty() && !isMetaLine(trimmedLine)) {
                currentFileLines.add(line)
            }
        }
        flushFile()
        // Return null if no diff sections were found — lets callers fall back to raw text display
        return if (panel.componentCount > 0) panel else null
    }

    private fun addFileSection(panel: javax.swing.JPanel, filePath: String, lines: List<String>) {
        val section = ToolRenderers.listPanel().apply {
            border = JBUI.Borders.emptyTop(6)
            alignmentX = JComponent.LEFT_ALIGNMENT
        }
        section.add(ToolRenderers.fileLink(filePath, filePath).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
        })
        val diffText = lines.joinToString("\n")
        section.add(ToolRenderers.codeOrScratchPanel(diffText, "diff"))
        panel.add(section)
    }

    fun isMetaLine(line: String): Boolean =
        line.startsWith("---") || line.startsWith("+++") ||
            line.startsWith("index ") || line.startsWith("new file") ||
            line.startsWith("deleted file") || line.startsWith("similarity") ||
            line.startsWith("rename") || line.startsWith("old mode") || line.startsWith("new mode")
}
