package com.github.catatafishen.agentbridge.settings

import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.*

/**
 * A rounded "chip"-style panel that mirrors the chat-pane tool-chip look:
 * a soft ~10%-alpha background fill and ~35%-alpha border, both in the
 * tool-kind accent color. Used by the Tools page for each tool card so the
 * card itself signals the tool's kind instead of relying on a tiny color dot.
 *
 * Uses [GridBagLayout] for child placement; callers add components as usual.
 */
class ToolChipCard(private val kindColor: Color?) : JBPanel<ToolChipCard>(GridBagLayout()) {

    init {
        isOpaque = false
        border = JBUI.Borders.empty(10, 14)
    }

    // Suppression is intentional: we derive semi-transparent ARGB fills from a single
    // theme-aware base color ([kindColor], which is already a JBColor where applicable).
    // The plain java.awt.Color(r,g,b,a) constructor is the only way to inject the alpha
    // channel; JBColor doesn't expose an alpha-adjustment helper. The derived colors are
    // recomputed every paint, so theme changes still take effect.
    @Suppress("UseJBColor")
    override fun paintComponent(g: Graphics) {
        if (kindColor != null) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = JBUI.scale(10)
                val w = width - 1
                val h = height - 1

                // Background fill — soft tint
                g2.color = Color(kindColor.red, kindColor.green, kindColor.blue, BG_ALPHA)
                g2.fillRoundRect(0, 0, w, h, arc, arc)

                // Border — stronger tint
                g2.color = Color(kindColor.red, kindColor.green, kindColor.blue, BORDER_ALPHA)
                g2.stroke = BasicStroke(JBUI.scale(1).toFloat())
                g2.drawRoundRect(0, 0, w, h, arc, arc)
            } finally {
                g2.dispose()
            }
        }
        super.paintComponent(g)
    }

    companion object {
        // Roughly match the chat tool-chip CSS: 10% bg, 22-35% border.
        private const val BG_ALPHA = 26       // ~10% of 255
        private const val BORDER_ALPHA = 90   // ~35% of 255
    }
}
