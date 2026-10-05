package com.github.catatafishen.agentbridge.ui

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Component
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * How full the context window is after a turn, e.g. `42k / 128k (33%)`, or null when either figure is unknown
 * (agents that cannot report it simply show nothing). A conversation over the window shows the real percentage,
 * not a capped one, so it is visible that the next request is likely to be trimmed or rejected.
 */
fun formatContextUsage(used: Long?, size: Long?): String? {
    if (used == null || size == null || used < 0 || size <= 0) return null
    val percent = Math.round(used * 100.0 / size)
    return "${compactTokens(used)} / ${compactTokens(size)} ($percent%)"
}

private fun compactTokens(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(java.util.Locale.ROOT, n / 1_000_000.0).replace(".0M", "M")
    n >= 10_000 -> "${Math.round(n / 1000.0)}k"
    n >= 1_000 -> "%.1fk".format(java.util.Locale.ROOT, n / 1000.0).replace(".0k", "k")
    else -> n.toString()
}

fun createTurnStatsRow(stats: TurnStatsData): JComponent {
    val text = buildList {
        add(TimerDisplayFormatter.formatElapsedTime(stats.durationMs / 1000))
        add("${stats.inputTokens}↑ ${stats.outputTokens}↓")
        formatContextUsage(stats.contextUsed, stats.contextSize)?.let { add("ctx $it") }
        if (stats.costUsd > 0) add(TimerDisplayFormatter.formatCost(stats.costUsd))
        if (stats.model.isNotEmpty()) add(stats.model.substringAfterLast('/').substringAfterLast(':'))
    }.joinToString(" · ")

    val label = JBLabel(text).apply {
        foreground = UIUtil.getContextHelpForeground()
        applyChatFont(-1)
    }

    if (stats.linesAdded <= 0 && stats.linesRemoved <= 0) {
        label.border = JBUI.Borders.empty(1, 0, 5, 0)
        return label
    }

    val bar = LineDiffBar(stats.linesAdded, stats.linesRemoved)
    return JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(1, 0, 5, 0)
        alignmentX = Component.LEFT_ALIGNMENT
        add(label)
        add(Box.createHorizontalStrut(JBUI.scale(6)))
        add(bar)
        add(Box.createHorizontalGlue())
    }
}
