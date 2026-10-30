package com.github.catatafishen.agentbridge.ui

import java.awt.Color
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Pure calculation and formatting functions for billing/usage display.
 * Extracted from [BillingManager] to enable unit testing without Swing dependencies.
 */
object BillingCalculator {

    private const val OVERAGE_COST_PER_REQ = 0.04

    /**
     * Formats token counts and cost for display in usage chips and toolbar stats.
     * Returns an empty string if no usage data is available. Zero token counts are
     * omitted: agents that report only cumulative cost (ACP usage_update) would
     * otherwise render a meaningless "0 tok" prefix.
     *
     * Examples: `"1.2k tok · $0.004"`, `"850 tok · $0.001"`, `"$0.004"`, `""`
     */
    fun formatUsageChip(inputTokens: Int, outputTokens: Int, costUsd: Double?): String {
        val totalTokens = inputTokens + outputTokens
        val costStr = costUsd?.takeIf { it > 0.0 }
            ?.let { "\$${String.format("%.4f", it).trimEnd('0').trimEnd('.')}" }
        if (totalTokens == 0 && costStr == null) return ""
        val tokStr = when {
            totalTokens >= 1000 -> "${totalTokens / 1000}.${(totalTokens % 1000) / 100}k tok"
            totalTokens > 0 -> "$totalTokens tok"
            else -> null
        }
        return listOfNotNull(tokStr, costStr).joinToString(" · ")
    }

    /**
     * Builds an HTML tooltip for the usage graph panel showing daily rate, projection,
     * overage costs, and cycle reset date.
     *
     * @param errorHex CSS hex color for overage warnings (e.g., `"#FF0000"`)
     */
    fun buildGraphTooltip(
        used: Int,
        entitlement: Int,
        currentDay: Int,
        totalDays: Int,
        resetDate: LocalDate,
        errorHex: String,
    ): String {
        val rate = if (currentDay > 0) used.toFloat() / currentDay else 0f
        val projected = (rate * totalDays).toInt()
        val overage = (used - entitlement).coerceAtLeast(0)
        val projectedOverage = (projected - entitlement).coerceAtLeast(0)
        val resetFormatted = resetDate.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))

        return buildString {
            append("<html>")
            append("Day ${currentDay + 1} / $totalDays<br>")
            append("Usage: $used / $entitlement<br>")
            if (overage > 0) {
                val cost = overage * OVERAGE_COST_PER_REQ
                append("<font color='$errorHex'>Overage: $overage reqs ($${String.format("%.2f", cost)})</font><br>")
            }
            append("Projected: ~$projected by cycle end<br>")
            if (projectedOverage > 0) {
                val projCost = projectedOverage * OVERAGE_COST_PER_REQ
                append("<font color='$errorHex'>Projected overage: ~$projectedOverage ($${String.format("%.2f", projCost)})</font><br>")
            }
            append("Resets: $resetFormatted")
            append("</html>")
        }
    }

    /**
     * Linearly interpolates between two colors by [ratio] (0.0 = [from], 1.0 = [to]).
     */
    fun interpolateColor(from: Color, to: Color, ratio: Float): Color {
        val r = (from.red + (to.red - from.red) * ratio).toInt()
        val g = (from.green + (to.green - from.green) * ratio).toInt()
        val b = (from.blue + (to.blue - from.blue) * ratio).toInt()
        return Color(r, g, b)
    }
}
