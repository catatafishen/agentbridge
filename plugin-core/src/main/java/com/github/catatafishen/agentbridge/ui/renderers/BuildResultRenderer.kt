package com.github.catatafishen.agentbridge.ui.renderers

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Font
import javax.swing.JComponent

/**
 * Renders build results as a status card with success/fail indicator,
 * error/warning counts, duration, and colored error/warning message list.
 */
object BuildResultRenderer : ToolResultRenderer {

    private val COUNTS_PATTERN = Regex("""\((\d+) errors?,\s*(\d+) warnings?,\s*([\d.]+)s\)""")

    override fun render(output: String): JComponent? {
        val lines = output.trimEnd().lines()
        if (lines.isEmpty()) return null

        val firstLine = lines.first()
        val (statusIcon, label, color) = classifyStatus(firstLine) ?: return null

        val panel = ToolRenderers.listPanel()

        // Status header
        val headerRow = ToolRenderers.rowPanel()
        headerRow.add(JBLabel(label).apply {
            icon = statusIcon
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            foreground = color
        })

        val countsMatch = COUNTS_PATTERN.find(firstLine)
        if (countsMatch != null) {
            val errors = countsMatch.groupValues[1]
            val warnings = countsMatch.groupValues[2]
            val duration = countsMatch.groupValues[3]
            headerRow.add(ToolRenderers.mutedLabel("${duration}s"))
            if (errors != "0") headerRow.add(JBLabel("$errors errors").apply { foreground = ToolRenderers.FAIL_COLOR })
            if (warnings != "0") headerRow.add(JBLabel("$warnings warnings").apply {
                foreground = ToolRenderers.WARN_COLOR
            })
        }
        panel.add(headerRow)

        // Error/warning messages
        val messages = lines.drop(1).filter { it.isNotBlank() }
        for (msg in messages) {
            val trimmed = msg.trim()
            val msgColor = when {
                trimmed.startsWith("ERROR") -> ToolRenderers.FAIL_COLOR
                trimmed.startsWith("WARN") -> ToolRenderers.WARN_COLOR
                else -> UIUtil.getLabelForeground()
            }
            val msgRow = ToolRenderers.rowPanel()
            msgRow.border = JBUI.Borders.emptyLeft(8)
            msgRow.add(ToolRenderers.monoLabel(trimmed).apply { foreground = msgColor })
            panel.add(msgRow)
        }

        return panel
    }

    private fun classifyStatus(line: String): Triple<javax.swing.Icon, String, Color>? = when {
        line.startsWith("✓") -> Triple(ToolIcons.SUCCESS, "Build succeeded", ToolRenderers.SUCCESS_COLOR)
        line.startsWith("✗") -> Triple(ToolIcons.FAILURE, "Build failed", ToolRenderers.FAIL_COLOR)
        line.startsWith("Build aborted") -> Triple(ToolIcons.WARNING, "Build aborted", ToolRenderers.WARN_COLOR)
        else -> null
    }
}
