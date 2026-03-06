package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Font
import javax.swing.JComponent

/**
 * Renders replace_symbol_body / insert_before_symbol / insert_after_symbol
 * output as a compact status card.
 */
internal object ReplaceSymbolRenderer : ToolResultRenderer {

    private val REPLACED = Regex("""^Replaced lines (\d+)-(\d+) \((\d+) lines?\) with (\d+) lines? in (.+)$""")
    private val INSERTED = Regex("""^Inserted (\d+) lines? (before|after) (.+?) in (.+)$""")
    private val SUCCESS_COLOR = JBColor(Color(0x1A, 0x7F, 0x37), Color(0x3F, 0xB9, 0x50))

    override fun render(output: String): JComponent? {
        val text = output.trimEnd()
        val firstLine = text.lines().first()

        val replacedMatch = REPLACED.find(firstLine)
        if (replacedMatch != null) return renderReplaced(replacedMatch)

        val insertedMatch = INSERTED.find(firstLine)
        if (insertedMatch != null) return renderInserted(insertedMatch)

        return null
    }

    private fun renderReplaced(match: MatchResult): JComponent {
        val startLine = match.groupValues[1]
        val endLine = match.groupValues[2]
        val oldLines = match.groupValues[3]
        val newLines = match.groupValues[4]
        val path = match.groupValues[5]
        val fileName = path.substringAfterLast('/')

        val panel = ToolRenderers.listPanel()

        val headerRow = ToolRenderers.rowPanel()
        headerRow.add(JBLabel("Replaced").apply {
            icon = ToolIcons.SUCCESS
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            foreground = SUCCESS_COLOR
        })
        headerRow.add(ToolRenderers.fileLink(fileName, path))
        panel.add(headerRow)

        val detailRow = ToolRenderers.rowPanel()
        detailRow.add(ToolRenderers.mutedLabel("Lines $startLine–$endLine → $newLines lines"))
        if (oldLines != newLines) {
            val delta = newLines.toInt() - oldLines.toInt()
            val sign = if (delta > 0) "+" else ""
            detailRow.add(ToolRenderers.mutedLabel("(${sign}$delta)"))
        }
        panel.add(detailRow)

        return panel
    }

    private fun renderInserted(match: MatchResult): JComponent {
        val lineCount = match.groupValues[1]
        val position = match.groupValues[2]
        val symbolName = match.groupValues[3]
        val path = match.groupValues[4]
        val fileName = path.substringAfterLast('/')

        val panel = ToolRenderers.listPanel()

        val headerRow = ToolRenderers.rowPanel()
        headerRow.add(JBLabel("Inserted").apply {
            icon = ToolIcons.SUCCESS
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            foreground = SUCCESS_COLOR
        })
        headerRow.add(ToolRenderers.fileLink(fileName, path))
        panel.add(headerRow)

        val detailRow = ToolRenderers.rowPanel()
        detailRow.add(ToolRenderers.mutedLabel("$lineCount lines $position"))
        detailRow.add(JBLabel(symbolName).apply {
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
        })
        panel.add(detailRow)

        return panel
    }
}
