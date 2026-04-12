package com.github.catatafishen.agentbridge.ui

/**
 * Pure HTML builder for user-prompt context-chip bubbles.
 * Extracted from [ChatToolWindowContent] to enable unit testing without UI dependencies.
 */
object PromptBubbleBuilder {

    /**
     * Unicode Object Replacement Character — used as placeholder for inline context chips.
     * Same value as [PromptContextManager.ORC].
     */
    private const val ORC = '\uFFFC'

    /**
     * Builds HTML with inline context-chip links replacing ORC placeholders in the raw prompt text.
     * Returns `null` if [items] is empty.
     */
    fun buildBubbleHtml(rawText: String, items: List<ContextItemData>): String? {
        if (items.isEmpty()) return null
        val sb = StringBuilder()
        var idx = 0
        for (ch in rawText) {
            if (ch == ORC && idx < items.size) {
                val item = items[idx++]
                val href = if (item.isSelection && item.startLine > 0)
                    "openfile://${item.path}:${item.startLine}"
                else
                    "openfile://${item.path}"
                val title = escapeHtml(
                    if (item.isSelection && item.startLine > 0) "${item.path}:${item.startLine}" else item.path
                )
                sb.append("<a class='prompt-ctx-chip' href='$href' title='$title'>${escapeHtml(item.name)}</a>")
            } else {
                appendHtmlChar(ch, sb)
            }
        }
        return sb.toString().trim()
    }

    /**
     * Escapes a string for safe inclusion in HTML content.
     */
    fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;")

    /**
     * Appends a single character to [sb], escaping HTML-special characters.
     */
    private fun appendHtmlChar(ch: Char, sb: StringBuilder) {
        when (ch) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '\'' -> sb.append("&#39;")
            '"' -> sb.append("&quot;")
            '\n' -> sb.append("\n")
            else -> sb.append(ch)
        }
    }
}
