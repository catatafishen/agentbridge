package com.github.catatafishen.agentbridge.ui

import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*

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
            // parent (the row) has width=0 before the first layout pass completes.
            // Walk up the hierarchy to find the nearest laid-out ancestor so the
            // bubble gets its correct max width on the very first measurement.
            var containerWidth = parent?.width ?: 0
            if (containerWidth == 0) {
                var anc: Container? = parent?.parent
                while (anc != null && containerWidth == 0) {
                    containerWidth = anc.width
                    anc = anc.parent
                }
            }
            if (containerWidth == 0) containerWidth = JBUI.scale(600)
            return Dimension(
                (containerWidth * MAX_BUBBLE_WIDTH_FRACTION).toInt().coerceAtLeast(JBUI.scale(200)),
                Int.MAX_VALUE
            )
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
