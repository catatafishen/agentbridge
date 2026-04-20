package com.github.catatafishen.agentbridge.ui

import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.*
import javax.swing.KeyStroke

class KeyBadge(text: String) : JBLabel(text) {

    init {
        font = JBUI.Fonts.smallFont()
        foreground = UIUtil.getLabelForeground()
        border = JBUI.Borders.empty(2, 6, 2, 6)
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val arc = JBUI.scale(4)
        g2.color = BACKGROUND
        g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
        g2.color = BORDER
        g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
        super.paintComponent(g)
    }

    companion object {
        val BACKGROUND: JBColor = JBColor(0xF0F0F0, 0x3C3F41)
        val BORDER: JBColor = JBColor(0xC8C8C8, 0x5E6060)

        /**
         * Formats a keystroke for display as a key-cap label.
         *
         * On macOS, [KeymapUtil.getKeystrokeText] already returns Unicode symbols (⌘, ⇧, ⌃, ⏎).
         * On Windows/Linux, we build the label from the [KeyStroke] data directly: Enter → ↵,
         * Shift → ⇧, Ctrl and Alt kept as text to match native platform conventions.
         */
        fun formatKeystroke(stroke: KeyStroke): String {
            if (System.getProperty("os.name", "").lowercase(Locale.ROOT).startsWith("mac")) {
                return KeymapUtil.getKeystrokeText(stroke)
            }
            val modifiers = stroke.modifiers
            val sb = StringBuilder()
            if (modifiers and InputEvent.CTRL_DOWN_MASK != 0) sb.append("Ctrl+")
            if (modifiers and InputEvent.ALT_DOWN_MASK != 0) sb.append("Alt+")
            if (modifiers and InputEvent.SHIFT_DOWN_MASK != 0) sb.append("⇧")
            sb.append(
                when (stroke.keyCode) {
                    KeyEvent.VK_ENTER -> "↵"
                    KeyEvent.VK_BACK_SPACE -> "⌫"
                    KeyEvent.VK_ESCAPE -> "Esc"
                    KeyEvent.VK_TAB -> "⇥"
                    else -> KeyEvent.getKeyText(stroke.keyCode)
                }
            )
            return sb.toString()
        }
    }
}
