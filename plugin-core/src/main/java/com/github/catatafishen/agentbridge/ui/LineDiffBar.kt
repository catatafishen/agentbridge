package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.JComponent

/**
 * Renders a GitHub-style line diff count bar: a green "+N" segment and a red "−N" segment
 * displayed side by side, each with a lightly colored background.
 *
 * Both segments are text-sized (their width fits the label + padding), so larger numbers
 * naturally occupy more space while small numbers remain compact.
 *
 * Reusable across chat turn-stats rows, sidebar panels, and any view that needs to display
 * line diff statistics.
 *
 * Font is set via [applyChatFont] so it participates in the live font-size update walk
 * in [NativeChatPanel].
 */
class LineDiffBar(val added: Int, val removed: Int) : JComponent() {

    private val segH get() = JBUI.scale(PlatformApiCompat.getEditorFontSize() + 2)
    private val hPad = JBUI.scale(5)
    private val gap = JBUI.scale(2)
    private val arc = JBUI.scale(3)

    init {
        isOpaque = false
        applyChatFont(-1)
    }

    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(font)
        val addedW = if (added > 0) fm.stringWidth("+$added") + hPad * 2 else 0
        val removedW = if (removed > 0) fm.stringWidth("−$removed") + hPad * 2 else 0
        val g = if (added > 0 && removed > 0) gap else 0
        return Dimension(addedW + removedW + g, segH)
    }

    override fun getMinimumSize(): Dimension = preferredSize
    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        val fm = g2.getFontMetrics(font)
        val ty = (segH - fm.height) / 2 + fm.ascent
        var x = 0

        if (added > 0) {
            val text = "+$added"
            val w = fm.stringWidth(text) + hPad * 2
            g2.color = ADDED_BG
            g2.fillRoundRect(0, 0, w, segH, arc, arc)
            g2.color = ADDED_FG
            g2.font = font
            g2.drawString(text, hPad, ty)
            x = w + if (removed > 0) gap else 0
        }

        if (removed > 0) {
            val text = "−$removed"
            val w = fm.stringWidth(text) + hPad * 2
            g2.color = REMOVED_BG
            g2.fillRoundRect(x, 0, w, segH, arc, arc)
            g2.color = REMOVED_FG
            g2.font = font
            g2.drawString(text, x + hPad, ty)
        }
    }

    companion object {
        val ADDED_BG: JBColor = JBColor(Color(40, 150, 70, 45), Color(60, 185, 100, 55))
        val ADDED_FG: JBColor = JBColor(Color(20, 100, 45), Color(80, 210, 120))
        val REMOVED_BG: JBColor = JBColor(Color(195, 50, 50, 45), Color(215, 75, 75, 55))
        val REMOVED_FG: JBColor = JBColor(Color(155, 35, 35), Color(230, 110, 110))
    }
}
