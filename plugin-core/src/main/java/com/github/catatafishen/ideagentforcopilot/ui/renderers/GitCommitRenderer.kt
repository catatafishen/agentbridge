package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Font
import javax.swing.JComponent

/**
 * Renders a git commit result as a card with commit metadata,
 * message, changed files with stats.
 */
internal object GitCommitRenderer : ToolResultRenderer {

    private val HEADER_PATTERN = Regex("""\[(\S+)\s+([a-f0-9]+)]\s+(.+)""")
    private val SUMMARY_PATTERN = Regex("""\d+ files? changed.*""")
    private val CREATE_PATTERN = Regex("""create mode \d+ (.+)""")
    private val DELETE_PATTERN = Regex("""delete mode \d+ (.+)""")
    private val RENAME_PATTERN = Regex("""rename (.+) => (.+) \((\d+)%\)""")

    private val ADD_COLOR = JBColor(Color(0x1A, 0x7F, 0x37), Color(0x3F, 0xB9, 0x50))
    private val DEL_COLOR = JBColor(Color(0xCF, 0x22, 0x2E), Color(0xF8, 0x53, 0x49))
    private val RENAME_COLOR = JBColor(Color(0x9A, 0x6D, 0x00), Color(0xD2, 0x9B, 0x22))

    override fun render(output: String): JComponent? {
        val lines = output.trimEnd().lines()
        if (lines.isEmpty()) return null
        val headerMatch = HEADER_PATTERN.find(lines[0]) ?: return null

        val branch = headerMatch.groupValues[1]
        val shortHash = headerMatch.groupValues[2]
        val message = headerMatch.groupValues[3]

        val panel = ToolRenderers.listPanel()

        // Header row: hash + branch
        val headerRow = ToolRenderers.rowPanel()
        headerRow.add(ToolRenderers.monoLabel(shortHash).apply {
            foreground = JBColor.namedColor("Link.activeForeground", UIUtil.getLabelForeground())
        })
        headerRow.add(ToolRenderers.mutedLabel(branch))
        panel.add(headerRow)

        // Commit message
        panel.add(JBLabel(message).apply {
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyTop(2)
        })

        // Parse file lines and summary
        var summaryLine = ""
        val fileLines = mutableListOf<String>()
        for (i in 1 until lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) continue
            if (line.matches(SUMMARY_PATTERN)) summaryLine = line
            else fileLines.add(line)
        }

        // Diff stats
        if (summaryLine.isNotEmpty()) {
            val stats = ToolRenderers.parseDiffStats(summaryLine)
            val statsRow = ToolRenderers.rowPanel()
            statsRow.border = JBUI.Borders.emptyTop(4)
            statsRow.add(ToolRenderers.mutedLabel(stats.files))
            if (stats.insertions.isNotEmpty()) {
                statsRow.add(JBLabel("+${stats.insertions}").apply { foreground = ADD_COLOR })
            }
            if (stats.deletions.isNotEmpty()) {
                statsRow.add(JBLabel("-${stats.deletions}").apply { foreground = DEL_COLOR })
            }
            panel.add(statsRow)
        }

        // File entries
        for (fileLine in fileLines) {
            panel.add(renderFileEntry(fileLine))
        }

        return panel
    }

    private fun renderFileEntry(line: String): JComponent {
        val row = ToolRenderers.rowPanel()
        val createMatch = CREATE_PATTERN.find(line)
        val deleteMatch = DELETE_PATTERN.find(line)
        val renameMatch = RENAME_PATTERN.find(line)

        when {
            createMatch != null -> {
                row.add(ToolRenderers.badgeLabel("A", ADD_COLOR))
                row.add(ToolRenderers.monoLabel(createMatch.groupValues[1].trim()))
            }
            deleteMatch != null -> {
                row.add(ToolRenderers.badgeLabel("D", DEL_COLOR))
                row.add(ToolRenderers.monoLabel(deleteMatch.groupValues[1].trim()))
            }
            renameMatch != null -> {
                row.add(ToolRenderers.badgeLabel("R", RENAME_COLOR))
                row.add(ToolRenderers.monoLabel("${renameMatch.groupValues[1].trim()} → ${renameMatch.groupValues[2].trim()}"))
            }
            else -> {
                row.add(ToolRenderers.badgeLabel("M", JBColor.namedColor("Link.activeForeground", UIUtil.getLabelForeground())))
                row.add(ToolRenderers.monoLabel(line.trim()))
            }
        }
        return row
    }
}
