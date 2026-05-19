package com.github.catatafishen.agentbridge.ui

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.FlowLayout
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * A panel that displays keyboard shortcut hints (e.g. "Enter Send", "Shift+Enter New line").
 * Call [setShortcuts] to update the displayed hint cells.
 */
class PromptShortcutHintPanel : JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)) {

    init {
        isOpaque = false
        border = JBUI.Borders.empty()
    }

    fun setShortcuts(shortcuts: List<Pair<KeyStroke, String>>) {
        removeAll()
        shortcuts.forEach { (stroke, label) -> add(createHintCell(stroke, label)) }
        revalidate()
        repaint()
    }

    companion object {
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
}
