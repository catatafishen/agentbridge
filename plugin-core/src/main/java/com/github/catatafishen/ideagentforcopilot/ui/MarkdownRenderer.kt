package com.github.catatafishen.ideagentforcopilot.ui

/**
 * Converts a subset of Markdown to HTML for display in the chat panel.
 * All methods are pure functions with no external state dependencies;
 * file-resolution behaviour is injected via optional lambda parameters.
 */
internal object MarkdownRenderer {

    private const val HTML_TABLE_CLOSE = "</table>"
    private val FILE_PATH_REGEX = Regex(
        """(?<![:\w])(?:/[\w.\-]+(?:/[\w.\-]+)*\.\w+|(?:\.\.?/)?[\w.\-]+(?:/[\w.\-]+)+\.\w+)(?::\d+(?::\d+)?)?"""
    )
    private val GIT_SHA_REGEX = Regex("""^[0-9a-f]{7,40}$""")

    data class MarkdownState(
        var inCode: Boolean = false,
        var inTable: Boolean = false,
        var firstTR: Boolean = true,
        var inList: Boolean = false
    )

    fun markdownToHtml(
        text: String,
        resolveFileReference: (String) -> Pair<String, Int?>? = { null },
        resolveFilePath: (String) -> String? = { null }
    ): String {
        val lines = text.lines()
        val sb = StringBuilder()
        val state = MarkdownState()

        for (line in lines) {
            val t = line.trim()
            when {
                t.startsWith("```") -> handleCodeFence(sb, state)
                state.inCode -> sb.append(escapeHtml(line)).append("\n")
                processBlockElement(sb, state, t, resolveFileReference, resolveFilePath) -> { /* handled by helper */
                }

                t.isEmpty() -> { /* skip blank lines */
                }

                else -> sb.append("<p>").append(formatInline(line, resolveFileReference, resolveFilePath))
                    .append("</p>")
            }
        }

        closeAllBlocks(sb, state)
        return sb.toString()
    }

    private fun handleCodeFence(sb: StringBuilder, state: MarkdownState) {
        if (state.inCode) {
            sb.append("</code></pre>"); state.inCode = false
        } else {
            closeListAndTable(sb, state)
            sb.append("<pre><code>"); state.inCode = true
        }
    }

    private fun processBlockElement(
        sb: StringBuilder, state: MarkdownState, t: String,
        resolveFileReference: (String) -> Pair<String, Int?>?,
        resolveFilePath: (String) -> String?
    ): Boolean {
        val hm = Regex("^(#{1,4})\\s+(.+)").find(t)
        if (hm != null) {
            closeListAndTable(sb, state)
            val lv = hm.groupValues[1].length + 1
            sb.append("<h$lv>").append(formatInline(hm.groupValues[2], resolveFileReference, resolveFilePath))
                .append("</h$lv>")
            return true
        }
        if (handleTableRow(sb, state, t, resolveFileReference, resolveFilePath)) return true
        if (handleListItem(sb, state, t, resolveFileReference, resolveFilePath)) return true
        return false
    }

    private fun handleTableRow(
        sb: StringBuilder, state: MarkdownState, t: String,
        resolveFileReference: (String) -> Pair<String, Int?>?,
        resolveFilePath: (String) -> String?
    ): Boolean {
        if (!(t.startsWith("|") && t.endsWith("|") && t.count { it == '|' } >= 3)) {
            if (state.inTable) {
                sb.append(HTML_TABLE_CLOSE); state.inTable = false
            }
            return false
        }
        if (state.inList) {
            sb.append("</ul>"); state.inList = false
        }
        if (t.replace(Regex("[|\\-: ]"), "").isEmpty()) return true
        if (!state.inTable) {
            sb.append("<table>"); state.inTable = true; state.firstTR = true
        }
        val cells = t.split("|").drop(1).dropLast(1).map { it.trim() }
        val tag = if (state.firstTR) "th" else "td"
        sb.append("<tr>"); cells.forEach {
            sb.append("<$tag>").append(formatInline(it, resolveFileReference, resolveFilePath)).append("</$tag>")
        }
        sb.append("</tr>"); state.firstTR = false
        return true
    }

