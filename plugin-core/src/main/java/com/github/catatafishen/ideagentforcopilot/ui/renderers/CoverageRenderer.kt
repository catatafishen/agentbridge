package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import java.awt.Font
import javax.swing.JComponent

/**
 * Renderer for get_coverage output.
 * Input: "ClassName: 85.2% covered (42 / 49 lines)" per line.
 */
object CoverageRenderer : ToolResultRenderer {

    private val COVERAGE_LINE = Regex("""^(.+?):\s+([\d.]+)%\s+covered\s+\((\d+)\s*/\s*(\d+)\s+lines\)""")

    override fun render(output: String): JComponent? {
        val lines = output.lines()
        val entries = lines.mapNotNull { COVERAGE_LINE.find(it.trim()) }
        if (entries.isEmpty()) return null

        val totalCovered = entries.sumOf { it.groupValues[3].toInt() }
        val totalLines = entries.sumOf { it.groupValues[4].toInt() }
        val overallPct = if (totalLines > 0) totalCovered * 100.0 / totalLines else 0.0

        val panel = ToolRenderers.listPanel()
        panel.add(
            ToolRenderers.headerPanel(
                ToolIcons.COVERAGE, entries.size,
                "${if (entries.size == 1) "class" else "classes"} — ${String.format("%.1f", overallPct)}% overall"
            )
        )

        for (m in entries) {
            val name = m.groupValues[1]
            val pct = m.groupValues[2].toDouble()
            val covered = m.groupValues[3]
            val total = m.groupValues[4]

            val pctColor = when {
                pct >= 80 -> ToolRenderers.SUCCESS_COLOR
                pct >= 50 -> ToolRenderers.WARN_COLOR
                else -> ToolRenderers.FAIL_COLOR
            }

            val row = ToolRenderers.rowPanel()
            row.add(JBLabel(name).apply {
                font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            })
            row.add(JBLabel("${m.groupValues[2]}%").apply {
                foreground = pctColor
                font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            })
            row.add(ToolRenderers.mutedLabel("$covered / $total lines"))
            panel.add(row)
        }

        return panel
    }
}
