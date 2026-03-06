package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import java.awt.Font
import javax.swing.JComponent

/**
 * Renders go_to_declaration output as a compact card with the file path,
 * line number, and a code context snippet.
 */
internal object GoToDeclarationRenderer : ToolResultRenderer {

    private val HEADER = Regex("""^Declaration of '(.+)':""")
    private val FILE_LINE = Regex("""^\s*File:\s+(.+)$""")
    private val LINE_NUM = Regex("""^\s*Line:\s+(\d+)""")
    private val CONTEXT_LINE = Regex("""^\s*(→\s*)?(\d+):\s*(.*)$""")

    override fun render(output: String): JComponent? {
        val lines = output.trimEnd().lines()
        if (lines.isEmpty()) return null
        val headerMatch = HEADER.find(lines.first().trim()) ?: return null
        val symbolName = headerMatch.groupValues[1]

        var filePath = ""
        var lineNumber = ""
        val contextLines = mutableListOf<Triple<Boolean, String, String>>()

        for (line in lines.drop(1)) {
            val fileMatch = FILE_LINE.find(line)
            val lineMatch = LINE_NUM.find(line)
            val ctxMatch = CONTEXT_LINE.find(line)
            when {
                fileMatch != null -> filePath = fileMatch.groupValues[1].trim()
                lineMatch != null -> lineNumber = lineMatch.groupValues[1]
                ctxMatch != null -> contextLines.add(
                    Triple(ctxMatch.groupValues[1].isNotEmpty(), ctxMatch.groupValues[2], ctxMatch.groupValues[3])
                )
            }
        }
        if (filePath.isEmpty()) return null

        val panel = ToolRenderers.listPanel()

        // Symbol name
        panel.add(JBLabel(symbolName).apply {
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            alignmentX = JComponent.LEFT_ALIGNMENT
        })

        // File location
        val locRow = ToolRenderers.rowPanel()
        val lineNum = lineNumber.toIntOrNull() ?: 0
        locRow.add(ToolRenderers.fileLink(filePath.substringAfterLast('/'), filePath, lineNum))
        if (lineNumber.isNotEmpty()) {
            locRow.add(ToolRenderers.mutedLabel(":$lineNumber"))
        }
        panel.add(locRow)

        // Code context
        if (contextLines.isNotEmpty()) {
            val codeText = contextLines.joinToString("\n") { (isTarget, num, code) ->
                val prefix = if (isTarget) "→" else " "
                "$prefix $num: $code"
            }
            panel.add(ToolRenderers.codeBlock(codeText))
        }

        return panel
    }
}
