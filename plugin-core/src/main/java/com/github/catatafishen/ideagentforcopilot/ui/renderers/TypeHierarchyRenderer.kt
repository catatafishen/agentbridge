package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Font
import javax.swing.JComponent

/**
 * Renders get_type_hierarchy output as a visual class hierarchy with
 * supertypes above and subtypes below the queried type.
 */
internal object TypeHierarchyRenderer : ToolResultRenderer {

    private val HEADER = Regex("""^Type hierarchy for:\s+(.+?)\s+\((class|interface)\)""")
    private val SECTION_HEADER = Regex("""^(Supertypes|Subtypes|Implementations):""")
    private val TYPE_ENTRY = Regex("""^\s+(class|interface|enum|annotation)\s+(\S+)(?:\s+\[(.+)])?""")
    private val NONE_FOUND = Regex("""^\s+\(none found""")

    override fun render(output: String): JComponent? {
        val lines = output.trimEnd().lines()
        if (lines.isEmpty()) return null

        val headerMatch = HEADER.find(lines.first().trim()) ?: return null
        val typeName = headerMatch.groupValues[1]
        val typeKind = headerMatch.groupValues[2]
        val shortName = typeName.substringAfterLast('.')

        var currentSection = ""
        val supertypes = mutableListOf<TypeEntry>()
        val subtypes = mutableListOf<TypeEntry>()

        for (line in lines.drop(1)) {
            val sectionMatch = SECTION_HEADER.find(line.trim())
            if (sectionMatch != null) {
                currentSection = sectionMatch.groupValues[1]; continue
            }
            if (NONE_FOUND.containsMatchIn(line)) continue
            val typeMatch = TYPE_ENTRY.find(line) ?: continue
            val entry = TypeEntry(
                typeMatch.groupValues[1],
                typeMatch.groupValues[2],
                typeMatch.groupValues.getOrNull(3)?.takeIf { it.isNotEmpty() })
            when (currentSection) {
                "Supertypes" -> supertypes.add(entry)
                "Subtypes", "Implementations" -> subtypes.add(entry)
            }
        }

        if (supertypes.isEmpty() && subtypes.isEmpty()) return null

        val panel = ToolRenderers.listPanel()
        if (supertypes.isNotEmpty()) addTypeSection(panel, "Supertypes", supertypes)

        // Center node
        val centerRow = ToolRenderers.rowPanel()
        centerRow.border = JBUI.Borders.empty(4, 0)
        centerRow.add(ToolRenderers.badgeLabel(typeKind[0].uppercaseChar().toString(), kindColor(typeKind)))
        centerRow.add(JBLabel(shortName).apply {
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD, UIUtil.getLabelFont().size2D + 1f)
        })
        panel.add(centerRow)

        if (subtypes.isNotEmpty()) addTypeSection(panel, "Subtypes", subtypes)
        return panel
    }

    private data class TypeEntry(val kind: String, val name: String, val location: String?)

    private fun addTypeSection(panel: javax.swing.JPanel, label: String, entries: List<TypeEntry>) {
        val section = ToolRenderers.listPanel().apply {
            border = JBUI.Borders.emptyTop(4)
            alignmentX = JComponent.LEFT_ALIGNMENT
        }
        val sectionHeader = ToolRenderers.rowPanel()
        sectionHeader.add(JBLabel(label).apply {
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
        })
        sectionHeader.add(ToolRenderers.mutedLabel("${entries.size}"))
        section.add(sectionHeader)

        for (entry in entries) {
            val row = ToolRenderers.rowPanel()
            row.border = JBUI.Borders.emptyLeft(8)
            row.add(ToolRenderers.badgeLabel(entry.kind[0].uppercaseChar().toString(), kindColor(entry.kind)))
            row.add(JBLabel(entry.name.substringAfterLast('.')))
            if (entry.location != null) {
                row.add(ToolRenderers.mutedLabel(entry.location.substringAfterLast('/')))
            }
            section.add(row)
        }
        panel.add(section)
    }

    private fun kindColor(kind: String): Color = when (kind.lowercase()) {
        "class" -> ToolRenderers.CLASS_COLOR
        "interface" -> ToolRenderers.INTERFACE_COLOR
        "enum" -> ToolRenderers.CLASS_COLOR
        else -> UIUtil.getLabelForeground()
    }
}
