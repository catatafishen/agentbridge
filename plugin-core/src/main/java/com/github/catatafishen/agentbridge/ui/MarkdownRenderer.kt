package com.github.catatafishen.agentbridge.ui

/**
 * Converts a subset of Markdown to HTML for display in the chat panel.
 * All methods are pure functions with no external state dependencies;
 * file-resolution behaviour is injected via optional lambda parameters.
 */
object MarkdownRenderer {

    private const val HTML_TABLE_CLOSE = "</table>"
    private const val HTML_BLOCKQUOTE_CLOSE = "</blockquote>"
    private const val HTML_CODE_BLOCK_CLOSE = "</code></pre>"
    private const val THINKING_FALLBACK = "No reasoning returned"
    private val FILE_PATH_REGEX: Regex = run {
        // Split into named parts so S5843 (regex complexity) analysis doesn't flag the combined form.
        val absolutePath = """/[\w.\-]+(?:/[\w.\-]+)*\.\w+"""
        val relativePath = """(?:\.\.?/)?[\w.\-]+(?:/[\w.\-]+)+\.\w+"""
        val lineCol = """(?::\d+(?::\d+)?)?"""
        Regex("""(?<![:\w])(?:$absolutePath|$relativePath)$lineCol""")
    }
    private val GIT_SHA_REGEX = Regex("""^[0-9a-f]{7,40}$""")
    private val BARE_GIT_SHA_REGEX = Regex("""\b([0-9a-f]{7,12})\b""")
    private val THINK_TAG_REGEX =
        Regex("""<(think|thinking)>(.*?)</\1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val WRAPPER_TAG_LINE_REGEX = Regex(
        """(?im)^\s*</?(?:task_result|commentary|example|code)>\s*$\r?\n?"""
    )

    data class MarkdownState(
        var inCode: Boolean = false,
        var inTable: Boolean = false,
        var firstTR: Boolean = true,
        var inList: Boolean = false,
        var inBlockquote: Boolean = false,
        var inImplicitCode: Boolean = false
    )

    @JvmOverloads
    fun markdownToHtml(
        text: String,
        resolveFileReference: (String) -> Pair<String, Int?>? = { null },
        resolveFilePath: (String) -> String? = { null },
        isGitCommit: (String) -> Boolean = { false }
    ): String {
        val lines = preprocessXmlTagsOutsideCodeBlocks(text)
        val sb = StringBuilder()
        val state = MarkdownState()

        for (line in lines) {
            val t = line.trim()
            when {
                t.startsWith("<thinking-block") -> {
                    closeImplicitCode(sb, state)
                    closeAllInlineBlocks(sb, state)
                    sb.append(t)
                }

                t.startsWith("```") -> {
                    closeImplicitCode(sb, state)
                    handleCodeFence(sb, state, t)
                }

                state.inCode -> sb.append(escapeHtml(line)).append("\n")

                t.isEmpty() -> closeImplicitCode(sb, state)

                state.inImplicitCode -> {
                    // Continue the implicit code block unless a major block element starts
                    if (isMajorBlockElement(t)) {
                        closeImplicitCode(sb, state)
                        processBlockElement(sb, state, t, resolveFileReference, resolveFilePath, isGitCommit)
                    } else {
                        sb.append(escapeHtml(t)).append("\n")
                    }
                }

                processBlockElement(
                    sb,
                    state,
                    t,
                    resolveFileReference,
                    resolveFilePath,
                    isGitCommit
                ) -> { /* handled by helper */
                }

                isCodeLikeLine(t) -> {
                    closeAllInlineBlocks(sb, state)
                    sb.append("<pre><code>")
                    state.inImplicitCode = true
                    sb.append(escapeHtml(t)).append("\n")
                }

                else -> sb.append("<p>").append(formatInline(line, resolveFileReference, resolveFilePath, isGitCommit))
                    .append("</p>")
            }
        }

        closeAllBlocks(sb, state)
        return sb.toString()
    }

    private fun preprocessXmlTags(text: String): String {
        var processed = THINK_TAG_REGEX.replace(text) { match ->
            buildThinkingBlockHtml(match.groupValues[2])
        }
        processed = WRAPPER_TAG_LINE_REGEX.replace(processed, "")
        return processed
    }

    private fun preprocessXmlTagsOutsideCodeBlocks(text: String): List<String> {
        val processed = mutableListOf<String>()
        val segment = mutableListOf<String>()
        val state = XmlPreprocessState()

        fun flushSegment() {
            if (segment.isEmpty()) return
            processed += preprocessXmlTags(segment.joinToString("\n")).lines()
            segment.clear()
        }

        for (line in text.lines()) {
            when (classifyXmlPreprocessLine(line, state)) {
                XmlPreprocessAction.FLUSH_AND_ADD -> {
                    flushSegment()
                    processed += line
                }

                XmlPreprocessAction.ADD -> processed += line
                XmlPreprocessAction.BUFFER -> segment += line
            }
        }

        flushSegment()
        return processed
    }

    private data class XmlPreprocessState(
        var inFence: Boolean = false,
        var inImplicit: Boolean = false
    )

    private enum class XmlPreprocessAction { FLUSH_AND_ADD, ADD, BUFFER }

    private fun classifyXmlPreprocessLine(
        line: String,
        state: XmlPreprocessState
    ): XmlPreprocessAction {
        val trimmed = line.trim()
        return when {
            isFenceLine(trimmed, state) -> XmlPreprocessAction.FLUSH_AND_ADD
            state.inFence -> XmlPreprocessAction.ADD
            shouldContinueImplicitCode(trimmed, state) -> XmlPreprocessAction.ADD
            isCodeLikeLine(trimmed) -> startImplicitCode(state)
            else -> XmlPreprocessAction.BUFFER
        }
    }

    private fun isFenceLine(trimmed: String, state: XmlPreprocessState): Boolean {
        if (!trimmed.startsWith("```")) return false
        state.inFence = !state.inFence
        state.inImplicit = false
        return true
    }

    private fun shouldContinueImplicitCode(trimmed: String, state: XmlPreprocessState): Boolean {
        if (!state.inImplicit) return false
        if (trimmed.isEmpty() || isMajorBlockElement(trimmed)) {
            state.inImplicit = false
            return false
        }
        return true
    }

    private fun startImplicitCode(state: XmlPreprocessState): XmlPreprocessAction {
        state.inImplicit = true
        return XmlPreprocessAction.FLUSH_AND_ADD
    }

    private fun buildThinkingBlockHtml(content: String): String {
        val normalized = content.trim()
            .ifEmpty { THINKING_FALLBACK }
            .replace("\r\n", "\n")
            .replace("\r", "\n")
        val escaped = escapeHtml(normalized).replace("\n", "<br>")
        return "<thinking-block><div class=\"thinking-content\">$escaped</div></thinking-block>"
    }

    private fun handleCodeFence(sb: StringBuilder, state: MarkdownState, fenceLine: String) {
        if (state.inCode) {
            sb.append(HTML_CODE_BLOCK_CLOSE); state.inCode = false
        } else {
            closeListAndTable(sb, state)
            val lang = fenceLine.trim().removePrefix("```").trim().lowercase()
            if (lang.isNotEmpty()) {
                sb.append("<pre><code data-lang=\"").append(escapeHtml(lang)).append("\">")
            } else {
                sb.append("<pre><code>")
            }
            state.inCode = true
        }
    }

    private fun processBlockElement(
        sb: StringBuilder, state: MarkdownState, t: String,
        resolveFileReference: (String) -> Pair<String, Int?>?,
        resolveFilePath: (String) -> String?,
        isGitCommit: (String) -> Boolean
    ): Boolean {
        if (handleHorizontalRule(sb, state, t)) return true
        val hm = Regex("^(#{1,4})\\s+(.+)").find(t)
        if (hm != null) {
            closeAllInlineBlocks(sb, state)
            val lv = hm.groupValues[1].length + 1
            sb.append("<h$lv>")
                .append(formatInline(hm.groupValues[2], resolveFileReference, resolveFilePath, isGitCommit))
                .append("</h$lv>")
            return true
        }
        if (handleBlockquote(sb, state, t, resolveFileReference, resolveFilePath, isGitCommit)) return true
        if (handleTableRow(sb, state, t, resolveFileReference, resolveFilePath, isGitCommit)) return true
        if (handleListItem(sb, state, t, resolveFileReference, resolveFilePath, isGitCommit)) return true
        if (state.inBlockquote) {
            sb.append(HTML_BLOCKQUOTE_CLOSE); state.inBlockquote = false
        }
        return false
    }

    private fun handleHorizontalRule(sb: StringBuilder, state: MarkdownState, t: String): Boolean {
        if (t.matches(Regex("^-{3,}$")) || t.matches(Regex("^\\*{3,}$")) || t.matches(Regex("^_{3,}$"))) {
            closeAllInlineBlocks(sb, state)
            sb.append("<hr>")
            return true
        }
        return false
    }

    private fun handleBlockquote(
        sb: StringBuilder, state: MarkdownState, t: String,
        resolveFileReference: (String) -> Pair<String, Int?>?,
        resolveFilePath: (String) -> String?,
        isGitCommit: (String) -> Boolean
    ): Boolean {
        if (!t.startsWith("> ") && t != ">") return false
        closeListAndTable(sb, state)
        if (!state.inBlockquote) {
            sb.append("<blockquote>"); state.inBlockquote = true
        }
        val content = t.removePrefix("> ").removePrefix(">").trim()
        if (content.isNotEmpty()) {
            sb.append("<p>").append(formatInline(content, resolveFileReference, resolveFilePath, isGitCommit))
                .append("</p>")
        }
        return true
    }

    private fun handleTableRow(
        sb: StringBuilder, state: MarkdownState, t: String,
        resolveFileReference: (String) -> Pair<String, Int?>?,
        resolveFilePath: (String) -> String?,
        isGitCommit: (String) -> Boolean
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
            sb.append("<$tag>").append(formatInline(it, resolveFileReference, resolveFilePath, isGitCommit))
                .append("</$tag>")
        }
        sb.append("</tr>"); state.firstTR = false
        return true
    }

    private fun handleListItem(
        sb: StringBuilder, state: MarkdownState, t: String,
        resolveFileReference: (String) -> Pair<String, Int?>?,
        resolveFilePath: (String) -> String?,
        isGitCommit: (String) -> Boolean
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
            .append(
                formatInline(
                    t.removePrefix("- ").removePrefix("* "),
                    resolveFileReference,
                    resolveFilePath,
                    isGitCommit
                )
            )
            .append("</li>")
        return true
    }

    private fun closeAllInlineBlocks(sb: StringBuilder, state: MarkdownState) {
        closeListAndTable(sb, state)
        if (state.inBlockquote) {
            sb.append(HTML_BLOCKQUOTE_CLOSE); state.inBlockquote = false
        }
    }

    private fun closeListAndTable(sb: StringBuilder, state: MarkdownState) {
        if (state.inList) {
            sb.append("</ul>"); state.inList = false
        }
        if (state.inTable) {
            sb.append(HTML_TABLE_CLOSE); state.inTable = false
        }
    }

    private fun closeImplicitCode(sb: StringBuilder, state: MarkdownState) {
        if (state.inImplicitCode) {
            sb.append(HTML_CODE_BLOCK_CLOSE)
            state.inImplicitCode = false
        }
    }

    /** Returns true if [line] is a code-comment that should be auto-wrapped in a `<pre><code>` block. */
    private fun isCodeLikeLine(line: String): Boolean =
        line.startsWith("//") || line.startsWith("/*") || line.startsWith("*/")

    /**
     * Returns true if [line] starts a major block element (heading or HR) that should break an
     * in-progress implicit code block even without a blank line separator.
     */
    private fun isMajorBlockElement(line: String): Boolean =
        line.matches(Regex("^#{1,4}\\s+.+")) ||
            line.matches(Regex("^-{3,}$")) ||
            line.matches(Regex("^\\*{3,}$")) ||
            line.matches(Regex("^_{3,}$")) ||
            line.startsWith("> ")

    private fun closeAllBlocks(sb: StringBuilder, state: MarkdownState) {
        if (state.inImplicitCode) {
            sb.append(HTML_CODE_BLOCK_CLOSE); state.inImplicitCode = false
        }
        if (state.inCode) sb.append(HTML_CODE_BLOCK_CLOSE)
        if (state.inTable) sb.append(HTML_TABLE_CLOSE)
        if (state.inList) sb.append("</ul>")
        if (state.inBlockquote) sb.append(HTML_BLOCKQUOTE_CLOSE)
    }

    private fun formatInline(
        text: String,
        resolveFileReference: (String) -> Pair<String, Int?>?,
        resolveFilePath: (String) -> String?,
        isGitCommit: (String) -> Boolean
    ): String {
        val result = StringBuilder()
        var lastEnd = 0
        val combinedPattern = inlineFormattingPattern()
        for (match in combinedPattern.findAll(text)) {
            result.append(formatNonCode(text.substring(lastEnd, match.range.first), resolveFilePath, isGitCommit))
            result.append(formatInlineMatch(match, resolveFileReference, resolveFilePath, isGitCommit))
            lastEnd = match.range.last + 1
        }
        result.append(formatNonCode(text.substring(lastEnd), resolveFilePath, isGitCommit))
        return result.toString()
    }

    private fun inlineFormattingPattern(): Regex {
        val boldPattern = """\*\*(.+?)\*\*"""
        val inlineCodePattern = "`([^`]+)`"
        val mdLinkPattern = """\[([^\]]+)]\(([^)]+)\)"""
        val urlLinkPattern = """(https?://[^\s<>\[\]()]+)"""
        return Regex("$boldPattern|$inlineCodePattern|$mdLinkPattern|$urlLinkPattern")
    }

    private fun formatInlineMatch(
        match: MatchResult,
        resolveFileReference: (String) -> Pair<String, Int?>?,
        resolveFilePath: (String) -> String?,
        isGitCommit: (String) -> Boolean
    ): String = when {
        match.groupValues[1].isNotEmpty() -> formatBold(
            match.groupValues[1],
            resolveFileReference,
            resolveFilePath,
            isGitCommit
        )

        match.groupValues[2].isNotEmpty() -> formatInlineCode(match.groupValues[2], resolveFileReference, isGitCommit)
        match.groupValues[3].isNotEmpty() -> formatMarkdownLink(
            match,
            resolveFileReference,
            resolveFilePath,
            isGitCommit
        )

        match.groupValues[5].isNotEmpty() -> formatBareUrl(match.groupValues[5])
        else -> ""
    }

    private fun formatBold(
        content: String,
        resolveFileReference: (String) -> Pair<String, Int?>?,
        resolveFilePath: (String) -> String?,
        isGitCommit: (String) -> Boolean
    ): String = "<b>${formatInline(content, resolveFileReference, resolveFilePath, isGitCommit)}</b>"

    private fun formatInlineCode(
        content: String,
        resolveFileReference: (String) -> Pair<String, Int?>?,
        isGitCommit: (String) -> Boolean
    ): String {
        val resolved = resolveFileReference(content)
        return when {
            resolved != null -> "<a href='openfile://${resolvedHref(resolved)}'><code>${escapeHtml(content)}</code></a>"
            GIT_SHA_REGEX.matches(content) && isGitCommit(content) ->
                "<a href='gitshow://$content'><code>${escapeHtml(content)}</code></a>"

            else -> "<code>${escapeHtml(content)}</code>"
        }
    }

    private fun formatMarkdownLink(
        match: MatchResult,
        resolveFileReference: (String) -> Pair<String, Int?>?,
        resolveFilePath: (String) -> String?,
        isGitCommit: (String) -> Boolean
    ): String {
        val linkText = escapeHtml(match.groupValues[3])
        val rawTarget = match.groupValues[4].trim()
        val resolved = resolveFileReference(rawTarget)
            ?: resolveFilePath(rawTarget.removePrefix("file://"))?.let { it to null }
        return formatMarkdownLinkTarget(linkText, rawTarget, resolved, isGitCommit)
    }

    private fun formatMarkdownLinkTarget(
        linkText: String,
        rawTarget: String,
        resolved: Pair<String, Int?>?,
        isGitCommit: (String) -> Boolean
    ): String = when {
        rawTarget.startsWith("openfile://") || rawTarget.startsWith("gitshow://") ->
            "<a href='${escapeHtml(rawTarget)}'>$linkText</a>"

        resolved != null -> "<a href='openfile://${escapeHtml(resolvedHref(resolved))}'>$linkText</a>"
        GIT_SHA_REGEX.matches(rawTarget) && isGitCommit(rawTarget) ->
            "<a href='gitshow://${escapeHtml(rawTarget)}'>$linkText</a>"

        rawTarget.startsWith("http://") || rawTarget.startsWith("https://") ->
            "<a href='${escapeHtml(rawTarget)}'>$linkText</a>"

        else -> "[${linkText}](${escapeHtml(rawTarget)})"
    }

    private fun resolvedHref(resolved: Pair<String, Int?>): String =
        resolved.first + if (resolved.second != null) ":${resolved.second}" else ""

    private fun formatBareUrl(urlText: String): String {
        val url = escapeHtml(urlText)
        return "<a href='$url'>$url</a>"
    }

    private fun formatNonCode(
        text: String,
        resolveFilePath: (String) -> String?,
        isGitCommit: (String) -> Boolean
    ): String {
        var html = escapeHtml(text)
        html = FILE_PATH_REGEX.replace(html) { m ->
            val pathPart = m.value.split(":")[0]
            val line = m.value.split(":").getOrNull(1)?.toIntOrNull()
            val resolved = resolveFilePath(pathPart)
            if (resolved != null) "<a href='openfile://$resolved${if (line != null) ":$line" else ""}'>${m.value}</a>"
            else m.value
        }
        html = BARE_GIT_SHA_REGEX.replace(html) { m ->
            val sha = m.groupValues[1]
            if (isGitCommit(sha)) "<a href='gitshow://$sha' class='git-commit-link' title='Show commit $sha'>$sha</a>"
            else m.value
        }
        return html
    }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")
}
