package com.github.catatafishen.agentbridge.ui

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * A clickable chip that toggles the associated thinking content panel
 * between visible and hidden.
 *
 * Auto-collapses when [collapseWhenReady] is called, unless the user is
 * hovering on the chip — in which case the collapse is deferred until
 * the mouse leaves.
 */
class ThinkingChipComponent(
    private var active: Boolean,
    private val onToggle: () -> Unit,
) : JPanel() {

    private val emojiLabel: JLabel
    private val textLabel: JLabel
    private var hovered = false
    private var pendingCollapseAction: (() -> Unit)? = null

    init {
        layout = FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)
        isOpaque = false
        border = JBUI.Borders.empty(JBUI.scale(2), JBUI.scale(6), JBUI.scale(2), JBUI.scale(6))
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        emojiLabel = JLabel("💭").apply { font = UIUtil.getLabelFont() }
        textLabel = JLabel("Thought").apply {
            foreground = NativeChatColors.THINK
            font = UIUtil.getLabelFont().deriveFont(UIUtil.getLabelFont().size * 0.88f)
        }
        add(emojiLabel)
        add(textLabel)

        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onToggle()
            override fun mouseEntered(e: MouseEvent) { hovered = true }
            override fun mouseExited(e: MouseEvent) {
                hovered = false
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

    /**
     * Collapses the thinking content immediately if the chip is not hovered.
     * If hovered, defers the collapse until the mouse leaves the chip.
     */
    fun collapseWhenReady(action: () -> Unit) {
        if (hovered) {
            pendingCollapseAction = action
        } else {
            action()
        }
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val r = JBUI.scale(6)
        g2.color = NativeChatColors.THINK_BG
        g2.fillRoundRect(0, 0, width, height, r, r)
        g2.color = NativeChatColors.THINK_BORDER
        g2.drawRoundRect(0, 0, width - 1, height - 1, r, r)
        g2.dispose()
    }
}
