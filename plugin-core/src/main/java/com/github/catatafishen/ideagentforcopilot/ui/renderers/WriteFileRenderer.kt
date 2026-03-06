package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Font
import javax.swing.JComponent

/**
 * Renders intellij_write_file output as a status card with edit summary
 * and context lines displayed as a code block.
 */
internal object WriteFileRenderer : ToolResultRenderer {

    private val WRITTEN = Regex("""^Written:\s+(.+?)\s+\((\d+)\s+chars\)""")
    private val CREATED = Regex("""^Created:\s+(.+)$""")
    private val EDITED_CHARS = Regex("""^Edited:\s+(.+?)\s+\(replaced\s+(\d+)\s+chars\s+with\s+(\d+)\s+chars\)""")
    private val EDITED_LINES = Regex("""^Edited:\s+(.+?)\s+\(replaced\s+lines\s+(\d+)-(\d+)\s+\((\d+)\s+lines?\)\s+with\s+(\d+)\s+chars\)""")
    private val CONTEXT_LINE = Regex("""^(\d+): (.*)$""")
    private val SYNTAX_WARNING = Regex("""⚠.*$""", RegexOption.DOT_MATCHES_ALL)
    private val SUCCESS_COLOR = JBColor(Color(0x1A, 0x7F, 0x37), Color(0x3F, 0xB9, 0x50))
    private val WARN_COLOR = JBColor(Color(0x9A, 0x6D, 0x00), Color(0xD2, 0x9B, 0x22))

    override fun render(output: String): JComponent? {
        val text = output.trimEnd()
        if (text.isEmpty()) return null
        val firstLine = text.lines().first()

        return when {
            WRITTEN.containsMatchIn(firstLine) -> renderWritten(text, firstLine)
            CREATED.containsMatchIn(firstLine) -> renderCreated(firstLine)
            EDITED_LINES.containsMatchIn(firstLine) -> renderEdited(text, firstLine)
            EDITED_CHARS.containsMatchIn(firstLine) -> renderEdited(text, firstLine)
            else -> null
        }
    }

    private fun renderWritten(text: String, firstLine: String): JComponent? {
        val match = WRITTEN.find(firstLine) ?: return null
        val path = match.groupValues[1]
        val chars = match.groupValues[2]
        val fileName = path.substringAfterLast('/')

        val panel = ToolRenderers.listPanel()
        addStatusHeader(panel, "Written", fileName, path)
        addDetail(panel, "$chars chars")
        addSyntaxWarning(panel, text)
        addContextBlock(panel, text)
        return panel
    }

    private fun renderCreated(firstLine: String): JComponent? {
        val match = CREATED.find(firstLine) ?: return null
        val path = match.groupValues[1]
        val fileName = path.substringAfterLast('/')

        val panel = ToolRenderers.listPanel()
        addStatusHeader(panel, "Created", fileName, path)
        return panel
    }

    private fun renderEdited(text: String, firstLine: String): JComponent? {
        val linesMatch = EDITED_LINES.find(firstLine)
        val charsMatch = EDITED_CHARS.find(firstLine)

        val panel = ToolRenderers.listPanel()

        when {
            linesMatch != null -> {
                val path = linesMatch.groupValues[1]
                val startLine = linesMatch.groupValues[2]
                val endLine = linesMatch.groupValues[3]
                val lineCount = linesMatch.groupValues[4]
                addStatusHeader(panel, "Edited", path.substringAfterLast('/'), path)
                addDetail(panel, "lines $startLine–$endLine ($lineCount replaced)")
            }
            charsMatch != null -> {
                val path = charsMatch.groupValues[1]
                val oldChars = charsMatch.groupValues[2]
                val newChars = charsMatch.groupValues[3]
                addStatusHeader(panel, "Edited", path.substringAfterLast('/'), path)
                addDetail(panel, "$oldChars → $newChars chars")
            }
            else -> return null
        }

        addSyntaxWarning(panel, text)
        addContextBlock(panel, text)
        return panel
    }

    private fun addStatusHeader(panel: javax.swing.JPanel, action: String, fileName: String, fullPath: String) {
        val headerRow = ToolRenderers.rowPanel()
        headerRow.add(JBLabel("✓ $action").apply {
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            foreground = SUCCESS_COLOR
        })
        headerRow.add(ToolRenderers.monoLabel(fileName).apply { toolTipText = fullPath })
        panel.add(headerRow)
    }

    private fun addDetail(panel: javax.swing.JPanel, detail: String) {
        val row = ToolRenderers.rowPanel()
        row.add(ToolRenderers.mutedLabel(detail))
        panel.add(row)
    }

    private fun addSyntaxWarning(panel: javax.swing.JPanel, text: String) {
        val warning = SYNTAX_WARNING.find(text)?.value ?: return
        val row = ToolRenderers.rowPanel()
        row.add(JBLabel(warning).apply { foreground = WARN_COLOR })
        panel.add(row)
    }

    private fun addContextBlock(panel: javax.swing.JPanel, text: String) {
        val contextStart = text.indexOf("Context after edit")
        if (contextStart < 0) return

        val contextText = text.substring(contextStart)
        val codeLines = contextText.lines().drop(1).mapNotNull { line ->
            CONTEXT_LINE.find(line)?.let { "${it.groupValues[1]}: ${it.groupValues[2]}" }
        }
        if (codeLines.isEmpty()) return

        panel.add(ToolRenderers.codeBlock(codeLines.joinToString("\n")))
    }
}
