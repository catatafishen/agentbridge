package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Font
import javax.swing.JComponent

/**
 * Renders inspection, compilation error, and highlight results as grouped
 * file cards with severity badges.
 */
internal object InspectionResultRenderer : ToolResultRenderer {

    private val FINDING_PATTERN = Regex("""^(.+?):(\d+)\s+\[([^]]+)]\s+(.+)$""")
    private val SUMMARY_PATTERN = Regex("""Found\s+(\d+)\s+(?:total\s+)?(?:problems?|compilation errors?)""")
    private val SUCCESS_PATTERN = Regex("""^[✅✓]\s+No\s+(?:compilation errors|inspection problems)""")

    private val ERROR_COLOR = JBColor(Color(0xCF, 0x22, 0x2E), Color(0xF8, 0x53, 0x49))
    private val WARN_COLOR = JBColor(Color(0x9A, 0x6D, 0x00), Color(0xD2, 0x9B, 0x22))
    private val WEAK_COLOR = JBColor(Color(0x6E, 0x77, 0x81), Color(0x8B, 0x94, 0x9E))
    private val SUCCESS_COLOR = JBColor(Color(0x1A, 0x7F, 0x37), Color(0x3F, 0xB9, 0x50))

    override fun render(output: String): JComponent? {
        val lines = output.trimEnd().lines()
        if (lines.isEmpty()) return null

        if (SUCCESS_PATTERN.containsMatchIn(lines.first())) {
            return renderSuccess(lines.first())
        }

        val findings = mutableListOf<Finding>()
        val headerLines = mutableListOf<String>()
        for (line in lines) {
            val match = FINDING_PATTERN.matchEntire(line.trim())
            if (match != null) findings.add(parseFinding(match))
            else if (line.isNotBlank()) headerLines.add(line)
        }
        if (findings.isEmpty()) return null

        val panel = ToolRenderers.listPanel()
        addSummaryHeader(panel, headerLines, findings.size)
        addGroupedFindings(panel, findings)
        return panel
    }

    private data class Finding(
        val path: String,
        val line: Int,
        val severity: String,
        val toolId: String,
        val description: String
    )

    private fun parseFinding(match: MatchResult): Finding {
        val bracketContent = match.groupValues[3]
        val slashIdx = bracketContent.indexOf('/')
        return if (slashIdx >= 0) {
            Finding(
                match.groupValues[1],
                match.groupValues[2].toIntOrNull() ?: 0,
                bracketContent.substring(0, slashIdx).trim(),
                bracketContent.substring(slashIdx + 1).trim(),
                match.groupValues[4]
            )
        } else {
            Finding(
                match.groupValues[1], match.groupValues[2].toIntOrNull() ?: 0,
                bracketContent.trim(), "", match.groupValues[4]
            )
        }
    }

    private fun renderSuccess(line: String): JComponent {
        val panel = ToolRenderers.listPanel()
        panel.add(JBLabel("✓ ${line.removePrefix("✅").removePrefix("✓").trim()}").apply {
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            foreground = SUCCESS_COLOR
            alignmentX = JComponent.LEFT_ALIGNMENT
        })
        return panel
    }

    private fun addSummaryHeader(panel: javax.swing.JPanel, headerLines: List<String>, totalFindings: Int) {
        val summaryMatch = headerLines.firstNotNullOfOrNull { SUMMARY_PATTERN.find(it) }
        val count = summaryMatch?.groupValues?.get(1)?.toIntOrNull() ?: totalFindings
        val hasErrors = headerLines.any { "error" in it.lowercase() }
        val color = if (hasErrors) ERROR_COLOR else WARN_COLOR
        val icon = if (hasErrors) "✗" else "⚠"
        val label = if (hasErrors) "problems" else "findings"

        val headerRow = ToolRenderers.rowPanel()
        headerRow.add(JBLabel("$icon $count $label").apply {
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            foreground = color
        })
        panel.add(headerRow)
    }

    private fun addGroupedFindings(panel: javax.swing.JPanel, findings: List<Finding>) {
        val grouped = findings.groupBy { it.path }
        for ((path, fileFindings) in grouped) {
            val section = ToolRenderers.listPanel().apply {
                border = JBUI.Borders.emptyTop(4)
                alignmentX = JComponent.LEFT_ALIGNMENT
            }
            val fileName = path.substringAfterLast('/')
            val fileHeader = ToolRenderers.rowPanel()
            fileHeader.add(ToolRenderers.monoLabel(fileName).apply {
                font = font.deriveFont(Font.BOLD)
                toolTipText = path
            })
            fileHeader.add(ToolRenderers.mutedLabel("${fileFindings.size}"))
            section.add(fileHeader)

            for (f in fileFindings) {
                val row = ToolRenderers.rowPanel()
                row.border = JBUI.Borders.emptyLeft(8)
                row.add(ToolRenderers.badgeLabel(abbreviateSeverity(f.severity), severityColor(f.severity)))
                row.add(ToolRenderers.mutedLabel(":${f.line}"))
                if (f.toolId.isNotEmpty()) row.add(ToolRenderers.mutedLabel(f.toolId))
                row.add(JBLabel(f.description))
                section.add(row)
            }
            panel.add(section)
        }
    }

    private fun severityColor(severity: String): Color = when (severity.uppercase()) {
        "ERROR", "GENERIC_SERVER_ERROR_OR_WARNING" -> ERROR_COLOR
        "WARNING" -> WARN_COLOR
        else -> WEAK_COLOR
    }

    private fun abbreviateSeverity(severity: String): String = when (severity.uppercase()) {
        "ERROR", "GENERIC_SERVER_ERROR_OR_WARNING" -> "E"
        "WARNING" -> "W"
        "WEAK_WARNING", "LIKE_UNUSED_SYMBOL" -> "w"
        "INFORMATION", "INFO", "TEXT_ATTRIBUTES" -> "I"
        else -> severity.take(1).uppercase()
    }
}
