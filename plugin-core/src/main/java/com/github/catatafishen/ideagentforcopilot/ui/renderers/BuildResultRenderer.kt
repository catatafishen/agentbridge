package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.intellij.ui.JBColor
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
internal object BuildResultRenderer : ToolResultRenderer {

    private val COUNTS_PATTERN = Regex("""\((\d+) errors?,\s*(\d+) warnings?,\s*([\d.]+)s\)""")
    private val SUCCESS_COLOR = JBColor(Color(0x1A, 0x7F, 0x37), Color(0x3F, 0xB9, 0x50))
    private val FAIL_COLOR = JBColor(Color(0xCF, 0x22, 0x2E), Color(0xF8, 0x53, 0x49))
    private val WARN_COLOR = JBColor(Color(0x9A, 0x6D, 0x00), Color(0xD2, 0x9B, 0x22))

    override fun render(output: String): JComponent? {
        val lines = output.trimEnd().lines()
        if (lines.isEmpty()) return null

        val firstLine = lines.first()
        val (icon, label, color) = classifyStatus(firstLine) ?: return null

        val panel = ToolRenderers.listPanel()

        // Status header
        val headerRow = ToolRenderers.rowPanel()
        headerRow.add(JBLabel("$icon $label").apply {
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            foreground = color
        })

        val countsMatch = COUNTS_PATTERN.find(firstLine)
        if (countsMatch != null) {
            val errors = countsMatch.groupValues[1]
            val warnings = countsMatch.groupValues[2]
            val duration = countsMatch.groupValues[3]
            headerRow.add(ToolRenderers.mutedLabel("${duration}s"))
            if (errors != "0") headerRow.add(JBLabel("$errors errors").apply { foreground = FAIL_COLOR })
            if (warnings != "0") headerRow.add(JBLabel("$warnings warnings").apply { foreground = WARN_COLOR })
        }
        panel.add(headerRow)

        // Error/warning messages
        val messages = lines.drop(1).filter { it.isNotBlank() }
        for (msg in messages) {
            val trimmed = msg.trim()
            val msgColor = when {
                trimmed.startsWith("ERROR") -> FAIL_COLOR
                trimmed.startsWith("WARN") -> WARN_COLOR
                else -> UIUtil.getLabelForeground()
            }
            val msgRow = ToolRenderers.rowPanel()
            msgRow.border = JBUI.Borders.emptyLeft(8)
            msgRow.add(ToolRenderers.monoLabel(trimmed).apply { foreground = msgColor })
            panel.add(msgRow)
        }

        return panel
    }

    private fun classifyStatus(line: String): Triple<String, String, Color>? = when {
        line.startsWith("✓") -> Triple("✓", "Build succeeded", SUCCESS_COLOR)
        line.startsWith("✗") -> Triple("✗", "Build failed", FAIL_COLOR)
        line.startsWith("Build aborted") -> Triple("⚠", "Build aborted", WARN_COLOR)
        else -> null
    }
}
