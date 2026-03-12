package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import javax.swing.JComponent

/**
 * Renderer for git_stash output.
 * Input (list): "stash@{0}: WIP on main: 1234567 Commit message" per line.
 * Input (push/pop/apply/drop): single-line result message.
 */
object GitStashRenderer : ToolResultRenderer {

    private val STASH_LINE = Regex("""^stash@\{(\d+)}:\s*(.*?)(?::\s+([0-9a-f]+)\s+(.*))?$""")

    override fun render(output: String): JComponent? {
        val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val stashes = lines.mapNotNull { STASH_LINE.find(it) }
        if (stashes.isEmpty()) return null

        val panel = ToolRenderers.listPanel()
        val header =
            ToolRenderers.headerPanel(ToolIcons.STASH, stashes.size, if (stashes.size == 1) "stash" else "stashes")
        panel.add(header)

        for (m in stashes) {
            val index = m.groupValues[1]
            val description = m.groupValues[2]
            val hash = m.groupValues[3]
            val message = m.groupValues[4]

            val row = ToolRenderers.rowPanel()
            row.add(
                ToolRenderers.badgeLabel(
                    index,
                    JBColor.namedColor("Link.activeForeground", UIUtil.getLabelForeground())
                )
            )
            if (message.isNotEmpty()) {
                row.add(JBLabel(message))
                row.add(ToolRenderers.mutedLabel(description))
            } else {
                row.add(JBLabel(description))
            }
            if (hash.isNotEmpty()) {
                row.add(ToolRenderers.monoLabel(hash.take(7)).apply {
                    foreground = JBColor.namedColor("Link.activeForeground", UIUtil.getLabelForeground())
                })
            }
            panel.add(row)
        }

        return panel
    }
}
