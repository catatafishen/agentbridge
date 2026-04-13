package com.github.catatafishen.agentbridge.ui.renderers

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Font
import javax.swing.JComponent

/**
 * Renders a git commit result as a card with commit metadata,
 * message, changed files with stats.
 */
object GitCommitRenderer : ToolResultRenderer {

    val HEADER_PATTERN = Regex("""\[(\S+)\s+([a-f0-9]+)]\s+(.+)""")
    val SUMMARY_PATTERN = Regex("""\d+ files? changed.*""")
    val CREATE_PATTERN = Regex("""create mode \d+ (.+)""")
    val DELETE_PATTERN = Regex("""delete mode \d+ (.+)""")
    val RENAME_PATTERN = Regex("""rename (.+) => (.+) \((\d+)%\)""")

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
                statsRow.add(JBLabel("+${stats.insertions}").apply { foreground = ToolRenderers.ADD_COLOR })
            }
            if (stats.deletions.isNotEmpty()) {
                statsRow.add(JBLabel("-${stats.deletions}").apply { foreground = ToolRenderers.DEL_COLOR })
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
                val path = createMatch.groupValues[1].trim()
                row.add(ToolRenderers.badgeLabel("A", ToolRenderers.ADD_COLOR))
                row.add(ToolRenderers.fileLink(path, path))
            }

            deleteMatch != null -> {
                val path = deleteMatch.groupValues[1].trim()
                row.add(ToolRenderers.badgeLabel("D", ToolRenderers.DEL_COLOR))
                row.add(ToolRenderers.fileLink(path, path))
            }

            renameMatch != null -> {
                val from = renameMatch.groupValues[1].trim()
                val to = renameMatch.groupValues[2].trim()
                row.add(ToolRenderers.badgeLabel("R", ToolRenderers.MOD_COLOR))
                row.add(ToolRenderers.monoLabel("$from →"))
                row.add(ToolRenderers.fileLink(to, to))
            }

            else -> {
                val path = line.trim()
                row.add(
                    ToolRenderers.badgeLabel(
                        "M",
                        JBColor.namedColor("Link.activeForeground", UIUtil.getLabelForeground())
                    )
                )
                row.add(ToolRenderers.fileLink(path, path))
            }
        }
        return row
    }
}
