package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import java.awt.Font
import javax.swing.JComponent

/**
 * Renderer for list_run_configurations output.
 * Input: "N run configurations:\nName [Type][ (temporary)]"
 */
internal object RunConfigRenderer : ToolResultRenderer {

    private val CONFIG_LINE = Regex("""^(.+?)\s+\[(.+?)](.*)$""")
    private val HEADER = Regex("""^(\d+)\s+run\s+configurations?:""")

    override fun render(output: String): JComponent? {
        val lines = output.lines()
        val headerMatch = lines.firstOrNull()?.let { HEADER.find(it.trim()) }
        val configLines = lines.drop(if (headerMatch != null) 1 else 0)
        val configs = configLines.mapNotNull { CONFIG_LINE.find(it.trim()) }
        if (configs.isEmpty()) return null

        val panel = ToolRenderers.listPanel()
        panel.add(
            ToolRenderers.headerPanel(
                ToolIcons.EXECUTE, configs.size,
                if (configs.size == 1) "run configuration" else "run configurations"
            )
        )

        for (m in configs) {
            val name = m.groupValues[1].trim()
            val type = m.groupValues[2].trim()
            val suffix = m.groupValues[3].trim()
            val isTemp = suffix.contains("temporary")

            val badge = when {
                type.contains("Application") -> AllIcons.RunConfigurations.Application
                type.contains("JUnit") || type.contains("Test") -> AllIcons.RunConfigurations.Junit
                type.contains("Gradle") -> AllIcons.Nodes.ConfigFolder
                else -> AllIcons.RunConfigurations.Compound
            }

            val row = ToolRenderers.rowPanel()
            row.add(JBLabel().apply { icon = badge })
            row.add(JBLabel(name).apply {
                font = font.deriveFont(Font.BOLD)
            })
            row.add(ToolRenderers.mutedLabel(type))
            if (isTemp) row.add(ToolRenderers.mutedLabel("temp"))
            panel.add(row)
        }

        return panel
    }
}
