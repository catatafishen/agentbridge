package com.github.catatafishen.agentbridge.ui.renderers

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Font
import javax.swing.JComponent

/**
 * Renders list_tests output as grouped test classes with method counts.
 */
object ListTestsRenderer : ToolResultRenderer {

    private val ENTRY_PATTERN = Regex("""^(\S+)\.(\S+)\s+\((.+?):(\d+)\)$""")
    private val COUNT_HEADER = Regex("""^(\d+)\s+tests?:""")

    private val CLASS_ENTRY_PATTERN = Regex("""^(\S+)$""")

    override fun render(output: String): JComponent? {
        data class TestEntry(val className: String, val method: String, val line: String)

        val lines = output.trimEnd().lines()
        if (lines.isEmpty()) return null

        if (lines.first() == "No tests found") {
            val panel = ToolRenderers.listPanel()
            panel.add(ToolRenderers.mutedLabel("∅ No tests found").apply {
                alignmentX = JComponent.LEFT_ALIGNMENT
            })
            return panel
        }

        val entries = lines.mapNotNull { line ->
            ENTRY_PATTERN.find(line.trim())?.let {
                TestEntry(it.groupValues[1], it.groupValues[2], it.groupValues[4])
            } ?: CLASS_ENTRY_PATTERN.find(line.trim())?.let {
                TestEntry(it.groupValues[1], "", "")
            }
        }
        if (entries.isEmpty()) return null

        val countMatch = COUNT_HEADER.find(lines.first())
        val count = countMatch?.groupValues?.get(1)?.toIntOrNull() ?: entries.size
        val grouped = entries.groupBy { it.className }

        val panel = ToolRenderers.listPanel()
        panel.add(ToolRenderers.headerPanel(ToolIcons.TEST, count, "tests"))

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

            for (test in methods) {
                if (test.method.isEmpty()) continue
                val row = ToolRenderers.rowPanel()
                row.border = JBUI.Borders.emptyLeft(8)
                row.add(ToolRenderers.mutedLabel(":${test.line}"))
                row.add(JBLabel(test.method))
                section.add(row)
            }
            panel.add(section)
        }

        return panel
    }
}
