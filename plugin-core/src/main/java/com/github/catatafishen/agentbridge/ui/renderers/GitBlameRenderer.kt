package com.github.catatafishen.agentbridge.ui.renderers

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Font
import javax.swing.JComponent

/**
 * Renders git blame output as a compact listing with author coloring,
 * line numbers, and code content.
 */
object GitBlameRenderer : ToolResultRenderer {

    val BLAME_LINE = Regex(
        """^([a-f0-9]+)\s+\((.+?)\s+(\d{4}-\d{2}-\d{2})\s+[\d:]+\s+[+-]\d{4}\s+(\d+)\)\s?(.*)$"""
    )

    private val PALETTE = listOf(
        ToolRenderers.CLASS_COLOR,
        ToolRenderers.SUCCESS_COLOR,
        ToolRenderers.WARN_COLOR,
        ToolRenderers.FAIL_COLOR,
        ToolRenderers.FIELD_COLOR,
        JBColor(Color(0x16, 0xA0, 0x85), Color(0x39, 0xD3, 0x53)),
    )

    override fun render(output: String): JComponent? {
        val lines = output.trimEnd().lines()
        val entries = lines.mapNotNull { parseBlameLine(it) }
        if (entries.size < 2) return null

        val authors = entries.map { it.author }.distinct()
        val panel = ToolRenderers.listPanel()

        val headerRow = ToolRenderers.rowPanel()
        headerRow.add(ToolRenderers.mutedLabel("${entries.size} lines"))
        headerRow.add(ToolRenderers.mutedLabel("${authors.size} authors"))
        panel.add(headerRow)

        panel.add(ToolRenderers.codeBlock(buildBlameText(entries)))

        if (authors.size > 1) {
            panel.add(buildAuthorLegend(authors))
        }

        return panel
    }

    private fun buildBlameText(entries: List<BlameEntry>): String {
        val sb = StringBuilder()
        var prevAuthor = ""
        for (entry in entries) {
            val authorTag =
                if (entry.author != prevAuthor) abbreviateAuthor(entry.author).padEnd(14) else " ".repeat(14)
            sb.appendLine("$authorTag ${entry.date} ${entry.lineNum.padStart(4)}: ${entry.content}")
            prevAuthor = entry.author
        }
        return sb.toString().trimEnd()
    }

    private fun buildAuthorLegend(authors: List<String>): JComponent {
        val authorColors = authors.withIndex().associate { (i, a) -> a to PALETTE[i % PALETTE.size] }
        val legendRow = ToolRenderers.rowPanel()
        legendRow.border = JBUI.Borders.emptyTop(4)
        for (author in authors) {
            legendRow.add(JBLabel(abbreviateAuthor(author)).apply {
                font = UIUtil.getLabelFont().deriveFont(Font.BOLD, UIUtil.getLabelFont().size2D - 1f)
                foreground = authorColors[author] ?: UIUtil.getLabelForeground()
            })
        }
        return legendRow
    }

    data class BlameEntry(
        val hash: String,
        val author: String,
        val date: String,
        val lineNum: String,
        val content: String
    )

    fun parseBlameLine(line: String): BlameEntry? {
        val match = BLAME_LINE.matchEntire(line) ?: return null
        return BlameEntry(
            match.groupValues[1],
            match.groupValues[2].trim(),
            match.groupValues[3],
            match.groupValues[4],
            match.groupValues[5]
        )
    }

    fun abbreviateAuthor(name: String): String =
        if (name.length <= 12) name else name.take(10) + "…"
}
