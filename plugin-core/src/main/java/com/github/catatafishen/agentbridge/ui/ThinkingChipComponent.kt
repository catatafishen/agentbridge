package com.github.catatafishen.agentbridge.ui

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JLabel
import javax.swing.JPanel

class ThinkingChipComponent(
    private var active: Boolean,
    private val onToggle: () -> Unit,
) : JPanel() {

    private val emojiLabel: JLabel
    private val textLabel: JLabel
    private var hovered = false
    private var pendingCollapseAction: (() -> Unit)? = null

    private val chipBg get() = NativeChatColors.kindBg(null)
    private val chipBorder get() = NativeChatColors.kindBorder(null)
    private val chipFg get() = NativeChatColors.kindColor(null)

    init {
        layout = FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)
        isOpaque = false
        border = JBUI.Borders.empty(JBUI.scale(2), JBUI.scale(6), JBUI.scale(2), JBUI.scale(6))
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        emojiLabel = JLabel("💭").apply { font = UIUtil.getLabelFont() }
        textLabel = JLabel(if (active) "Thinking…" else "Thought").apply {
            foreground = chipFg
            font = UIUtil.getLabelFont().deriveFont(UIUtil.getLabelFont().size * 0.88f)
        }
        add(emojiLabel)
        add(textLabel)

        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onToggle()
            override fun mouseEntered(e: MouseEvent) {
                hovered = true
                repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                hovered = false
                repaint()
                pendingCollapseAction?.let { action ->
                    pendingCollapseAction = null
                    action()
                }
            }
        })
    }

    fun setActive(isActive: Boolean) {
        active = isActive
        textLabel.text = if (isActive) "Thinking…" else "Thought"
        repaint()
    }

    fun collapseWhenReady(action: () -> Unit) {
        if (hovered) {
            pendingCollapseAction = action
        } else {
            action()
        }
    }

    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val r = JBUI.scale(6)
        g2.color = chipBg
        g2.fillRoundRect(0, 0, width, height, r, r)
        g2.color = chipBorder
        g2.drawRoundRect(0, 0, width - 1, height - 1, r, r)
        g2.dispose()
    }
}
