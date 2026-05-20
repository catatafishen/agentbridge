package com.github.catatafishen.agentbridge.ui

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.FlowLayout
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * Factory for keyboard shortcut hint cells used in the input footer toolbar.
 * Call [createHintCell] to build a single key-badge + label cell for use
 * as a [com.intellij.openapi.actionSystem.CustomComponentAction] component.
 */
object PromptShortcutHintPanel {

    fun createHintCell(stroke: KeyStroke, label: String): JPanel {
        val cell = JPanel(FlowLayout(FlowLayout.CENTER, JBUI.scale(2), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty()
        }
        KeyBadge.keystrokeTokens(stroke).forEach { cell.add(KeyBadge(it)) }
        cell.add(JBLabel(label).apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.emptyLeft(2)
        })
        return cell
    }
}
