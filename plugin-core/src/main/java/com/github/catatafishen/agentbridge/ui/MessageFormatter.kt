package com.github.catatafishen.agentbridge.ui

import com.google.gson.JsonParser
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

/**
 * Centralized formatting utilities for chat message rendering.
 * <p>
 * Shared between live streaming (Kotlin → JS bridge) and batch history
 * rendering (Kotlin HTML generation). All formatting decisions live here
 * so plugin chat pane and web UI stay consistent.
 */
object MessageFormatter {

    /**
     * Canonical chip status values used as HTML attribute values.
     * Both Kotlin and TypeScript must use these exact strings.
     */
    object ChipStatus {
        const val PENDING = "pending"
        const val RUNNING = "running"
        const val COMPLETE = "complete"
        const val FAILED = "failed"
        const val DENIED = "denied"
        const val THINKING = "thinking"
    }

    enum class TimestampStyle {
        /** HH:mm — for message bubbles and chips */
        COMPACT,
        /** MMM d, yyyy HH:mm — for session separators */
        FULL
    }

    private val FULL_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm")

    /**
     * Format an ISO 8601 timestamp for display.
     * Falls back to the raw string if parsing fails.
     */
    fun formatTimestamp(isoOrLegacy: String, style: TimestampStyle = TimestampStyle.COMPACT): String {
        return try {
            val zdt = Instant.parse(isoOrLegacy).atZone(ZoneId.systemDefault())
            when (style) {
                TimestampStyle.COMPACT -> "%02d:%02d".format(zdt.hour, zdt.minute)
                TimestampStyle.FULL -> FULL_FORMAT.format(zdt)
            }
        } catch (_: Exception) {
            isoOrLegacy
        }
    }

    /** Current time as ISO 8601 string. */
    fun timestamp(): String = Instant.now().toString()

    /** HTML-encode for safe embedding in HTML content and single-quoted attributes. */
    fun escapeHtml(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("'", "&#39;")
            .replace("`", "&#96;")

    /** Escape for embedding inside single-quoted JavaScript string literals. */
    fun escapeJs(s: String): String =
        s.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("`", "\\`")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

    /** Encode a UTF-8 string to base64. Pair with decodeBase64() in TypeScript. */
    fun encodeBase64(s: String): String =
        Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    /**
     * Extract a short subtitle from tool call arguments for chip labels.
     * Returns a truncated value of the primary argument key (e.g. path, query, symbol),
     * or null if no subtitle can be extracted.
     */
    fun formatToolSubtitle(baseName: String, arguments: String?): String? {
        if (arguments.isNullOrBlank()) return null
        val key = TOOL_SUBTITLE_KEY[baseName] ?: return null
        return try {
            val json = JsonParser.parseString(arguments).asJsonObject
            val value = json[key]?.asString ?: return null
            if (value.length > 40) "…" + value.takeLast(37) else value
        } catch (_: Exception) {
            null
        }
    }
}
