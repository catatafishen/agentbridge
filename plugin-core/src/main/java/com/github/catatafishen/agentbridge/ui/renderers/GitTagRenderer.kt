package com.github.catatafishen.agentbridge.ui.renderers

import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import javax.swing.JComponent

object GitTagRenderer : ToolResultRenderer {

    private val ANNOTATED = Regex("""^(.+?)\s+(.+)$""")

    override fun render(output: String): JComponent? {
        val tags = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (tags.isEmpty()) return null

        val panel = ToolRenderers.listPanel()
        panel.add(
            ToolRenderers.headerPanel(
                ToolIcons.TAG, tags.size,
                if (tags.size == 1) "tag" else "tags"
            )
        )

        for (tag in tags) {
            val row = ToolRenderers.rowPanel()
            val match = ANNOTATED.find(tag)
            if (match != null) {
                row.add(ToolRenderers.monoLabel(match.groupValues[1]).apply {
                    foreground = JBColor.namedColor("Link.activeForeground", UIUtil.getLabelForeground())
                })
                row.add(ToolRenderers.mutedLabel(match.groupValues[2]))
            } else {
                row.add(ToolRenderers.monoLabel(tag).apply {
                    foreground = JBColor.namedColor("Link.activeForeground", UIUtil.getLabelForeground())
                })
            }
            panel.add(row)
        }

        return panel
    }
}
