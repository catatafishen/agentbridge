package com.github.catatafishen.agentbridge.ui.renderers

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Font
import javax.swing.JComponent

object TestResultRenderer : ToolResultRenderer {

    private val summaryPattern = Regex(
        """Test Results: (\d+) tests?, (\d+) passed, (\d+) failed, (\d+) errors?, (\d+) skipped"""
    )
    private val durationPattern = Regex("""\(([\d.]+)s\)""")

    private data class TestSummary(
        val total: Int,
        val passed: Int,
        val failed: Int,
        val errors: Int,
        val skipped: Int,
        val duration: String
    ) {
        val allPassed get() = failed == 0 && errors == 0
    }

    override fun render(output: String): JComponent? {
        val lines = output.trimEnd().lines()
        val summary = lines.firstOrNull()?.let(::parseSummary) ?: return null
        return ToolRenderers.listPanel().apply {
            add(createHeader(summary))
            add(createStats(summary))
            failureLines(lines).forEach { add(createFailureRow(it)) }
        }
    }

    private fun parseSummary(line: String): TestSummary? {
        val match = summaryPattern.find(line) ?: return null
        return TestSummary(
            total = match.groupValues[1].toInt(),
            passed = match.groupValues[2].toInt(),
            failed = match.groupValues[3].toInt(),
            errors = match.groupValues[4].toInt(),
            skipped = match.groupValues[5].toInt(),
            duration = durationPattern.find(line)?.groupValues?.get(1).orEmpty()
        )
    }

    private fun createHeader(summary: TestSummary) = ToolRenderers.rowPanel().apply {
        val color = if (summary.allPassed) ToolRenderers.SUCCESS_COLOR else ToolRenderers.FAIL_COLOR
        val icon = if (summary.allPassed) ToolIcons.SUCCESS else ToolIcons.FAILURE
        add(JBLabel("${summary.total} tests").apply {
            this.icon = icon
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            foreground = color
        })
        if (summary.duration.isNotEmpty()) add(ToolRenderers.mutedLabel("${summary.duration}s"))
    }

    private fun createStats(summary: TestSummary) = ToolRenderers.rowPanel().apply {
        addStat(summary.passed, "passed", ToolRenderers.SUCCESS_COLOR)
        addStat(summary.failed, "failed", ToolRenderers.FAIL_COLOR)
        addStat(summary.errors, "errors", ToolRenderers.FAIL_COLOR)
        addStat(summary.skipped, "skipped", ToolRenderers.MUTED_COLOR)
    }

    private fun javax.swing.JPanel.addStat(count: Int, label: String, color: java.awt.Color) {
        if (count > 0) add(JBLabel("$count $label").apply { foreground = color })
    }

    private fun failureLines(lines: List<String>): List<String> {
        val failureIndex = lines.indexOfFirst { it.trim() == "Failures:" }
        return if (failureIndex >= 0) {
            lines.drop(failureIndex + 1).filter { it.isNotBlank() }
        } else {
            lines.drop(1).filter { it.trim().startsWith("❌") }
        }
    }

    private fun createFailureRow(failure: String) = ToolRenderers.rowPanel().apply {
        border = JBUI.Borders.emptyLeft(8)
        val text = failure.trim().removePrefix("❌").trim()
        val separator = text.indexOf(':')
        if (separator > 0) {
            add(JBLabel(text.substring(0, separator).trim()).apply {
                font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
                foreground = ToolRenderers.FAIL_COLOR
            })
            add(JBLabel(text.substring(separator + 1).trim()).apply { foreground = ToolRenderers.WARN_COLOR })
        } else {
            add(JBLabel(text).apply { foreground = ToolRenderers.FAIL_COLOR })
        }
    }
}
