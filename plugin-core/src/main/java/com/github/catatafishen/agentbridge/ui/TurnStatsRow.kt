package com.github.catatafishen.agentbridge.ui

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil

fun createTurnStatsRow(stats: TurnStatsData): JBLabel {
    val text = buildList {
        add("${stats.durationMs / 1000}s")
        add("${stats.inputTokens}↑ ${stats.outputTokens}↓")
        if (stats.costUsd > 0) add("${"%.4f".format(stats.costUsd)}$")
        if (stats.linesAdded > 0 || stats.linesRemoved > 0) add("+${stats.linesAdded} −${stats.linesRemoved}")
        if (stats.model.isNotEmpty()) add(stats.model.substringAfterLast('/').substringAfterLast(':'))
    }.joinToString(" · ")
    return JBLabel(text).apply {
        foreground = UIUtil.getContextHelpForeground()
        applyChatFont(-1)
        border = JBUI.Borders.empty(1, 0, 5, 0)
    }
}
