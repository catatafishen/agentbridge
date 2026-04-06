package com.github.catatafishen.agentbridge.ui.renderers

import com.google.gson.JsonParser
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.ScrollPaneConstants
import javax.swing.text.StyleConstants
import javax.swing.text.StyleContext

/**
 * Renders intellij_write_file output as a status card with an inline diff
 * when old_str/new_str are available in the arguments, otherwise falls back
 * to context lines.
 */
object WriteFileRenderer : ArgumentAwareRenderer {

    private val WRITTEN = Regex("""^Written:\s+(.+?)\s+\((\d+)\s+chars\)""")
    private val CREATED = Regex("""^Created:\s+(.+)$""")
    private val EDITED_CHARS = Regex("""^Edited:\s+(.+?)\s+\(replaced\s+(\d+)\s+chars\s+with\s+(\d+)\s+chars\)""")
    private val EDITED_LINES =
        Regex("""^Edited:\s+(.+?)\s+\(replaced\s+lines\s+(\d+)-(\d+)\s+\((\d+)\s+lines?\)\s+with\s+(\d+)\s+chars\)""")
    private val CONTEXT_LINE = Regex("""^(\d+): (.*)$""")
    private val SYNTAX_WARNING = Regex("""WARNING:.*$""", RegexOption.DOT_MATCHES_ALL)

    private val ADD_BG = JBColor(Color(0xE6, 0xFF, 0xEC), Color(0x1B, 0x3A, 0x22))
    private val DEL_BG = JBColor(Color(0xFF, 0xEB, 0xE9), Color(0x3D, 0x1F, 0x1F))
    private val ADD_FG = JBColor(Color(0x1A, 0x7F, 0x37), Color(0x7E, 0xE7, 0x87))
    private val DEL_FG = JBColor(Color(0xCF, 0x22, 0x2E), Color(0xF8, 0x85, 0x80))

    override fun render(output: String, arguments: String?): JComponent? {
        val text = output.trimEnd()
        if (text.isEmpty()) return null
        val firstLine = text.lines().first()

        val diff = extractDiff(arguments)

        return when {
            WRITTEN.containsMatchIn(firstLine) -> renderWritten(text, firstLine)
            CREATED.containsMatchIn(firstLine) -> renderCreated(firstLine)
            EDITED_LINES.containsMatchIn(firstLine) -> renderEdited(text, firstLine, diff)
            EDITED_CHARS.containsMatchIn(firstLine) -> renderEdited(text, firstLine, diff)
            else -> null
        }
    }

    private data class DiffContent(val oldStr: String, val newStr: String)

    private fun extractDiff(arguments: String?): DiffContent? {
        if (arguments.isNullOrBlank()) return null
        return try {
            val json = JsonParser.parseString(arguments).asJsonObject
            val oldStr = json["old_str"]?.asString ?: return null
            val newStr = json["new_str"]?.asString ?: return null
            if (oldStr.isBlank() && newStr.isBlank()) null else DiffContent(oldStr, newStr)
        } catch (_: Exception) {
            null
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

    private fun renderEdited(text: String, firstLine: String, diff: DiffContent?): JComponent? {
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

        if (diff != null) {
            addDiffBlock(panel, diff)
        } else {
            addContextBlock(panel, text)
        }
        return panel
    }

    private fun addStatusHeader(panel: JPanel, action: String, fileName: String, fullPath: String) {
        val headerRow = ToolRenderers.rowPanel()
        headerRow.add(JBLabel(action).apply {
            icon = ToolIcons.SUCCESS
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            foreground = ToolRenderers.SUCCESS_COLOR
        })
        headerRow.add(ToolRenderers.fileLink(fileName, fullPath))
        panel.add(headerRow)
    }

    private fun addDetail(panel: JPanel, detail: String) {
        val row = ToolRenderers.rowPanel()
        row.add(ToolRenderers.mutedLabel(detail))
        panel.add(row)
    }

    private fun addSyntaxWarning(panel: JPanel, text: String) {
        val warning = SYNTAX_WARNING.find(text)?.value ?: return
        val row = ToolRenderers.rowPanel()
        row.add(JBLabel(warning).apply {
            icon = ToolIcons.WARNING
            foreground = ToolRenderers.WARN_COLOR
        })
        panel.add(row)
    }

    private val DIFF_INLINE_THRESHOLD = 8

    private fun addDiffBlock(panel: JPanel, diff: DiffContent) {
        val oldLines = diff.oldStr.lines()
        val newLines = diff.newStr.lines()
        val totalLines = oldLines.size + newLines.size

        // For large diffs, skip inline rendering and offer the diff viewer instead
        if (totalLines > DIFF_INLINE_THRESHOLD) {
            val statsRow = ToolRenderers.rowPanel()
            statsRow.add(ToolRenderers.mutedLabel("-${oldLines.size} lines  +${newLines.size} lines  "))
            statsRow.add(ToolRenderers.diffViewerLink("Open diff viewer", "Before", diff.oldStr, "After", diff.newStr))
            panel.add(statsRow)
            return
        }

        val scheme = EditorColorsManager.getInstance().globalScheme
        val monoFont = JBUI.Fonts.create("JetBrains Mono", UIUtil.getLabelFont().size)
        val sc = StyleContext.getDefaultStyleContext()

        val textPane = JTextPane().apply {
            isEditable = false
            background = scheme.defaultBackground
            border = JBUI.Borders.empty(6)
            alignmentX = JComponent.LEFT_ALIGNMENT
        }

        val doc = textPane.styledDocument

        val baseStyle = sc.addStyle("base", null).apply {
            StyleConstants.setFontFamily(this, monoFont.family)
            StyleConstants.setFontSize(this, monoFont.size)
            StyleConstants.setForeground(this, scheme.defaultForeground)
        }
        val delStyle = sc.addStyle("del", baseStyle).apply {
            StyleConstants.setForeground(this, DEL_FG)
            StyleConstants.setBackground(this, DEL_BG)
        }
        val addStyle = sc.addStyle("add", baseStyle).apply {
            StyleConstants.setForeground(this, ADD_FG)
            StyleConstants.setBackground(this, ADD_BG)
        }

        for (line in oldLines) {
            doc.insertString(doc.length, "- $line\n", delStyle)
        }
        for (line in newLines) {
            doc.insertString(doc.length, "+ $line\n", addStyle)
        }

        panel.add(textPane)
    }

    private fun addContextBlock(panel: JPanel, text: String) {
        val contextStart = text.indexOf("Context after edit")
        if (contextStart < 0) return

        val contextText = text.substring(contextStart)
        val codeLines = contextText.lines().drop(1).mapNotNull { line ->
            CONTEXT_LINE.find(line)?.let { "${it.groupValues[1]}: ${it.groupValues[2]}" }
        }
        if (codeLines.isEmpty()) return

        panel.add(JBScrollPane(ToolRenderers.codeBlock(codeLines.joinToString("\n"))).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        })
    }
}
