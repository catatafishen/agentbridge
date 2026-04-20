package com.github.catatafishen.agentbridge.ui

import java.awt.Color

/**
 * Pure formatting functions for timer and stats display in [ProcessingTimerPanel].
 * Extracted to enable unit testing without Swing dependencies.
 */
object TimerDisplayFormatter {

    /**
     * Formats elapsed seconds as "Xs" (under a minute) or "Xm Ys" (a minute or more).
     */
    fun formatElapsedTime(elapsedSeconds: Long): String =
        if (elapsedSeconds < 60) "${elapsedSeconds}s"
        else "${elapsedSeconds / 60}m ${elapsedSeconds % 60}s"

    /**
     * Formats a line-added count for display: "+N" when positive, empty otherwise.
     */
    fun formatLinesAdded(count: Int): String =
        if (count > 0) "+$count" else ""

    /**
     * Formats a line-removed count for display: "-N" when positive, empty otherwise.
     */
    fun formatLinesRemoved(count: Int): String =
        if (count > 0) "-$count" else ""

    /**
     * Formats a tool-call count with a bullet prefix: "• N tools" when positive, empty otherwise.
     */
    fun formatToolCount(count: Int): String =
        if (count > 0) "\u2022 $count tools" else ""

    /**
     * Returns true when the turn has completed and produced displayable usage data
     * (tokens or cost).
     */
    fun hasDisplayableUsage(
        isRunning: Boolean,
        costUsd: Double?,
        inputTokens: Int,
        outputTokens: Int,
    ): Boolean = !isRunning && ((costUsd?.let { it > 0.0 } ?: false) || (inputTokens + outputTokens) > 0)

    /**
     * Formats a token count with SI-style suffixes: 0 → "0", 1234 → "1.2k", 1500000 → "1.5M".
     */
    fun formatTokenCount(tokens: Long): String = when {
        tokens >= 1_000_000 -> "%.1fM".format(tokens / 1_000_000.0)
        tokens >= 1_000 -> "%.1fk".format(tokens / 1_000.0)
        else -> tokens.toString()
    }

    /**
     * Formats a USD cost for display: "$0.00" for zero, 4 decimals for sub-cent values,
     * 2 decimals otherwise.
     */
    fun formatCost(costUsd: Double): String = when {
        costUsd <= 0.0 -> "\$0.00"
        costUsd < 0.01 -> "\$${String.format("%.4f", costUsd).trimEnd('0').trimEnd('.')}"
        else -> "\$${String.format("%.2f", costUsd)}"
    }

    /**
     * Formats lines-changed as "+N / -M", showing only the non-zero parts.
     * Returns empty string when both are zero.
     */
    fun formatLinesChanged(added: Int, removed: Int): String = when {
        added > 0 && removed > 0 -> "+$added / -$removed"
        added > 0 -> "+$added"
        removed > 0 -> "-$removed"
        else -> ""
    }

    /**
     * Formats diff counts as colored HTML for use inside a `JLabel` with `<html>` support.
     * Returns `"<html><font color='#HEX'>+N</font> <font color='#HEX'>−M</font></html>"`
     * with green for additions and red for removals, or empty string when both are zero.
     */
    @JvmStatic
    fun formatDiffCountHtml(added: Int, removed: Int, addedColor: Color, removedColor: Color): String {
        if (added <= 0 && removed <= 0) return ""
        val sb = StringBuilder("<html>")
        if (added > 0) sb.append("<font color='").append(colorHex(addedColor)).append("'>+").append(added)
            .append("</font>")
        if (removed > 0) {
            if (added > 0) sb.append(" ")
            sb.append("<font color='").append(colorHex(removedColor)).append("'>\u2212").append(removed)
                .append("</font>")
        }
        sb.append("</html>")
        return sb.toString()
    }

    private fun colorHex(c: Color): String = "#%02x%02x%02x".format(c.red, c.green, c.blue)
}
