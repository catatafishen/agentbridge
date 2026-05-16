package com.github.catatafishen.agentbridge.ui

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.SwingConstants

fun createSessionSeparatorRow(timestamp: String, agent: String): JPanel {
    val text = buildString {
        if (agent.isNotEmpty()) append("$agent · ")
        if (timestamp.length >= 10) append(timestamp.substring(0, 10)) else append(timestamp)
    }
    return JPanel(BorderLayout(0, 2)).apply {
        isOpaque = false
        border = JBUI.Borders.empty(8, 0, 4, 0)
        alignmentX = Component.LEFT_ALIGNMENT
        add(JSeparator(SwingConstants.HORIZONTAL), BorderLayout.CENTER)
        add(JBLabel(text).apply {
            foreground = UIUtil.getContextHelpForeground()
            applyChatFont(-1)
            horizontalAlignment = SwingConstants.CENTER
        }, BorderLayout.SOUTH)
    }
}
