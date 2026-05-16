package com.github.catatafishen.agentbridge.ui

import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.JComponent
import javax.swing.JLabel

/**
 * A compact tool chip with a spinning ring indicator and kind-based coloring.
 * Matches the JCEF `<tool-chip>` component.
 *
 * Extends [BaseChipComponent] for consistent sizing and painting with [ThinkingChipComponent].
 * Uses [BoxLayout.X_AXIS] so the chip never wraps regardless of panel width.
 */
class ToolChipComponent(
    title: String,
    kind: String?,
    status: String,
    private val onClick: (() -> Unit)? = null,
) : BaseChipComponent(kind) {

    private var currentStatus = status
    private var spinAngle = 0
    private val ringSize = JBUI.scale(8)
    private val ring = RingIndicator()
    private val label = JLabel(truncateLabel(title)).apply {
        foreground = kindCol
        font = chipFont()
        alignmentY = CENTER_ALIGNMENT
        putClientProperty("html.disable", true)
    }

    init {
        if (onClick != null) cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        add(ring)
        add(Box.createRigidArea(Dimension(JBUI.scale(4), 0)))
        add(label)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                onClick?.invoke()
            }

            override fun mouseEntered(e: MouseEvent) {
                hovered = true; repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                hovered = false; repaint()
            }
        })
    }

    fun isSpinning(): Boolean = currentStatus.lowercase() in listOf("running", "pending")

    fun updateStatus(status: String) {
        currentStatus = status
        repaint()
    }

    fun advanceSpin() {
        spinAngle = (spinAngle + 15) % 360
        repaint()
    }

    private inner class RingIndicator : JComponent() {
        init {
            val s = ringSize
            preferredSize = Dimension(s, s)
            minimumSize = Dimension(s, s)
            maximumSize = Dimension(s, s)
            alignmentY = CENTER_ALIGNMENT
            isOpaque = false
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val s = ringSize - 2
            when (currentStatus.lowercase()) {
                "running", "pending" -> {
                    g2.color = kindCol
                    g2.stroke = BasicStroke(1.5f)
                    g2.drawArc(1, 1, s, s, spinAngle, 270)
                }

                "complete", "completed", "success", "done" -> {
                    g2.color = kindCol
                    g2.fillOval(1, 1, s, s)
                }

                else -> {
                    g2.color = NativeChatColors.ERROR
                    g2.stroke = BasicStroke(1.5f)
                    g2.drawArc(1, 1, s, s, 0, 270)
                }
            }
            g2.dispose()
        }
    }

    companion object {
        private fun truncateLabel(text: String, max: Int = 50): String =
            if (text.length > max) text.take(max - 1) + "…" else text
    }
}
