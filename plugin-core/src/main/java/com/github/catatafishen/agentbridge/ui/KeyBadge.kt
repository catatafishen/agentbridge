package com.github.catatafishen.agentbridge.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints

/**
 * Small label rendered with a rounded-rect border and subtle background,
 * mimicking a physical keyboard key cap. Used in the shortcut hint overlay
 * and the Ctrl+/ cheat-sheet popup.
 */
class KeyBadge(text: String) : JBLabel(text) {

    init {
        font = JBUI.Fonts.miniFont()
        foreground = UIUtil.getLabelForeground()
        border = JBUI.Borders.empty(1, 4, 1, 4)
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
    }
}
