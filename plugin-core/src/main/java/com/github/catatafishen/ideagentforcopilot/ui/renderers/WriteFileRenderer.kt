package com.github.catatafishen.ideagentforcopilot.ui.renderers

import com.github.catatafishen.ideagentforcopilot.ui.renderers.ToolRenderers.esc

/**
 * Renders intellij_write_file output as a status card with edit summary
 * and context lines displayed as a code block.
 *
 * Input formats:
 * - `Written: path (123 chars)`
 * - `Edited: path (replaced X chars with Y chars)\n\nContext after edit (lines M-N):\nM: code\n...`
 * - `Edited: path (replaced lines X-Y (Z lines) with N chars)\n\nContext after edit ...`
 * - `Created: path`
 */
internal object WriteFileRenderer : ToolResultRenderer {

    private val WRITTEN = Regex("""^Written:\s+(.+?)\s+\((\d+)\s+chars\)""")
    private val CREATED = Regex("""^Created:\s+(.+)$""")
    private val EDITED_CHARS = Regex("""^Edited:\s+(.+?)\s+\(replaced\s+(\d+)\s+chars\s+with\s+(\d+)\s+chars\)""")
    private val EDITED_LINES = Regex("""^Edited:\s+(.+?)\s+\(replaced\s+lines\s+(\d+)-(\d+)\s+\((\d+)\s+lines?\)\s+with\s+(\d+)\s+chars\)""")
    private val CONTEXT_HEADER = Regex("""Context after edit \(lines (\d+)-(\d+)\):""")
    private val CONTEXT_LINE = Regex("""^(\d+): (.*)$""")
    private val SYNTAX_WARNING = Regex("""⚠.*$""", RegexOption.DOT_MATCHES_ALL)

    override fun render(output: String): String? {
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

    private fun renderWritten(text: String, firstLine: String): String {
        val match = WRITTEN.find(firstLine) ?: return fallback(text)
        val path = match.groupValues[1]
        val chars = match.groupValues[2]
        val fileName = path.substringAfterLast('/')
        val syntaxWarn = SYNTAX_WARNING.find(text)?.value

        val sb = StringBuilder("<div class='file-write-result'>")
        sb.append("<div class='file-write-header file-write-success'>")
        sb.append("<span class='build-icon'>✓</span>")
        sb.append("<span class='build-status'>Written</span>")
        sb.append("<span class='git-file-path' title='${esc(path)}'>${esc(fileName)}</span>")
        sb.append("<span class='build-meta'>${esc(chars)} chars</span>")
        sb.append("</div>")
        appendSyntaxWarning(sb, syntaxWarn)
        appendContextBlock(sb, text)
        sb.append("</div>")
        return sb.toString()
    }

    private fun renderCreated(firstLine: String): String {
        val match = CREATED.find(firstLine) ?: return fallback(firstLine)
        val path = match.groupValues[1]
        val fileName = path.substringAfterLast('/')

        val sb = StringBuilder("<div class='file-write-result'>")
        sb.append("<div class='file-write-header file-write-success'>")
        sb.append("<span class='build-icon'>✓</span>")
        sb.append("<span class='build-status'>Created</span>")
        sb.append("<span class='git-file-path' title='${esc(path)}'>${esc(fileName)}</span>")
        sb.append("</div>")
        sb.append("</div>")
        return sb.toString()
    }

    private fun renderEdited(text: String, firstLine: String): String {
        val linesMatch = EDITED_LINES.find(firstLine)
        val charsMatch = EDITED_CHARS.find(firstLine)
        val syntaxWarn = SYNTAX_WARNING.find(text)?.value

        val sb = StringBuilder("<div class='file-write-result'>")

        when {
            linesMatch != null -> {
                val path = linesMatch.groupValues[1]
                val startLine = linesMatch.groupValues[2]
                val endLine = linesMatch.groupValues[3]
                val lineCount = linesMatch.groupValues[4]
                val fileName = path.substringAfterLast('/')

                sb.append("<div class='file-write-header file-write-success'>")
                sb.append("<span class='build-icon'>✓</span>")
                sb.append("<span class='build-status'>Edited</span>")
                sb.append("<span class='git-file-path' title='${esc(path)}'>${esc(fileName)}</span>")
                sb.append("<span class='build-meta'>lines $startLine–$endLine ($lineCount replaced)</span>")
                sb.append("</div>")
            }

            charsMatch != null -> {
                val path = charsMatch.groupValues[1]
                val oldChars = charsMatch.groupValues[2]
                val newChars = charsMatch.groupValues[3]
                val fileName = path.substringAfterLast('/')

                sb.append("<div class='file-write-header file-write-success'>")
                sb.append("<span class='build-icon'>✓</span>")
                sb.append("<span class='build-status'>Edited</span>")
                sb.append("<span class='git-file-path' title='${esc(path)}'>${esc(fileName)}</span>")
                sb.append("<span class='build-meta'>${esc(oldChars)} → ${esc(newChars)} chars</span>")
                sb.append("</div>")
            }

            else -> return fallback(text)
        }

        appendSyntaxWarning(sb, syntaxWarn)
        appendContextBlock(sb, text)
        sb.append("</div>")
        return sb.toString()
    }

    private fun appendSyntaxWarning(sb: StringBuilder, warning: String?) {
        if (warning == null) return
        sb.append("<div class='file-write-warning'>${esc(warning)}</div>")
    }

    private fun appendContextBlock(sb: StringBuilder, text: String) {
        val contextStart = text.indexOf("Context after edit")
        if (contextStart < 0) return

        val contextText = text.substring(contextStart)
        val lines = contextText.lines()
        if (lines.size < 2) return

        sb.append("<div class='file-write-context'>")
        for (line in lines.drop(1)) {
            val ctxMatch = CONTEXT_LINE.find(line) ?: continue
            val num = ctxMatch.groupValues[1]
            val code = ctxMatch.groupValues[2]
            sb.append("<div class='file-line'>")
            sb.append("<span class='file-line-num'>${esc(num)}</span>")
            sb.append("<span class='file-line-code'>${esc(code)}</span>")
            sb.append("</div>")
        }
        sb.append("</div>")
    }

    private fun fallback(text: String): String =
        "<pre class='tool-output'><code>${esc(text)}</code></pre>"
}
