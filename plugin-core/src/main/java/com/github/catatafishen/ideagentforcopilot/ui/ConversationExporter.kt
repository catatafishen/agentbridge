package com.github.catatafishen.ideagentforcopilot.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import java.awt.Color
import javax.swing.UIManager

/**
 * Exports a conversation (list of [EntryData]) to plain text,
 * compressed summary, or a self-contained HTML document.
 *
 * Extracted from [ChatConsolePanel] to keep conversation-export concerns separate.
 */
internal class ConversationExporter(private val entries: List<EntryData>) {

    fun getConversationText(): String {
        val sb = StringBuilder()
        for (e in entries) when (e) {
            is EntryData.Prompt -> sb.appendLine(">>> ${e.text}")
            is EntryData.Text -> {
                sb.append(e.raw); sb.appendLine()
            }

            is EntryData.Thinking -> sb.appendLine("[thinking] ${e.raw}")
            is EntryData.ToolCall -> {
                val info = TOOL_DISPLAY_INFO[e.title]
                val name = info?.displayName ?: e.title
                sb.appendLine("\uD83D\uDD27 $name")
                if (e.arguments != null) sb.appendLine("  params: ${e.arguments}")
            }

            is EntryData.SubAgent -> {
                val info = SUB_AGENT_INFO[e.agentType]
                sb.appendLine("${info?.displayName ?: e.agentType}: ${e.description}")
            }

            is EntryData.ContextFiles -> sb.appendLine(
                "\uD83D\uDCCE ${e.files.size} context file(s): ${
                    e.files.joinToString(
                        ", "
                    ) { it.first }
                }"
            )

            is EntryData.Status -> sb.appendLine("${e.icon} ${e.message}")
            is EntryData.SessionSeparator -> sb.appendLine("--- Previous session \uD83D\uDCC5 ${formatTimestamp(e.timestamp)} ---")
        }
        return sb.toString()
    }

    /**
     * Produce a compressed summary of the conversation for context injection.
     *
     * - Groups entries into named turns (t1, t2, …).
     * - Last 3 turns: full user input + full agent text.
     * - Older turns: first 500 chars of each, marked as truncated.
     * - Tool calls, thoughts, sub-agents: replaced with count markers, e.g. [5 tool calls, 2 thoughts].
     * - Builds from newest → oldest within [maxChars] budget.
     */
    fun getCompressedSummary(maxChars: Int = 8000): String {
        val turns = groupIntoTurns()
        if (turns.isEmpty()) return ""

        val totalTurns = turns.size
        val recentFullTurns = 3

        val blocks = ArrayDeque<String>()
        var usedChars = 0

        for (i in turns.indices.reversed()) {
            val isFull = i >= totalTurns - recentFullTurns
            val block = formatTurnForSummary(turns[i], full = isFull)
            if (usedChars + block.length > maxChars && blocks.isNotEmpty()) break
            blocks.addFirst(block)
            usedChars += block.length
        }

        val shown = blocks.size
        val hint = "Use search_conversation_history(file='current', turn_id='tN') to read any turn in full."
        val header = if (shown < totalTurns) {
            "[Previous conversation: $totalTurns turns. Showing $shown most recent. $hint]\n\n"
        } else {
            "[Previous conversation: $totalTurns turn${if (totalTurns > 1) "s" else ""}. $hint]\n\n"
        }
        return header + blocks.joinToString("\n")
    }

    private data class TurnData(
        val id: String,
        val userText: String,
        val agentText: String,
        val toolCallCount: Int,
        val thinkingCount: Int,
        val subAgentCount: Int,
    )

    private fun groupIntoTurns(): List<TurnData> {
        val turns = mutableListOf<TurnData>()
        var currentPrompt: EntryData.Prompt? = null
        val agentTextBuf = StringBuilder()
        var toolCount = 0
        var thinkCount = 0
        var subAgentCount = 0

        fun flush() {
            val p = currentPrompt ?: return
            turns.add(
                TurnData(
                    id = "t${turns.size + 1}",
                    userText = p.text,
                    agentText = agentTextBuf.toString().trim(),
                    toolCallCount = toolCount,
                    thinkingCount = thinkCount,
                    subAgentCount = subAgentCount,
                )
            )
        }

        for (e in entries) {
            when (e) {
                is EntryData.Prompt -> {
                    flush()
                    currentPrompt = e
                    agentTextBuf.clear()
                    toolCount = 0
                    thinkCount = 0
                    subAgentCount = 0
                }

                is EntryData.Text -> agentTextBuf.append(e.raw)
                is EntryData.ToolCall -> toolCount++
                is EntryData.Thinking -> thinkCount++
                is EntryData.SubAgent -> subAgentCount++
                is EntryData.ContextFiles,
                is EntryData.SessionSeparator,
                is EntryData.Status -> { /* not relevant for turn grouping */
                }
            }
        }
        flush()
        return turns
    }

    private fun formatTurnForSummary(turn: TurnData, full: Boolean): String {
        val sb = StringBuilder()
        sb.appendLine("[${turn.id}] User: ${truncateField(turn.userText, full, "use turn_id='${turn.id}' for full")}")
        if (turn.agentText.isNotEmpty()) {
            sb.appendLine("Agent: ${truncateField(turn.agentText, full, "truncated")}")
        }
        val markerLine = buildMarkerLine(turn)
        if (markerLine != null) sb.appendLine(markerLine)
        return sb.toString()
    }

    private fun truncateField(text: String, full: Boolean, hint: String): String =
        if (full || text.length <= 500) text else text.take(500) + "…[$hint]"

    private fun buildMarkerLine(turn: TurnData): String? {
        val markers = buildList {
            if (turn.toolCallCount > 0) add("${turn.toolCallCount} tool call${if (turn.toolCallCount > 1) "s" else ""}")
            if (turn.thinkingCount > 0) add("${turn.thinkingCount} thinking block${if (turn.thinkingCount > 1) "s" else ""}")
            if (turn.subAgentCount > 0) add("${turn.subAgentCount} sub-agent${if (turn.subAgentCount > 1) "s" else ""}")
        }
        return if (markers.isNotEmpty()) "[${markers.joinToString(", ")}]" else null
    }

    /** Returns the conversation as a self-contained HTML document */
    fun getConversationHtml(): String {
        val sb = StringBuilder()
        sb.append(buildExportCss())
        for (e in entries) sb.append(renderExportEntry(e))
        sb.append("</body></html>")
        return sb.toString()
    }

    private fun buildExportCss(): String {
        val font = UIUtil.getLabelFont()
        val fg = UIUtil.getLabelForeground()
        val bg = UIUtil.getPanelBackground()
        val codeBg =
            UIManager.getColor("EditorPane.background") ?: JBColor(Color(0xF5, 0xF5, 0xF5), Color(0x2B, 0x2B, 0x2B))
        val tblBorder = JBColor(Color(0xDD, 0xDD, 0xDD), Color(0x55, 0x55, 0x55))
        val thBg = JBColor(Color(0xEE, 0xEE, 0xEE), Color(0x3C, 0x3F, 0x41))
        val linkColor = UIManager.getColor(LINK_COLOR_KEY) ?: JBColor(Color(0x29, 0x79, 0xFF), Color(0x58, 0x9D, 0xF6))
        return """<!DOCTYPE html><html><head><meta charset="utf-8"><style>
body{font-family:'${font.family}',system-ui,sans-serif;font-size:${font.size}pt;color:${rgb(fg)};background:${rgb(bg)};max-width:900px;margin:0 auto;padding:20px}
.prompt{margin:12px 0 4px}
.prompt-b{display:inline-block;background:${rgba(USER_COLOR, 0.12)};border:1px solid ${
            rgba(
                USER_COLOR,
                0.3
            )
        };border-radius:16px 16px 16px 4px;padding:8px 14px;color:${rgb(USER_COLOR)};font-weight:600}
.response{margin:4px 0;line-height:1.55}
.thinking{background:${rgba(THINK_COLOR, 0.06)};border:1px solid ${
            rgba(
                THINK_COLOR,
                0.2
            )
        };border-radius:4px 16px 16px 16px;padding:6px 12px;margin:4px 0;font-size:0.88em;color:${rgb(THINK_COLOR)}}
.tool{display:inline-flex;align-items:center;gap:6px;background:${rgba(TOOL_COLOR, 0.1)};border:1px solid ${
            rgba(
                TOOL_COLOR,
                0.3
            )
        };border-radius:20px;padding:3px 12px;margin:2px 0;font-size:0.88em;color:${rgb(TOOL_COLOR)}}
.context{font-size:0.88em;color:${rgb(USER_COLOR)};margin:2px 0}
.context summary{cursor:pointer;padding:4px 0}
.context .ctx-file{padding:2px 0;padding-left:8px}
.context .ctx-file a{color:${rgb(linkColor)};text-decoration:none}
.status{padding:4px 8px;margin:2px 0;font-size:0.88em}
.status.error{color:red} .status.info{color:${rgb(THINK_COLOR)}}
code{background:${rgb(codeBg)};padding:2px 5px;border-radius:4px;font-family:'JetBrains Mono',monospace;font-size:${font.size - 3}pt}
pre{background:${rgb(codeBg)};padding:10px;border-radius:6px;margin:6px 0;overflow-x:auto}
pre code{background:none;padding:0;border-radius:0;display:block}
table{border-collapse:collapse;margin:6px 0}
th,td{border:1px solid ${rgb(tblBorder)};padding:4px 10px;text-align:left}
th{background:${rgb(thBg)};font-weight:600}
a{color:${rgb(linkColor)}}
ul,ol{margin:4px 0;padding-left:22px}
</style></head><body>
"""
    }

    private fun renderExportEntry(e: EntryData): String = when (e) {
        is EntryData.Prompt -> "<div class='prompt'><span class='prompt-b'>${escapeHtml(e.text)}</span></div>\n"
        is EntryData.Text -> "<div class='response'>${markdownToHtml(e.raw.toString())}</div>\n"
        is EntryData.Thinking -> "<details class='thinking'><summary>\uD83D\uDCAD Thought process</summary><pre>${
            escapeHtml(
                e.raw.toString()
            )
        }</pre></details>\n"

        is EntryData.ToolCall -> renderExportToolCall(e)
        is EntryData.SubAgent -> renderExportSubAgent(e)
        is EntryData.ContextFiles -> renderExportContextFiles(e)
        is EntryData.Status -> "<div class='status ${if (e.icon == ICON_ERROR) "error" else "info"}'>${e.icon} ${
            escapeHtml(
                e.message
            )
        }</div>\n"

        is EntryData.SessionSeparator -> "<hr style='border:none;border-top:1px solid #555;margin:16px 0'><div style='text-align:center;font-size:0.85em;color:#888'>Previous session \uD83D\uDCC5 ${
            escapeHtml(
                formatTimestamp(e.timestamp)
            )
        }</div>\n"
    }

    private fun renderExportToolCall(e: EntryData.ToolCall): String {
        val info = TOOL_DISPLAY_INFO[e.title]
        val displayName = info?.displayName ?: e.title
        val sb = StringBuilder("<details class='tool'><summary>\u2692 ${escapeHtml(displayName)}</summary>")
        if (info?.description != null) sb.append("<div style='font-style:italic;margin:4px 0'>${escapeHtml(info.description)}</div>")
        if (e.arguments != null) sb.append("<div style='margin:4px 0'><b>Parameters:</b><pre><code>${escapeHtml(e.arguments)}</code></pre></div>")
        sb.append("</details>\n")
        return sb.toString()
    }

    private fun renderExportSubAgent(e: EntryData.SubAgent): String {
        val info = SUB_AGENT_INFO[e.agentType]
        val name = info?.displayName ?: e.agentType
        val sb = StringBuilder()
        if (e.prompt != null) sb.append("<div class='response'><b>@$name</b> ${escapeHtml(e.prompt)}</div>\n")
        if (e.result != null) sb.append("<div class='response'>${markdownToHtml(e.result!!)}</div>\n")
        else sb.append("<div class='response'><b>@$name</b> \u2014 ${escapeHtml(e.description)}</div>\n")
        return sb.toString()
    }

    private fun renderExportContextFiles(e: EntryData.ContextFiles): String {
        val label = "${e.files.size} context file${if (e.files.size != 1) "s" else ""} attached"
        val sb = StringBuilder("<details class='context'><summary>\uD83D\uDCCE $label</summary>")
        e.files.forEach { (name, _) -> sb.append("<div class='ctx-file'><code>${escapeHtml(name)}</code></div>") }
        sb.append("</details>\n")
        return sb.toString()
    }

    companion object {
        private fun escapeHtml(text: String): String = text
            .replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;")

        private fun rgb(c: Color) = "rgb(${c.red},${c.green},${c.blue})"
        private fun rgba(c: Color, a: Double) = "rgba(${c.red},${c.green},${c.blue},$a)"

        private fun markdownToHtml(text: String): String =
            MarkdownRenderer.markdownToHtml(text)

        private fun formatTimestamp(isoOrLegacy: String): String {
            return try {
                val zdt = java.time.Instant.parse(isoOrLegacy).atZone(java.time.ZoneId.systemDefault())
                java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm").format(zdt)
            } catch (_: Exception) {
                isoOrLegacy
            }
        }
    }
}
