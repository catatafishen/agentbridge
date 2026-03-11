package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Font
import javax.swing.JComponent

/**
 * Renders git branch list output with the current branch highlighted.
 */
internal object GitBranchRenderer : ToolResultRenderer {

    private val BRANCH_LINE = Regex("""^([* ])\s+(\S+)\s+([a-f0-9]+)\s+(.*)$""")
    private val REMOTE_PREFIX = Regex("""^remotes?/""")

    override fun render(output: String): JComponent? {
        val lines = output.trimEnd().lines()
        val branches = lines.mapNotNull { parseBranch(it) }
        if (branches.size < 2) return null

        val locals = branches.filter { !it.isRemote }
        val remotes = branches.filter { it.isRemote }

        val panel = ToolRenderers.listPanel()
        if (locals.isNotEmpty()) appendSection(panel, "Local", locals)
        if (remotes.isNotEmpty()) appendSection(panel, "Remote", remotes)
        return panel
    }

    private data class Branch(
        val name: String,
        val hash: String,
        val message: String,
        val isCurrent: Boolean,
        val isRemote: Boolean,
    )

    private fun parseBranch(line: String): Branch? {
        val match = BRANCH_LINE.find(line) ?: return null
        val marker = match.groupValues[1]
        val name = match.groupValues[2]
        return Branch(
            name = name.replace(REMOTE_PREFIX, ""),
            hash = match.groupValues[3],
            message = match.groupValues[4],
            isCurrent = marker == "*",
            isRemote = name.startsWith("remotes/") || name.startsWith("remote/"),
        )
    }

    private fun appendSection(panel: javax.swing.JPanel, label: String, branches: List<Branch>) {
        val section = ToolRenderers.listPanel().apply {
            border = JBUI.Borders.emptyTop(4)
            alignmentX = JComponent.LEFT_ALIGNMENT
        }
        val sectionHeader = ToolRenderers.rowPanel()
        sectionHeader.add(JBLabel(label).apply {
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
        })
        sectionHeader.add(ToolRenderers.mutedLabel("${branches.size}"))
        section.add(sectionHeader)

        for (b in branches) {
            val row = ToolRenderers.rowPanel()
            if (b.isCurrent) {
                row.add(ToolRenderers.badgeLabel("●", ToolRenderers.SUCCESS_COLOR))
                row.add(JBLabel(b.name).apply {
                    font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
                    foreground = ToolRenderers.SUCCESS_COLOR
                })
            } else {
                row.add(JBLabel(b.name))
            }
            row.add(ToolRenderers.monoLabel(b.hash).apply {
                foreground = JBColor.namedColor("Link.activeForeground", UIUtil.getLabelForeground())
            })
            row.add(ToolRenderers.mutedLabel(b.message))
            section.add(row)
        }

        panel.add(section)
    }
}