    private fun handleListItem(
        sb: StringBuilder, state: MarkdownState, t: String,
        resolveFileReference: (String) -> Pair<String, Int?>?,
        resolveFilePath: (String) -> String?
    ): Boolean {
        if (!(t.startsWith("- ") || t.startsWith("* "))) {
            if (state.inList) {
                sb.append("</ul>"); state.inList = false
            }
            return false
        }
        if (!state.inList) {
            sb.append("<ul>"); state.inList = true
        }
        sb.append("<li>")
            .append(formatInline(t.removePrefix("- ").removePrefix("* "), resolveFileReference, resolveFilePath))
            .append("</li>")
        return true
    }

    private fun closeListAndTable(sb: StringBuilder, state: MarkdownState) {
        if (state.inList) {
            sb.append("</ul>"); state.inList = false
        }
        if (state.inTable) {
            sb.append(HTML_TABLE_CLOSE); state.inTable = false
        }
    }

    private fun closeAllBlocks(sb: StringBuilder, state: MarkdownState) {
        if (state.inCode) sb.append("</code></pre>")
        if (state.inTable) sb.append(HTML_TABLE_CLOSE)
        if (state.inList) sb.append("</ul>")
    }

    private fun formatInline(
        text: String,
        resolveFileReference: (String) -> Pair<String, Int?>?,
        resolveFilePath: (String) -> String?
    ): String {
        val result = StringBuilder()
        var lastEnd = 0
        // Match bold **text**, inline code, markdown links [text](url), or bare URLs
        val combinedPattern =
            Regex("""\*\*(.+?)\*\*|`([^`]+)`|\[([^\]]+)]\((https?://[^)]+)\)|(https?://[^\s<>\[\]()]+)""")
        for (match in combinedPattern.findAll(text)) {
            result.append(formatNonCode(text.substring(lastEnd, match.range.first), resolveFilePath))
            when {
                match.groupValues[1].isNotEmpty() -> {
                    // Bold: **content** — recurse to allow inline code/links inside bold
                    result.append("<b>")
                        .append(formatInline(match.groupValues[1], resolveFileReference, resolveFilePath))
                        .append("</b>")
                }

                match.groupValues[2].isNotEmpty() -> {
                    // Inline code: `content`
                    val content = match.groupValues[2]
                    val resolved = resolveFileReference(content)
                    if (resolved != null) {
                        val href = resolved.first + if (resolved.second != null) ":${resolved.second}" else ""
                        result.append("<a href='openfile://$href'><code>${escapeHtml(content)}</code></a>")
                    } else if (GIT_SHA_REGEX.matches(content)) {
                        result.append("<a href='gitshow://$content'><code>${escapeHtml(content)}</code></a>")
                    } else {
                        result.append("<code>${escapeHtml(content)}</code>")
                    }
                }

                match.groupValues[4].isNotEmpty() -> {
                    // Markdown link: [text](url)
                    val linkText = escapeHtml(match.groupValues[3])
                    val url = escapeHtml(match.groupValues[4])
                    result.append("<a href='$url'>$linkText</a>")
                }

                match.groupValues[5].isNotEmpty() -> {
                    // Bare URL: https://example.com
                    val url = escapeHtml(match.groupValues[5])
                    result.append("<a href='$url'>$url</a>")
                }
            }
            lastEnd = match.range.last + 1
        }
        result.append(formatNonCode(text.substring(lastEnd), resolveFilePath))
        return result.toString()
    }

    private fun formatNonCode(text: String, resolveFilePath: (String) -> String?): String {
        var html = escapeHtml(text)
        html = FILE_PATH_REGEX.replace(html) { m ->
            val pathPart = m.value.split(":")[0]
            val line = m.value.split(":").getOrNull(1)?.toIntOrNull()
            val resolved = resolveFilePath(pathPart)
            if (resolved != null) "<a href='openfile://$resolved${if (line != null) ":$line" else ""}'>${m.value}</a>"
            else m.value
        }
        return html
    }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")
}
