package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Font
import javax.swing.JComponent

/**
 * Renders test results as a status card with pass/fail counts,
 * duration, and a colored list of failures.
 */
internal object TestResultRenderer : ToolResultRenderer {

    private val SUMMARY_PATTERN = Regex(
        """Test Results:\s*(\d+)\s+tests?,\s*(\d+)\s+passed,\s*(\d+)\s+failed,\s*(\d+)\s+errors?,\s*(\d+)\s+skipped\s*\(([\d.]+)s\)"""
    )

    private val SUCCESS_COLOR = JBColor(Color(0x1A, 0x7F, 0x37), Color(0x3F, 0xB9, 0x50))
    private val FAIL_COLOR = JBColor(Color(0xCF, 0x22, 0x2E), Color(0xF8, 0x53, 0x49))
    private val WARN_COLOR = JBColor(Color(0x9A, 0x6D, 0x00), Color(0xD2, 0x9B, 0x22))
    private val SKIP_COLOR = JBColor(Color(0x6E, 0x77, 0x81), Color(0x8B, 0x94, 0x9E))

    override fun render(output: String): JComponent? {
        val lines = output.trimEnd().lines()
        if (lines.isEmpty()) return null

        val summary = SUMMARY_PATTERN.find(lines.first()) ?: return null
        val total = summary.groupValues[1].toInt()
        val passed = summary.groupValues[2].toInt()
        val failed = summary.groupValues[3].toInt()
        val errors = summary.groupValues[4].toInt()
        val skipped = summary.groupValues[5].toInt()
        val duration = summary.groupValues[6]

        val allPassed = failed == 0 && errors == 0
        val panel = ToolRenderers.listPanel()

        // Status header
        val headerRow = ToolRenderers.rowPanel()
        val statusColor = if (allPassed) SUCCESS_COLOR else FAIL_COLOR
        val icon = if (allPassed) "✓" else "✗"
        headerRow.add(JBLabel("$icon $total tests").apply {
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            foreground = statusColor
        })
        headerRow.add(ToolRenderers.mutedLabel("${duration}s"))
        panel.add(headerRow)

        // Stat badges
        val statsRow = ToolRenderers.rowPanel()
        if (passed > 0) statsRow.add(JBLabel("$passed passed").apply { foreground = SUCCESS_COLOR })
        if (failed > 0) statsRow.add(JBLabel("$failed failed").apply { foreground = FAIL_COLOR })
        if (errors > 0) statsRow.add(JBLabel("$errors errors").apply { foreground = FAIL_COLOR })
        if (skipped > 0) statsRow.add(JBLabel("$skipped skipped").apply { foreground = SKIP_COLOR })
        panel.add(statsRow)

        // Failures
        val failures = lines.drop(1).filter { it.trim().startsWith("❌") || it.trim().startsWith("\u274C") }
        for (failure in failures) {
            val trimmed = failure.trim().removePrefix("❌").removePrefix("\u274C").trim()
            val row = ToolRenderers.rowPanel()
            row.border = JBUI.Borders.emptyLeft(8)
            val colonIdx = trimmed.indexOf(':')
            if (colonIdx > 0) {
                row.add(JBLabel(trimmed.substring(0, colonIdx).trim()).apply {
                    font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
                    foreground = FAIL_COLOR
                })
                row.add(JBLabel(trimmed.substring(colonIdx + 1).trim()).apply {
                    foreground = WARN_COLOR
                })
            } else {
                row.add(JBLabel(trimmed).apply { foreground = FAIL_COLOR })
            }
            panel.add(row)
        }

        return panel
    }
}
