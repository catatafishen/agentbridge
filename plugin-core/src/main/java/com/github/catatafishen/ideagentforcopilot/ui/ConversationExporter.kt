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
            is EntryData.SessionSeparator -> sb.appendLine("--- Previous session \uD83D\uDCC5 ${e.timestamp} ---")
        }
        return sb.toString()
    }

    /**
     * Produce a compressed summary of the conversation for context injection.
     * Omits thinking blocks, truncates long responses, and caps total size.
     */
    fun getCompressedSummary(maxChars: Int = 8000): String {
        if (entries.isEmpty()) return ""
        val sb = StringBuilder()
        sb.appendLine("[Previous conversation summary]")
        for (e in entries) when (e) {
            is EntryData.Prompt -> sb.appendLine("User: ${e.text}")
            is EntryData.Text -> {
                val raw = e.raw.toString().trim()
                if (raw.isNotEmpty()) {
                    val truncated = if (raw.length > 600) raw.take(600) + "...[truncated]" else raw
                    sb.appendLine("Agent: $truncated")
                }
            }

            is EntryData.ToolCall -> {
                val info = TOOL_DISPLAY_INFO[e.title]
                val name = info?.displayName ?: e.title
                sb.appendLine("Tool: $name")
            }

            is EntryData.SubAgent -> {
                val info = SUB_AGENT_INFO[e.agentType]
                sb.appendLine("Tool: ${info?.displayName ?: e.agentType} — ${e.description}")
            }

            is EntryData.ContextFiles -> sb.appendLine("Context: ${e.files.joinToString(", ") { it.first }}")
            is EntryData.SessionSeparator -> sb.appendLine("--- ${e.timestamp} ---")
            is EntryData.Thinking -> { /* Thinking entries are rendered in HTML only */
            }

            is EntryData.Status -> { /* Status entries are rendered in HTML only */
            }
        }
        val result = sb.toString()
        if (result.length <= maxChars) return result
        return "[Previous conversation summary - trimmed to recent]\n..." +
            result.substring(result.length - maxChars + 60)
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
                e.timestamp
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
    }
}
