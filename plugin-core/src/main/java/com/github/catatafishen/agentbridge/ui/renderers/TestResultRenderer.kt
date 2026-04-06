package com.github.catatafishen.agentbridge.ui.renderers

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Font
import javax.swing.JComponent

/**
 * Renders test results as a status card with pass/fail counts,
 * duration, and a colored list of failures.
 */
object TestResultRenderer : ToolResultRenderer {

    private val SUMMARY_PATTERN = Regex(
        """Test Results:\s*(\d+)\s+tests?,\s*(\d+)\s+passed,\s*(\d+)\s+failed,\s*(\d+)\s+errors?,\s*(\d+)\s+skipped\s*\(([\d.]+)s\)"""
    )

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
        val statusColor = if (allPassed) ToolRenderers.SUCCESS_COLOR else ToolRenderers.FAIL_COLOR
        val statusIcon = if (allPassed) ToolIcons.SUCCESS else ToolIcons.FAILURE
        headerRow.add(JBLabel("$total tests").apply {
            icon = statusIcon
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            foreground = statusColor
        })
        headerRow.add(ToolRenderers.mutedLabel("${duration}s"))
        panel.add(headerRow)

        // Stat badges
        val statsRow = ToolRenderers.rowPanel()
        if (passed > 0) statsRow.add(JBLabel("$passed passed").apply { foreground = ToolRenderers.SUCCESS_COLOR })
        if (failed > 0) statsRow.add(JBLabel("$failed failed").apply { foreground = ToolRenderers.FAIL_COLOR })
        if (errors > 0) statsRow.add(JBLabel("$errors errors").apply { foreground = ToolRenderers.FAIL_COLOR })
        if (skipped > 0) statsRow.add(JBLabel("$skipped skipped").apply { foreground = ToolRenderers.MUTED_COLOR })
        panel.add(statsRow)

        // Failures — look for "Failures:" section or legacy ❌-prefixed lines
        val failIdx = lines.indexOfFirst { it.trim() == "Failures:" }
        val failures = if (failIdx >= 0) {
            lines.subList(failIdx + 1, lines.size).filter { it.trim().isNotEmpty() }
        } else {
            lines.drop(1).filter { it.trim().startsWith("❌") || it.trim().startsWith("\u274C") }
        }
        for (failure in failures) {
            val trimmed = failure.trim().removePrefix("❌").removePrefix("\u274C").trim()
            val row = ToolRenderers.rowPanel()
            row.border = JBUI.Borders.emptyLeft(8)
            val colonIdx = trimmed.indexOf(':')
            if (colonIdx > 0) {
                row.add(JBLabel(trimmed.substring(0, colonIdx).trim()).apply {
                    font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
                    foreground = ToolRenderers.FAIL_COLOR
                })
                row.add(JBLabel(trimmed.substring(colonIdx + 1).trim()).apply {
                    foreground = ToolRenderers.WARN_COLOR
                })
            } else {
                row.add(JBLabel(trimmed).apply { foreground = ToolRenderers.FAIL_COLOR })
            }
            panel.add(row)
        }

        return panel
    }
}
