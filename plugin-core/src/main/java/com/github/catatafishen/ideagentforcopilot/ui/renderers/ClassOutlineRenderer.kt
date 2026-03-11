package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Font
import javax.swing.JComponent

/**
 * Renderer for get_class_outline output.
 * Input: "class/interface QName [extends X] [implements Y]\n\nSection:\n  signature\n..."
 */
internal object ClassOutlineRenderer : ToolResultRenderer {

    private val HEADER = Regex("""^(class|interface|enum|record|annotation)\s+(\S+)(.*)""")
    private val SECTION = Regex("""^(Constructors|Methods|Fields|Inner classes):$""")

    override fun render(output: String): JComponent? {
        val lines = output.lines()
        val headerMatch = lines.firstOrNull()?.let { HEADER.find(it.trim()) } ?: return null

        val kind = headerMatch.groupValues[1]
        val qName = headerMatch.groupValues[2]
        val rest = headerMatch.groupValues[3].trim()
        val simpleName = qName.substringAfterLast('.')
        val packageName = if (qName.contains('.')) qName.substringBeforeLast('.') else ""

        val panel = ToolRenderers.listPanel()

        // Header
        val (badge, color) = kindBadge(kind)
        val headerRow = ToolRenderers.rowPanel()
        headerRow.add(ToolRenderers.badgeLabel(badge, color))
        headerRow.add(JBLabel(simpleName).apply {
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
        })
        if (packageName.isNotEmpty()) headerRow.add(ToolRenderers.mutedLabel(packageName))
        panel.add(headerRow)

        if (rest.isNotEmpty()) {
            panel.add(ToolRenderers.mutedLabel(rest).apply {
                alignmentX = JComponent.LEFT_ALIGNMENT
            })
        }

        // Sections
        var currentSection = ""
        val sectionItems = mutableListOf<String>()

        fun flushSection() {
            if (currentSection.isNotEmpty() && sectionItems.isNotEmpty()) {
                addSection(panel, currentSection, sectionItems.toList())
            }
            sectionItems.clear()
        }

        for (line in lines.drop(1)) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val sectionMatch = SECTION.find(trimmed)
            if (sectionMatch != null) {
                flushSection()
                currentSection = sectionMatch.groupValues[1]
            } else if (currentSection.isNotEmpty()) {
                sectionItems.add(trimmed)
            }
        }
        flushSection()

        return panel
    }

    private fun addSection(panel: javax.swing.JPanel, sectionName: String, items: List<String>) {
        val section = ToolRenderers.listPanel().apply {
            border = JBUI.Borders.emptyTop(6)
            alignmentX = JComponent.LEFT_ALIGNMENT
        }
        val sectionHeader = ToolRenderers.rowPanel()
        sectionHeader.add(JBLabel(sectionName).apply {
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
        })
        sectionHeader.add(ToolRenderers.mutedLabel("${items.size}"))
        section.add(sectionHeader)

        val (sectionBadge, sectionColor) = sectionBadge(sectionName)
        for (item in items) {
            val row = ToolRenderers.rowPanel()
            row.border = JBUI.Borders.emptyLeft(8)
            row.add(ToolRenderers.badgeLabel(sectionBadge, sectionColor))
            row.add(ToolRenderers.monoLabel(item))
            section.add(row)
        }
        panel.add(section)
    }

    private fun kindBadge(kind: String): Pair<String, Color> = when (kind) {
        "class" -> "C" to ToolRenderers.CLASS_COLOR
        "interface" -> "I" to ToolRenderers.INTERFACE_COLOR
        "enum" -> "E" to ToolRenderers.CLASS_COLOR
        "record" -> "R" to ToolRenderers.CLASS_COLOR
        "annotation" -> "@" to ToolRenderers.METHOD_COLOR
        else -> kind.first().uppercaseChar().toString() to UIUtil.getLabelForeground()
    }

    private fun sectionBadge(section: String): Pair<String, Color> = when (section) {
        "Constructors" -> "⊕" to ToolRenderers.METHOD_COLOR
        "Methods" -> "M" to ToolRenderers.METHOD_COLOR
        "Fields" -> "F" to ToolRenderers.FIELD_COLOR
        "Inner classes" -> "C" to ToolRenderers.CLASS_COLOR
        else -> "•" to UIUtil.getLabelForeground()
    }
}
