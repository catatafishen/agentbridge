package com.github.catatafishen.agentbridge.ui.sessions

import com.github.catatafishen.agentbridge.session.db.SessionSearch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Text and HTML helpers for the session picker. Pure functions, no Swing. */
object SessionFormat {

    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun relativeTime(
        thenMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ): String {
        if (thenMs <= 0) return ""
        val seconds = (nowMs - thenMs) / 1000
        return when {
            seconds < 60 -> "just now"
            seconds < 3600 -> "${seconds / 60}m ago"
            seconds < 86_400 -> "${seconds / 3600}h ago"
            seconds < 7 * 86_400 -> "${seconds / 86_400}d ago"
            else -> DATE.format(Instant.ofEpochMilli(thenMs).atZone(zone))
        }
    }

    fun dateTime(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        if (ms <= 0) "" else DATE_TIME.format(Instant.ofEpochMilli(ms).atZone(zone))

    fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Shortens [s] to at most [max] characters, adding an ellipsis when cut. */
    fun truncate(s: String, max: Int): String {
        val flat = s.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= max) flat else flat.substring(0, max - 1) + "…"
    }

    /** HTML-escapes [text] and bolds the first case-insensitive occurrence of [query]. */
    fun highlight(text: String, query: String): String {
        val q = query.trim()
        if (q.isEmpty()) return escapeHtml(text)
        val idx = text.indexOf(q, ignoreCase = true)
        if (idx < 0) return escapeHtml(text)
        return escapeHtml(text.substring(0, idx)) +
            "<b>" + escapeHtml(text.substring(idx, idx + q.length)) + "</b>" +
            escapeHtml(text.substring(idx + q.length))
    }

    /** "a, ~b +2": inferred branches are prefixed with "~"; at most [max] names are shown. */
    fun branchSummary(branches: List<String>, inferred: Set<String>, max: Int = 2): String {
        if (branches.isEmpty()) return ""
        val shown = branches.take(max).joinToString(", ") { if (it in inferred) "~$it" else it }
        val rest = branches.size - max
        return if (rest > 0) "$shown +$rest" else shown
    }

    fun linesSummary(added: Int, removed: Int): String =
        if (added == 0 && removed == 0) "" else "+$added/\u2212$removed"

    fun shortHash(hash: String): String = hash.take(7)

    /** One-line summary shown below the session title. */
    fun detailLine(s: SessionSearch.SessionSummary): String {
        val parts = mutableListOf(s.agentName(), "${s.turnCount()} turn${if (s.turnCount() == 1) "" else "s"}")
        linesSummary(s.linesAdded(), s.linesRemoved()).takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        branchSummary(s.branches(), s.inferredBranches()).takeIf { it.isNotEmpty() }?.let { parts.add("\u2387 $it") }
        if (s.commits().isNotEmpty()) parts.add("${s.commits().size} commit${if (s.commits().size == 1) "" else "s"}")
        return parts.joinToString(" \u00b7 ")
    }

    private const val MUTED_OPEN_PREFIX = "<p><font color='"

    /** Right-hand preview of a session: its metadata, branches, and every turn with its commits. */
    fun previewHtml(
        s: SessionSearch.SessionSummary,
        turns: List<SessionSearch.TurnPreview>,
        query: String = "",
        mutedColorHex: String = "#888888",
    ): String {
        val sb = StringBuilder("<html><body>")
        sb.append("<h3>").append(escapeHtml(truncate(s.label(), 120))).append("</h3>")
        sb.append(MUTED_OPEN_PREFIX).append(mutedColorHex).append("'>")
            .append(escapeHtml(detailLine(s)))
            .append("<br>")
            .append(escapeHtml(dateTime(s.startedAtMs())))
            .append(" \u2192 ")
            .append(escapeHtml(dateTime(s.lastActivityMs())))
            .append("</font></p>")

        if (s.branches().isNotEmpty()) {
            sb.append("<p><b>Branches:</b> ")
                .append(s.branches().joinToString(", ") { b ->
                    escapeHtml(if (b in s.inferredBranches()) "~$b" else b)
                })
            if (s.inferredBranches().isNotEmpty()) {
                sb.append("<br><font color='").append(mutedColorHex).append("'>~ = inferred from commits</font>")
            }
            sb.append("</p>")
        }

        sb.append("<hr>")
        if (turns.isEmpty()) {
            sb.append(MUTED_OPEN_PREFIX).append(mutedColorHex).append("'>No prompts recorded.</font></p>")
        }
        turns.forEachIndexed { i, t ->
            sb.append(MUTED_OPEN_PREFIX).append(mutedColorHex).append("'>")
                .append(i + 1).append(". ").append(escapeHtml(dateTime(t.startedAtMs())))
            t.branch()?.takeIf { it.isNotBlank() }?.let { sb.append(" \u00b7 \u2387 ").append(escapeHtml(it)) }
            linesSummary(t.linesAdded(), t.linesRemoved()).takeIf { it.isNotEmpty() }
                ?.let { sb.append(" \u00b7 ").append(it) }
            sb.append("</font><br>").append(highlight(truncate(t.prompt(), 400), query))
            t.commits().forEach { c ->
                sb.append("<br>&nbsp;&nbsp;<a href='commit:").append(escapeHtml(c.hash())).append("'>")
                    .append(shortHash(c.hash())).append("</a> ")
                    .append(escapeHtml(truncate(c.message() ?: "", 90)))
            }
            sb.append("</p>")
        }
        return sb.append("</body></html>").toString()
    }
}
