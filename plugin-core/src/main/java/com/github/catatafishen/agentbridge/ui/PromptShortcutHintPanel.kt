package com.github.catatafishen.agentbridge.ui

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.FlowLayout
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * Factory for individual keyboard-shortcut hint cells.
 * Each cell shows key badges (e.g. Ctrl+Enter) followed by a descriptive label.
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

    /** Short human-readable representation of a keystroke for overflow-popup text. */
    fun keystrokeText(stroke: KeyStroke): String {
        val mods = stroke.modifiers
        val sb = StringBuilder()
        if (mods and InputEvent.CTRL_DOWN_MASK != 0) sb.append("Ctrl+")
        if (mods and InputEvent.ALT_DOWN_MASK != 0) sb.append("Alt+")
        if (mods and InputEvent.SHIFT_DOWN_MASK != 0) sb.append("Shift+")
        if (mods and InputEvent.META_DOWN_MASK != 0) sb.append("⌘")
        sb.append(KeyEvent.getKeyText(stroke.keyCode))
        return sb.toString()
    }
}
