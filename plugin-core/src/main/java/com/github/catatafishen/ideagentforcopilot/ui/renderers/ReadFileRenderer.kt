package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.github.catatafishen.ideagentforcopilot.ui.renderers.ToolRenderers.esc

/**
 * Renders intellij_read_file output as a compact code block with line numbers,
 * a file-info header (line count), and automatic truncation for very large files.
 *
 * Input formats:
 * - Full file content (raw text, no line numbers)
 * - Line-range read with numbered lines: `42: code here\n43: more code\n`
 * - Error: `File not found: path`
 */
internal object ReadFileRenderer : ToolResultRenderer {

    private const val MAX_DISPLAY_LINES = 80
    private val NUMBERED_LINE = Regex("""^(\d+): (.*)""")

    override fun render(output: String): String? {
        if (output.startsWith("Error") || output.startsWith("File not found")) return null

        val raw = output.trimEnd()
        if (raw.isEmpty()) return "<span class='file-empty'>Empty file</span>"

        val lines = raw.lines()
        val isNumbered = lines.firstOrNull()?.let { NUMBERED_LINE.matches(it) } == true

        val sb = StringBuilder("<div class='file-read-result'>")

        // Header with line count
        val totalLines = lines.size
        sb.append("<div class='file-read-header'>")
        if (isNumbered) {
            val firstNum = NUMBERED_LINE.find(lines.first())?.groupValues?.get(1) ?: "?"
            val lastNum = NUMBERED_LINE.find(lines.last())?.groupValues?.get(1) ?: "?"
            sb.append("<span class='file-read-range'>Lines $firstNum–$lastNum</span>")
        } else {
            sb.append("<span class='file-read-range'>$totalLines lines</span>")
        }
        sb.append("</div>")

        // Code block with line numbers
        val truncated = totalLines > MAX_DISPLAY_LINES
        val displayLines = if (truncated) lines.take(MAX_DISPLAY_LINES) else lines

        sb.append("<div class='file-read-code'>")
        for (line in displayLines) {
            val numMatch = NUMBERED_LINE.find(line)
            if (numMatch != null) {
                val num = numMatch.groupValues[1]
                val code = numMatch.groupValues[2]
                sb.append("<div class='file-line'>")
                sb.append("<span class='file-line-num'>${esc(num)}</span>")
                sb.append("<span class='file-line-code'>${esc(code)}</span>")
                sb.append("</div>")
            } else {
                sb.append("<div class='file-line'>")
                sb.append("<span class='file-line-code'>${esc(line)}</span>")
                sb.append("</div>")
            }
        }
        sb.append("</div>")

        if (truncated) {
            val remaining = totalLines - MAX_DISPLAY_LINES
            sb.append("<div class='file-read-truncated'>⋯ $remaining more lines</div>")
        }

        sb.append("</div>")
        return sb.toString()
    }
}
