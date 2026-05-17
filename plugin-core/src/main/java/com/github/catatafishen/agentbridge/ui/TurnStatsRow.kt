package com.github.catatafishen.agentbridge.ui

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Component
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

fun createTurnStatsRow(stats: TurnStatsData): JComponent {
    val text = buildList {
        add(TimerDisplayFormatter.formatElapsedTime(stats.durationMs / 1000))
        add("${stats.inputTokens}↑ ${stats.outputTokens}↓")
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
