package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Font
import javax.swing.JComponent

/**
 * Renders list_tests output as grouped test classes with method counts.
 */
internal object ListTestsRenderer : ToolResultRenderer {

    private val ENTRY_PATTERN = Regex("""^(\S+)\.(\S+)\s+\((.+?):(\d+)\)$""")
    private val COUNT_HEADER = Regex("""^(\d+)\s+tests?:""")

    override fun render(output: String): JComponent? {
        val lines = output.trimEnd().lines()
        if (lines.isEmpty()) return null

        if (lines.first() == "No tests found") {
            val panel = ToolRenderers.listPanel()
            panel.add(ToolRenderers.mutedLabel("∅ No tests found").apply {
                alignmentX = JComponent.LEFT_ALIGNMENT
            })
            return panel
        }

        val entries = lines.mapNotNull { ENTRY_PATTERN.find(it.trim()) }
        if (entries.isEmpty()) return null

        val countMatch = COUNT_HEADER.find(lines.first())
        val count = countMatch?.groupValues?.get(1)?.toIntOrNull() ?: entries.size

        data class TestEntry(val className: String, val method: String, val line: String)

        val tests = entries.map { TestEntry(it.groupValues[1], it.groupValues[2], it.groupValues[4]) }
        val grouped = tests.groupBy { it.className }

        val panel = ToolRenderers.listPanel()
        panel.add(ToolRenderers.headerPanel("✓", count, "tests"))

        for ((className, methods) in grouped) {
            val section = ToolRenderers.listPanel().apply {
                border = JBUI.Borders.emptyTop(4)
                alignmentX = JComponent.LEFT_ALIGNMENT
            }
            val classHeader = ToolRenderers.rowPanel()
            classHeader.add(JBLabel(className).apply {
                font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            })
            classHeader.add(ToolRenderers.mutedLabel("${methods.size}"))
            section.add(classHeader)

            for (t in methods) {
                val row = ToolRenderers.rowPanel()
                row.border = JBUI.Borders.emptyLeft(8)
                row.add(ToolRenderers.mutedLabel(":${t.line}"))
                row.add(JBLabel(t.method))
                section.add(row)
            }
            panel.add(section)
        }

        return panel
    }
}
