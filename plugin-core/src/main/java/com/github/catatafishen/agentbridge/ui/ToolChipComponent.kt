package com.github.catatafishen.agentbridge.ui

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * A compact tool chip with a spinning ring indicator and kind-based coloring.
 * Matches the JCEF `<tool-chip>` component.
 */
class ToolChipComponent(
    title: String,
    kind: String?,
    status: String,
    private val onClick: (() -> Unit)? = null,
) : JPanel() {

    private var currentStatus = status
    private var spinAngle = 0
    private val ringSize = JBUI.scale(8)
    private val kindCol: Color = NativeChatColors.kindColor(kind)
    private val bgCol: Color = NativeChatColors.kindBg(kind)
    private val borderCol: Color = NativeChatColors.kindBorder(kind)
    private val hoverBgCol: Color = NativeChatColors.kindBgHover(kind)
    private val hoverBorderCol: Color = NativeChatColors.kindBorderHover(kind)
    private var hovered = false

    init {
        layout = FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)
        isOpaque = false
        border = JBUI.Borders.empty(JBUI.scale(2), JBUI.scale(6), JBUI.scale(2), JBUI.scale(6))

        if (onClick != null) cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        add(RingIndicator())
        add(JLabel(truncateLabel(title)).apply {
            foreground = kindCol
            font = UIUtil.getLabelFont().deriveFont(UIUtil.getLabelFont().size * 0.88f)
            putClientProperty("html.disable", true)
        })

        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { onClick?.invoke() }
            override fun mouseEntered(e: MouseEvent) { hovered = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hovered = false; repaint() }
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

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val r = JBUI.scale(6)
        g2.color = if (hovered) hoverBgCol else bgCol
        g2.fillRoundRect(0, 0, width, height, r, r)
        g2.color = if (hovered) hoverBorderCol else borderCol
        g2.drawRoundRect(0, 0, width - 1, height - 1, r, r)
        g2.dispose()
    }

    private inner class RingIndicator : JComponent() {
        init {
            preferredSize = Dimension(ringSize, ringSize)
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
