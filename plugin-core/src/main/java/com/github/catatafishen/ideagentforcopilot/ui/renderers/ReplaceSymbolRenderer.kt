package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.github.catatafishen.ideagentforcopilot.ui.renderers.ToolRenderers.esc

/**
 * Renders replace_symbol_body / insert_before_symbol / insert_after_symbol
 * output as a compact status card.
 *
 * Input formats:
 * - `Replaced lines X-Y (Z lines) with N lines in path`
 * - `Inserted N lines before symbolName in path`
 * - `Inserted N lines after symbolName in path`
 */
internal object ReplaceSymbolRenderer : ToolResultRenderer {

    private val REPLACED = Regex(
        """^Replaced lines (\d+)-(\d+) \((\d+) lines?\) with (\d+) lines? in (.+)$"""
    )
    private val INSERTED = Regex(
        """^Inserted (\d+) lines? (before|after) (.+?) in (.+)$"""
    )

    override fun render(output: String): String? {
        val text = output.trimEnd()
        val firstLine = text.lines().first()

        val replacedMatch = REPLACED.find(firstLine)
        if (replacedMatch != null) return renderReplaced(replacedMatch)

        val insertedMatch = INSERTED.find(firstLine)
        if (insertedMatch != null) return renderInserted(insertedMatch)

        return null
    }

    private fun renderReplaced(match: MatchResult): String {
        val startLine = match.groupValues[1]
        val endLine = match.groupValues[2]
        val oldLines = match.groupValues[3]
        val newLines = match.groupValues[4]
        val path = match.groupValues[5]
        val fileName = path.substringAfterLast('/')

        val sb = StringBuilder("<div class='file-write-result'>")
        sb.append("<div class='file-write-header file-write-success'>")
        sb.append("<span class='build-icon'>✓</span>")
        sb.append("<span class='build-status'>Replaced</span>")
        sb.append("<span class='git-file-path' title='${esc(path)}'>${esc(fileName)}</span>")
        sb.append("</div>")

        sb.append("<div class='file-write-detail'>")
        sb.append("<span class='build-meta'>Lines $startLine–$endLine</span>")
        sb.append("<span class='file-write-arrow'>→</span>")
        sb.append("<span class='build-meta'>$newLines lines</span>")
        if (oldLines != newLines) {
            val delta = newLines.toInt() - oldLines.toInt()
            val sign = if (delta > 0) "+" else ""
            sb.append("<span class='file-write-delta'>(${sign}$delta)</span>")
        }
        sb.append("</div>")

        sb.append("</div>")
        return sb.toString()
    }

    private fun renderInserted(match: MatchResult): String {
        val lineCount = match.groupValues[1]
        val position = match.groupValues[2]
        val symbolName = match.groupValues[3]
        val path = match.groupValues[4]
        val fileName = path.substringAfterLast('/')

        val sb = StringBuilder("<div class='file-write-result'>")
        sb.append("<div class='file-write-header file-write-success'>")
        sb.append("<span class='build-icon'>✓</span>")
        sb.append("<span class='build-status'>Inserted</span>")
        sb.append("<span class='git-file-path' title='${esc(path)}'>${esc(fileName)}</span>")
        sb.append("</div>")

        sb.append("<div class='file-write-detail'>")
        sb.append("<span class='build-meta'>$lineCount lines $position</span>")
        sb.append("<span class='refactor-symbol'>${esc(symbolName)}</span>")
        sb.append("</div>")

        sb.append("</div>")
        return sb.toString()
    }
}
