package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Font
import javax.swing.JComponent

/**
 * Renders get_project_info output as a structured info card with
 * sections for IDE, SDK, modules, build system, and run configs.
 */
internal object ProjectInfoRenderer : ToolResultRenderer {

    override fun render(output: String): JComponent? {
        val lines = output.trimEnd().lines()
        if (lines.isEmpty() || !lines.first().startsWith("Project:")) return null

        val panel = ToolRenderers.listPanel()
        var currentSection: javax.swing.JPanel? = null

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                currentSection = null
                continue
            }
            if (trimmed.startsWith("- ") && currentSection != null) {
                addSectionItem(currentSection, trimmed.removePrefix("- "))
                continue
            }
            currentSection = parseKeyValueLine(trimmed, panel, currentSection)
        }

        return panel
    }

    private fun addSectionItem(section: javax.swing.JPanel, item: String) {
        val itemRow = ToolRenderers.rowPanel()
        itemRow.border = JBUI.Borders.emptyLeft(12)
        itemRow.add(ToolRenderers.monoLabel(item))
        section.add(itemRow)
    }

    private fun parseKeyValueLine(
        trimmed: String,
        panel: javax.swing.JPanel,
        currentSection: javax.swing.JPanel?
    ): javax.swing.JPanel? {
        val colonIdx = trimmed.indexOf(':')
        if (colonIdx <= 0 || trimmed.startsWith("- ")) return currentSection

        val key = trimmed.substring(0, colonIdx).trim()
        val value = trimmed.substring(colonIdx + 1).trim()
        val sectionMatch = Regex("""\((\d+)\)""").find(key)

        return when {
            sectionMatch != null -> buildSection(key, sectionMatch, panel)
            key == "Project" -> { addProjectHeader(panel, value); currentSection }
            else -> { addKeyValue(panel, key, value); currentSection }
        }
    }

    private fun buildSection(key: String, sectionMatch: MatchResult, panel: javax.swing.JPanel): javax.swing.JPanel {
        val sectionName = key.replace(sectionMatch.value, "").trim()
        val count = sectionMatch.groupValues[1]
        val section = ToolRenderers.listPanel().apply {
            border = JBUI.Borders.emptyTop(6)
            alignmentX = JComponent.LEFT_ALIGNMENT
        }
        val sectionHeader = ToolRenderers.rowPanel()
        sectionHeader.add(JBLabel(sectionName).apply {
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
        })
        sectionHeader.add(ToolRenderers.mutedLabel(count))
        section.add(sectionHeader)
        panel.add(section)
        return section
    }

    private fun addProjectHeader(panel: javax.swing.JPanel, name: String) {
        panel.add(JBLabel(name).apply {
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD, UIUtil.getLabelFont().size2D + 1f)
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyBottom(4)
        })
    }

    private fun addKeyValue(panel: javax.swing.JPanel, key: String, value: String) {
        val kvRow = ToolRenderers.rowPanel()
        kvRow.add(ToolRenderers.mutedLabel("$key:"))
        kvRow.add(ToolRenderers.monoLabel(value))
        panel.add(kvRow)
    }
}
