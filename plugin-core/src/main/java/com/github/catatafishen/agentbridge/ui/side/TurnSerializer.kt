package com.github.catatafishen.agentbridge.ui.side

import com.github.catatafishen.agentbridge.bridge.EntryData

/**
 * Serializes the entries of a single conversation turn into a Markdown-flavoured
 * text block suitable for attaching to a future prompt via
 * [com.github.catatafishen.agentbridge.ui.AttachmentKind.PROMPT].
 *
 * Output shape (kept compact and predictable so the receiving agent can parse it):
 *
 * ```
 * # Historical prompt — <timestamp>
 *
 * **Stats**: 12 tools · 2m 15s
 * **Commits**: 1f2a3b4, c5d6e7f
 *
 * ## User prompt
 * <prompt text verbatim>
 *
 * ## Agent thinking
 * <thinking text>
 *
 * ## Agent response
 * <text>
 *
 * ## Tool calls
 * - `git_status` (read) — <description>
 * - `write_file` (edit) — <description>
 * ```
 *
 * Empty sections are omitted entirely.
 */
internal object TurnSerializer {

    private const val MAX_TOOL_RESULT_CHARS = 200

    fun serialize(
        prompt: EntryData.Prompt,
        entries: List<EntryData>,
        stats: EntryData.TurnStats?,
    ): String {
        val sb = StringBuilder()
        val header = if (prompt.timestamp.isNotEmpty()) prompt.timestamp else "(no timestamp)"
        sb.append("# Historical prompt — ").append(header).append("\n\n")

        if (stats != null) {
            val statsText = PromptsPanel.formatStats(stats)
            if (statsText.isNotEmpty()) sb.append("**Stats**: ").append(statsText).append("\n")
            val commits = PromptsPanel.formatCommits(stats.commitHashes)
            if (commits.isNotEmpty()) sb.append("**").append(commits).append("**\n")
            sb.append("\n")
        }

        sb.append("## User prompt\n").append(prompt.text.trim()).append("\n\n")

        val thinking = entries.filterIsInstance<EntryData.Thinking>().joinToString("\n\n") { it.raw.trim() }
        if (thinking.isNotEmpty()) {
            sb.append("## Agent thinking\n").append(thinking).append("\n\n")
        }

        val response = entries.filterIsInstance<EntryData.Text>().joinToString("\n\n") { it.raw.trim() }
        if (response.isNotEmpty()) {
            sb.append("## Agent response\n").append(response).append("\n\n")
        }

        val toolCalls = entries.filterIsInstance<EntryData.ToolCall>()
        if (toolCalls.isNotEmpty()) {
            sb.append("## Tool calls\n")
            for (tc in toolCalls) {
                sb.append("- `").append(tc.title).append("` (").append(tc.kind).append(")")
                tc.description?.takeIf { it.isNotBlank() }?.let { sb.append(" — ").append(it.trim()) }
                tc.status?.takeIf { it.isNotBlank() && it != "ok" }?.let { sb.append(" [").append(it).append("]") }
                tc.result?.takeIf { it.isNotBlank() }?.let {
                    val snippet = it.trim().take(MAX_TOOL_RESULT_CHARS)
                    val ellipsis = if (it.length > MAX_TOOL_RESULT_CHARS) "…" else ""
                    sb.append("\n    ").append(snippet.replace("\n", " ")).append(ellipsis)
                }
                sb.append("\n")
            }
            sb.append("\n")
        }

        return sb.toString().trimEnd() + "\n"
    }
}
