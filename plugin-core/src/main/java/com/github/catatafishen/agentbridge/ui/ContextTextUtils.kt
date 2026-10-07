package com.github.catatafishen.agentbridge.ui

/**
 * Pure text-utility functions extracted from [PromptContextManager].
 *
 * All functions are stateless and free of IDE dependencies, making them
 * easy to unit-test independently.
 */
object ContextTextUtils {

    /** Unicode Object Replacement Character — placeholder for inline context chips. */
    private const val ORC = '\uFFFC'

    /**
     * Replace each ORC in [rawText] with a backtick-wrapped text reference
     * from the corresponding context item, e.g. `` `AuthLoginService.kt:116-170` ``.
     */
    fun replaceOrcsWithTextRefs(rawText: String, items: List<ContextItemData>): String {
        if (items.isEmpty()) return rawText.replace(ORC.toString(), "").trim()
        val sb = StringBuilder()
        var idx = 0
        for (ch in rawText) {
            if (ch == ORC && idx < items.size) {
                sb.append('`').append(items[idx++].name).append('`')
            } else {
                sb.append(ch)
            }
        }
        return sb.toString().trim()
    }

    /**
     * Like [replaceOrcsWithTextRefs], but for nudges, which cannot carry ACP attachment blocks:
     * the text is the only channel to the agent, so each chip must be self-describing.
     * File-backed chips become `` `name` (path:start-end) `` so the agent can `read_file` them;
     * chips carrying [ContextItemData.inlineText] get that content appended after the message.
     */
    fun replaceOrcsWithNudgeRefs(rawText: String, items: List<ContextItemData>): String {
        if (items.isEmpty()) return rawText.replace(ORC.toString(), "").trim()
        val sb = StringBuilder()
        val inlineBlocks = StringBuilder()
        var idx = 0
        for (ch in rawText) {
            if (ch == ORC && idx < items.size) {
                appendNudgeChip(items[idx++], sb, inlineBlocks)
            } else {
                sb.append(ch)
            }
        }
        return (sb.toString().trim() + inlineBlocks).trim()
    }

    /** Renders one chip into [sb]; chips with inline text defer their content to [inlineBlocks]. */
    private fun appendNudgeChip(item: ContextItemData, sb: StringBuilder, inlineBlocks: StringBuilder) {
        sb.append('`').append(item.name).append('`')
        val inline = item.inlineText
        if (inline != null) {
            inlineBlocks.append("\n\n--- ").append(item.name).append(" ---\n").append(inline)
        } else {
            sb.append(" (").append(item.path).append(selectionRangeSuffix(item)).append(')')
        }
    }

    private fun selectionRangeSuffix(item: ContextItemData): String = when {
        !item.isSelection || item.startLine <= 0 -> ""
        item.endLine > item.startLine -> ":${item.startLine}-${item.endLine}"
        else -> ":${item.startLine}"
    }

    /**
     * Compare two text snippets after normalizing tabs to spaces and
     * stripping trailing whitespace per line, so minor indentation
     * mismatches (partial first-line selection, mixed tabs/spaces) still match.
     */
    fun normalizedEquals(a: String, b: String, tabSize: Int): Boolean {
        if (a == b) return true
        val spaces = " ".repeat(tabSize.coerceAtLeast(1))
        val normA = a.replace("\t", spaces).lines().joinToString("\n") { it.trimEnd() }
        val normB = b.replace("\t", spaces).lines().joinToString("\n") { it.trimEnd() }
        return normA == normB
    }

    /**
     * Map an IntelliJ file-type name (lowercased) to the corresponding MIME type string.
     */
    fun getMimeTypeForFileType(fileTypeName: String?): String {
        return when (fileTypeName) {
            "java" -> "text/x-java"
            "kotlin" -> "text/x-kotlin"
            "python" -> "text/x-python"
            "javascript" -> "text/javascript"
            "typescript" -> "text/typescript"
            "xml", "html" -> "text/$fileTypeName"
            else -> "text/plain"
        }
    }
}
