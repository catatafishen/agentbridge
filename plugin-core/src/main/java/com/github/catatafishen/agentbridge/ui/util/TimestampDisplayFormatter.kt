package com.github.catatafishen.agentbridge.ui.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Pure formatting utility for "human-friendly" timestamps used across the side panels
 * (prompts list, review-changes list, and any future timeline view).
 *
 * The display format mirrors the rest of the IDE-side UI:
 * `Today HH:mm`, `Yesterday HH:mm`, `MMM d HH:mm` (same year), `MMM d yyyy HH:mm` (other year).
 *
 * Two entry points are provided so callers can pass whichever representation they have:
 *  - [formatIsoTimestamp] for ISO 8601 strings (chat events, conversation files).
 *  - [formatEpochMillis] for `System.currentTimeMillis()`-style longs (review-row last-edited).
 */
object TimestampDisplayFormatter {

    private val TIME = DateTimeFormatter.ofPattern("HH:mm")
    private val MONTH_DAY = DateTimeFormatter.ofPattern("MMM d")
    private val MONTH_DAY_YEAR = DateTimeFormatter.ofPattern("MMM d yyyy")

    @JvmStatic
    fun formatIsoTimestamp(iso: String): String {
        if (iso.isEmpty()) return ""
        return try {
            formatInstant(Instant.parse(iso))
        } catch (_: Exception) {
            iso
        }
    }

    @JvmStatic
    fun formatEpochMillis(millis: Long): String {
        if (millis <= 0L) return ""
        return formatInstant(Instant.ofEpochMilli(millis))
    }

    private fun formatInstant(instant: Instant): String {
        val zdt = instant.atZone(ZoneId.systemDefault())
        val today = LocalDate.now()
        val date = zdt.toLocalDate()
        val time = TIME.format(zdt)
        return when {
            date == today -> "Today $time"
            date == today.minusDays(1) -> "Yesterday $time"
            date.year == today.year -> "${MONTH_DAY.format(zdt)} $time"
            else -> "${MONTH_DAY_YEAR.format(zdt)} $time"
        }
    }
}
