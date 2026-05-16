package com.github.catatafishen.agentbridge.ui

import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.JViewport

internal const val BUBBLE_V_PAD = 8
private const val BUBBLE_H_PAD = 14
private const val MAX_BUBBLE_WIDTH_FRACTION = 0.94

/**
 * A JPanel that paints a rounded rectangle background. Optionally draws a 1px border
 * when [borderColor] is non-null.
 */
open class RoundedPanel(
    private val bgColor: Color,
    private val borderColor: Color? = null,
    private val radius: Int = JBUI.scale(10),
) : JPanel(BorderLayout()) {

    init {
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = bgColor
        g2.fillRoundRect(0, 0, width, height, radius, radius)
        borderColor?.let {
            g2.color = it
            g2.drawRoundRect(0, 0, width - 1, height - 1, radius, radius)
        }
        g2.dispose()
    }
}

/**
 * Creates a width-capped rounded bubble and its alignment wrapper in one call.
 *
 * Returns a pair of (alignedRow, bubble):
 *  - `alignedRow` is the JPanel to add to the parent container
 *  - `bubble` is the RoundedPanel to add content to
 *
 * All message types share this factory — only color and alignment differ.
 */
fun createBubble(bg: Color, rightAligned: Boolean = false): Pair<JPanel, RoundedPanel> {
    val bubble = object : RoundedPanel(bg) {
        override fun getMaximumSize(): Dimension {
            // Walk up to the nearest JViewport — its width is set by ScrollPaneLayout
            // before any content is measured, so it's always correct even on the very
            // first layout pass of a brand-new turn container whose intermediate
            // ancestors (contentWrapper, turn.container) are still width=0.
            var insetH = 0
            var anc: Container? = parent
            while (anc != null) {
                if (anc is JViewport && anc.width > 0) {
                    val available = (anc.width - insetH).coerceAtLeast(0)
                    return Dimension(
                        (available * MAX_BUBBLE_WIDTH_FRACTION).toInt().coerceAtLeast(JBUI.scale(200)),
                        Int.MAX_VALUE
                    )
                }
                insetH += anc.insets.left + anc.insets.right
                anc = anc.parent
            }
            // Viewport not yet laid out (e.g., panel not yet shown): return unconstrained
            // so NativeMarkdownPane falls back to p.width (previous pass) or super.
            return Dimension(Short.MAX_VALUE.toInt(), Int.MAX_VALUE)
        }

        override fun getPreferredSize(): Dimension {
            val pref = super.getPreferredSize()
            val max = maximumSize
            return Dimension(pref.width.coerceAtMost(max.width), pref.height)
        }
    }.apply {
        border = JBUI.Borders.empty(
            JBUI.scale(BUBBLE_V_PAD), JBUI.scale(BUBBLE_H_PAD),
            JBUI.scale(BUBBLE_V_PAD), JBUI.scale(BUBBLE_H_PAD)
        )
    }

    val row = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        if (rightAligned) {
            add(Box.createHorizontalGlue())
            add(bubble)
        } else {
            add(bubble)
            add(Box.createHorizontalGlue())
        }
        alignmentX = if (rightAligned) Component.RIGHT_ALIGNMENT else Component.LEFT_ALIGNMENT
    }

    return row to bubble
}
