package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Font
import javax.swing.JComponent

/**
 * Renders refactor results (rename, safe_delete, extract_method, inline)
 * as a status card with the operation summary and affected files.
 */
internal object RefactorRenderer : ToolResultRenderer {

    private val RENAME = Regex("""^✅\s*Renamed '(.+)' to '(.+)'""")
    private val DELETE_OK = Regex("""^✅\s*Safely deleted '(.+)'""")
    private val EXTRACT = Regex("""^✅\s*Extracted method '(.+)'""")
    private val INLINE = Regex("""^✅\s*Inlined '(.+)'""")
    private val DELETE_FAIL = Regex("""^Cannot safely delete '(.+)' — it has (\d+) usages?:""")
    private val REF_COUNT = Regex("""Updated (\d+) references?""")
    private val FILE_LINE = Regex("""^\s*File:\s+(.+)$""")
    private val USAGE_LINE = Regex("""^\s+(.+?):(\d+)""")

    private val SUCCESS_COLOR = JBColor(Color(0x1A, 0x7F, 0x37), Color(0x3F, 0xB9, 0x50))
    private val FAIL_COLOR = JBColor(Color(0xCF, 0x22, 0x2E), Color(0xF8, 0x53, 0x49))

    override fun render(output: String): JComponent? {
        val lines = output.trimEnd().lines()
        if (lines.isEmpty()) return null
        val first = lines.first().trim()

        return when {
            RENAME.containsMatchIn(first) -> renderRename(RENAME.find(first)!!, lines)
            DELETE_OK.containsMatchIn(first) -> renderDeleteOk(DELETE_OK.find(first)!!, lines)
            EXTRACT.containsMatchIn(first) -> renderSuccess("Extracted method", EXTRACT.find(first)!!.groupValues[1], lines)
            INLINE.containsMatchIn(first) -> renderSuccess("Inlined", INLINE.find(first)!!.groupValues[1], lines)
            DELETE_FAIL.containsMatchIn(first) -> renderDeleteFail(DELETE_FAIL.find(first)!!, lines)
            else -> null
        }
    }

    private fun renderRename(match: MatchResult, lines: List<String>): JComponent {
        val oldName = match.groupValues[1]
        val newName = match.groupValues[2]

        val panel = ToolRenderers.listPanel()
        addSuccessHeader(panel, "Renamed")

        val nameRow = ToolRenderers.rowPanel()
        nameRow.add(JBLabel(oldName).apply {
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            foreground = ToolRenderers.blendColor(UIUtil.getLabelForeground(), UIUtil.getPanelBackground(), 0.3)
        })
        nameRow.add(ToolRenderers.mutedLabel("→"))
        nameRow.add(JBLabel(newName).apply {
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
        })
        panel.add(nameRow)

        addMeta(panel, lines)
        return panel
    }

    private fun renderDeleteOk(match: MatchResult, lines: List<String>): JComponent {
        val name = match.groupValues[1]
        val panel = ToolRenderers.listPanel()
        addSuccessHeader(panel, "Deleted")
        panel.add(JBLabel(name).apply {
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            alignmentX = JComponent.LEFT_ALIGNMENT
        })
        addMeta(panel, lines)
        return panel
    }

    private fun renderSuccess(action: String, name: String, lines: List<String>): JComponent {
        val panel = ToolRenderers.listPanel()
        addSuccessHeader(panel, action)
        panel.add(JBLabel(name).apply {
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            alignmentX = JComponent.LEFT_ALIGNMENT
        })
        addMeta(panel, lines)
        return panel
    }

    private fun renderDeleteFail(match: MatchResult, lines: List<String>): JComponent {
        val name = match.groupValues[1]
        val count = match.groupValues[2]

        val panel = ToolRenderers.listPanel()

        val headerRow = ToolRenderers.rowPanel()
        headerRow.add(JBLabel("✗ Cannot delete").apply {
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            foreground = FAIL_COLOR
        })
        headerRow.add(JBLabel(name).apply {
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
        })
        panel.add(headerRow)

        panel.add(ToolRenderers.mutedLabel("$count usages").apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
        })

        val usages = lines.drop(1).mapNotNull { line ->
            USAGE_LINE.find(line)?.let { it.groupValues[1] to it.groupValues[2] }
        }
        for ((file, lineNum) in usages) {
            val usageRow = ToolRenderers.rowPanel()
            usageRow.border = JBUI.Borders.emptyLeft(8)
            usageRow.add(ToolRenderers.monoLabel(file))
            usageRow.add(ToolRenderers.mutedLabel(":$lineNum"))
            panel.add(usageRow)
        }

        return panel
    }

    private fun addSuccessHeader(panel: javax.swing.JPanel, action: String) {
        val headerRow = ToolRenderers.rowPanel()
        headerRow.add(JBLabel("✓ $action").apply {
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            foreground = SUCCESS_COLOR
        })
        panel.add(headerRow)
    }

    private fun addMeta(panel: javax.swing.JPanel, lines: List<String>) {
        val refCount = lines.firstNotNullOfOrNull { REF_COUNT.find(it) }?.groupValues?.get(1)
        val file = lines.firstNotNullOfOrNull { FILE_LINE.find(it) }?.groupValues?.get(1)?.trim()
        if (refCount == null && file == null) return

        val metaRow = ToolRenderers.rowPanel()
        if (refCount != null) metaRow.add(ToolRenderers.mutedLabel("$refCount references updated"))
        if (file != null) metaRow.add(ToolRenderers.monoLabel(file.substringAfterLast('/')))
        panel.add(metaRow)
    }
}
