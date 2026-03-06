package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import javax.swing.JComponent

/**
 * Renders search results (text search, symbol search, find references) as
 * grouped file cards with line numbers and match highlights.
 */
internal object SearchResultRenderer : ToolResultRenderer {

    private val LINE_REF_PATTERN = Regex("""^(.+?):(\d+):\s+(.+)$""")
    private val LOCATION_PATTERN = Regex("""^(.+?):(\d+)\s+\[([^]]+)]\s+(.+)$""")
    private val COUNT_HEADER = Regex("""^(\d+)\s+(matches|results?|references?|symbols?)\b""", RegexOption.IGNORE_CASE)
    private val NO_MATCHES = Regex("""^No\s+(matches|results|references|symbols)\s+found""", RegexOption.IGNORE_CASE)

    override fun render(output: String): JComponent? {
        val lines = output.trimEnd().lines()
        if (lines.isEmpty()) return null

        if (NO_MATCHES.containsMatchIn(lines.first())) {
            val panel = ToolRenderers.listPanel()
            panel.add(ToolRenderers.mutedLabel("∅ ${lines.first()}").apply {
                alignmentX = JComponent.LEFT_ALIGNMENT
            })
            return panel
        }

        val results = mutableListOf<SearchResult>()
        var headerLine: String? = null

        for (line in lines) {
            val trimmed = line.trim()
            val locMatch = LOCATION_PATTERN.matchEntire(trimmed)
            val lineRefMatch = LINE_REF_PATTERN.matchEntire(trimmed)
            when {
                locMatch != null -> results.add(
                    SearchResult(
                        locMatch.groupValues[1],
                        locMatch.groupValues[2].toIntOrNull() ?: 0,
                        locMatch.groupValues[3],
                        locMatch.groupValues[4]
                    )
                )

                lineRefMatch != null -> results.add(
                    SearchResult(
                        lineRefMatch.groupValues[1],
                        lineRefMatch.groupValues[2].toIntOrNull() ?: 0,
                        "",
                        lineRefMatch.groupValues[3]
                    )
                )

                COUNT_HEADER.containsMatchIn(line) || (headerLine == null && line.isNotBlank()) -> headerLine = line
            }
        }

        if (results.isEmpty()) return null

        val panel = ToolRenderers.listPanel()
        addHeader(panel, headerLine, results.size)
        addGroupedResults(panel, results)
        return panel
    }

    private data class SearchResult(val path: String, val line: Int, val badge: String, val content: String)

    private fun addHeader(panel: javax.swing.JPanel, headerLine: String?, count: Int) {
        val match = headerLine?.let { COUNT_HEADER.find(it) }
        val displayCount = match?.groupValues?.get(1)?.toIntOrNull() ?: count
        val kind = match?.groupValues?.get(2) ?: "results"
        panel.add(ToolRenderers.headerPanel(ToolIcons.SEARCH, displayCount, kind))
    }

    private fun addGroupedResults(panel: javax.swing.JPanel, results: List<SearchResult>) {
        val grouped = results.groupBy { it.path }
        for ((path, fileResults) in grouped) {
            val section = ToolRenderers.listPanel().apply {
                border = JBUI.Borders.emptyTop(4)
                alignmentX = JComponent.LEFT_ALIGNMENT
            }
            val fileName = path.substringAfterLast('/')
            val fileHeader = ToolRenderers.rowPanel()
            fileHeader.add(ToolRenderers.fileLink(fileName, path))
            fileHeader.add(ToolRenderers.mutedLabel("${fileResults.size}"))
            section.add(fileHeader)

            for (r in fileResults) {
                val row = ToolRenderers.rowPanel()
                row.border = JBUI.Borders.emptyLeft(8)
                row.add(ToolRenderers.fileLink(":${r.line}", r.path, r.line))
                if (r.badge.isNotEmpty()) {
                    row.add(
                        ToolRenderers.badgeLabel(
                            r.badge,
                            UIUtil.getLabelForeground()
                        )
                    )
                }
                row.add(JBLabel(r.content))
                section.add(row)
            }
            panel.add(section)
        }
    }
}
