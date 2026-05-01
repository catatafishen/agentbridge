package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.ui.KeyBadge.Companion.keystrokeTokens
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
        border = JBUI.Borders.empty(2, 6)
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

        /** Formats a keystroke as a single string; prefer [keystrokeTokens] for badge rendering. */
        fun formatKeystroke(stroke: KeyStroke): String = keystrokeTokens(stroke).joinToString("+")

        fun keystrokeTokens(stroke: KeyStroke): List<String> {
            val isMac = System.getProperty("os.name", "").lowercase(Locale.ROOT).startsWith("mac")
            return if (isMac) macKeystrokeTokens(stroke) else defaultKeystrokeTokens(stroke)
        }

        private fun macKeystrokeTokens(stroke: KeyStroke): List<String> {
            val tokens = mutableListOf<String>()
            val modifiers = stroke.modifiers
            if (modifiers and InputEvent.CTRL_DOWN_MASK != 0) tokens.add("⌃")
            if (modifiers and InputEvent.ALT_DOWN_MASK != 0) tokens.add("⌥")
            if (modifiers and InputEvent.SHIFT_DOWN_MASK != 0) tokens.add("⇧")
            if (modifiers and InputEvent.META_DOWN_MASK != 0) tokens.add("⌘")
            tokens.add(macKeyText(stroke.keyCode))
            return tokens
        }

        private fun defaultKeystrokeTokens(stroke: KeyStroke): List<String> {
            val tokens = mutableListOf<String>()
            val modifiers = stroke.modifiers
            if (modifiers and InputEvent.CTRL_DOWN_MASK != 0) tokens.add("Ctrl")
            if (modifiers and InputEvent.ALT_DOWN_MASK != 0) tokens.add("Alt")
            if (modifiers and InputEvent.SHIFT_DOWN_MASK != 0) tokens.add("⇧")
            tokens.add(defaultKeyText(stroke.keyCode))
            return tokens
        }

        private fun macKeyText(keyCode: Int): String = when (keyCode) {
            KeyEvent.VK_ENTER -> "⏎"
            KeyEvent.VK_BACK_SPACE -> "⌫"
            KeyEvent.VK_ESCAPE -> "⎋"
            KeyEvent.VK_TAB -> "⇥"
            else -> KeyEvent.getKeyText(keyCode)
        }

        private fun defaultKeyText(keyCode: Int): String = when (keyCode) {
            KeyEvent.VK_ENTER -> "↵"
            KeyEvent.VK_BACK_SPACE -> "⌫"
            KeyEvent.VK_ESCAPE -> "Esc"
            KeyEvent.VK_TAB -> "⇥"
            KeyEvent.VK_UP -> "↑"
            else -> KeyEvent.getKeyText(keyCode)
        }
    }
}
