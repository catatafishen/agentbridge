package com.github.catatafishen.agentbridge.ui.renderers

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import javax.swing.JComponent

/**
 * Renders git log output as a structured commit list.
 * Supports oneline, short, and medium formats.
 */
object GitLogRenderer : ToolResultRenderer {

    private const val COMMIT_PREFIX = "commit "
    private val SHORT_PATTERN = Regex("""^[a-f0-9]{7,40}\s+.+\(.+,\s*.+\)$""")
    private val ONELINE_PATTERN = Regex("""^[a-f0-9]{7,40}\s+.+""")
    private val MEDIUM_ENTRY = Regex("""^([a-f0-9]{7,40})\s+(.+)\((.+),\s*(.+)\)$""")

    override fun render(output: String): JComponent? {
        val lines = output.trimEnd().lines()
        if (lines.isEmpty()) return null

        val firstLine = lines.first().trim()
        return when {
            firstLine.startsWith(COMMIT_PREFIX) -> renderMediumLog(lines)
            SHORT_PATTERN.matches(firstLine) -> renderShortLog(lines)
            ONELINE_PATTERN.matches(firstLine) -> renderOnelineLog(lines)
            else -> null
        }
    }

    private fun renderOnelineLog(lines: List<String>): JComponent {
        val panel = ToolRenderers.listPanel()
        var count = 0
        for (line in lines) {
            if (line.isBlank()) continue
            val spaceIdx = line.indexOf(' ')
            if (spaceIdx < 0) continue
            if (count >= ToolRenderers.MAX_LIST_ENTRIES) {
                val remaining = lines.count { it.isNotBlank() && it.indexOf(' ') >= 0 } - count
                if (remaining > 0) ToolRenderers.addTruncationIndicator(panel, remaining, "commits")
                break
            }
            val hash = line.substring(0, spaceIdx).trim()
            val message = line.substring(spaceIdx + 1).trim()
            addEntry(panel, hash, message, null)
            count++
        }
        return panel
    }

    private fun renderShortLog(lines: List<String>): JComponent {
        val panel = ToolRenderers.listPanel()
        var count = 0
        val nonBlankLines = lines.filter { it.isNotBlank() }
        for (line in nonBlankLines) {
            if (count >= ToolRenderers.MAX_LIST_ENTRIES) {
                ToolRenderers.addTruncationIndicator(panel, nonBlankLines.size - count, "commits")
                break
            }
            val match = MEDIUM_ENTRY.find(line.trim())
            if (match != null) {
                val hash = match.groupValues[1]
                val message = match.groupValues[2].trim()
                val meta = "${match.groupValues[3].trim()}, ${match.groupValues[4].trim()}"
                addEntry(panel, hash, message, meta)
            } else {
                panel.add(JBLabel(line.trim()).apply { alignmentX = JComponent.LEFT_ALIGNMENT })
            }
            count++
        }
        return panel
    }

    private fun renderMediumLog(lines: List<String>): JComponent {
        data class Entry(
            var hash: String = "",
            var author: String = "",
            var date: String = "",
            val message: StringBuilder = StringBuilder()
        )

        val panel = ToolRenderers.listPanel()
        var current = Entry()
        var inMessage = false
        var count = 0

        fun flush() {
            if (current.hash.isEmpty()) return
            if (count >= ToolRenderers.MAX_LIST_ENTRIES) return
            val shortHash = if (current.hash.length > 7) current.hash.substring(0, 7) else current.hash
            val msg = current.message.toString().trim().lines().firstOrNull() ?: ""
            val meta = listOf(current.author, current.date).filter { it.isNotEmpty() }.joinToString(", ")
            addEntry(panel, shortHash, msg, meta.ifEmpty { null })
            count++
            current = Entry()
            inMessage = false
        }

        var totalCommits = 0
        for (line in lines) {
            if (line.startsWith(COMMIT_PREFIX)) totalCommits++
        }

        for (line in lines) {
            when {
                line.startsWith(COMMIT_PREFIX) -> {
                    flush(); current.hash = line.removePrefix(COMMIT_PREFIX).trim()
                }

                line.startsWith("Author:") -> current.author =
                    line.removePrefix("Author:").trim().replace(Regex("""<.*>"""), "").trim()

                line.startsWith("Date:") -> {
                    current.date = line.removePrefix("Date:").trim(); inMessage = true
                }

                inMessage -> current.message.appendLine(line.trimStart())
            }
        }
        flush()

        if (totalCommits > count) {
            ToolRenderers.addTruncationIndicator(panel, totalCommits - count, "commits")
        }
        return panel
    }

    private fun addEntry(panel: javax.swing.JPanel, hash: String, message: String, meta: String?) {
        val row = ToolRenderers.rowPanel()
        row.add(ToolRenderers.monoLabel(hash).apply {
            foreground = JBColor.namedColor("Link.activeForeground", UIUtil.getLabelForeground())
        })
        if (message.isNotEmpty()) row.add(JBLabel(message))
        if (!meta.isNullOrEmpty()) row.add(ToolRenderers.mutedLabel(meta))
        panel.add(row)
    }
}
