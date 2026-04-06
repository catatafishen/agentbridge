package com.github.catatafishen.agentbridge.ui.renderers

import com.google.gson.JsonParser
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Font
import javax.swing.JComponent

/**
 * Renders intellij_read_file output as a code block with line numbers.
 * For files longer than [INLINE_LINE_LIMIT], shows a short preview and a
 * "Open in scratch file" hyperlink instead of dumping the entire content inline.
 */
object ReadFileRenderer : ArgumentAwareRenderer {

    private const val INLINE_LINE_LIMIT = 10
    private val NUMBERED_LINE = Regex("""^(\d+): (.*)""")

    override fun render(output: String, arguments: String?): JComponent? {
        if (output.startsWith("Error") || output.startsWith("File not found")) return null

        val raw = output.trimEnd()
        if (raw.isEmpty()) {
            val panel = ToolRenderers.listPanel()
            panel.add(ToolRenderers.mutedLabel("Empty file").apply {
                alignmentX = JComponent.LEFT_ALIGNMENT
            })
            return panel
        }

        val lines = raw.lines()
        val isNumbered = lines.firstOrNull()?.let { NUMBERED_LINE.matches(it) } == true
        val extension = extractExtension(arguments)

        val panel = ToolRenderers.listPanel()

        // Header with line range
        val headerRow = ToolRenderers.rowPanel()
        headerRow.border = JBUI.Borders.emptyBottom(4)
        if (isNumbered) {
            val firstNum = NUMBERED_LINE.find(lines.first())?.groupValues?.get(1) ?: "?"
            val lastNum = NUMBERED_LINE.find(lines.last())?.groupValues?.get(1) ?: "?"
            headerRow.add(JBLabel("Lines $firstNum–$lastNum").apply {
                font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            })
        } else {
            headerRow.add(JBLabel("${lines.size} lines").apply {
                font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            })
        }
        panel.add(headerRow)

        fun formatLine(line: String): String {
            val m = NUMBERED_LINE.find(line)
            return if (m != null && isNumbered) "${m.groupValues[1].padStart(4)}: ${m.groupValues[2]}" else line
        }

        val fullText = lines.joinToString("\n") { formatLine(it) }

        if (lines.size > INLINE_LINE_LIMIT) {
            // Show a short preview + scratch link
            val previewText = lines.take(INLINE_LINE_LIMIT).joinToString("\n") { formatLine(it) }
            panel.add(ToolRenderers.codeBlock(previewText))
            val row = ToolRenderers.rowPanel()
            row.add(ToolRenderers.mutedLabel("⋯ ${lines.size - INLINE_LINE_LIMIT} more lines  "))
            row.add(ToolRenderers.scratchLink("Open in scratch file", fullText, extension))
            panel.add(row)
        } else {
            panel.add(ToolRenderers.codeBlock(fullText))
        }

        return panel
    }

    private fun extractExtension(arguments: String?): String {
        if (arguments.isNullOrBlank()) return "txt"
        return try {
            val path = JsonParser.parseString(arguments).asJsonObject["path"]?.asString ?: return "txt"
            val ext = path.substringAfterLast('.')
            if (ext == path || ext.isEmpty()) "txt" else ext
        } catch (_: Exception) {
            "txt"
        }
    }
}
