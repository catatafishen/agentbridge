package com.github.catatafishen.agentbridge.ui

import com.intellij.util.ui.JBUI
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.JLabel

/**
 * A chip representing the "Thinking…" / "Thought" state of an agent reasoning phase.
 *
 * Visually identical to a grey [ToolChipComponent] (same [BaseChipComponent] base),
 * using a 💭 emoji instead of a ring indicator. Clicking toggles the thinking bubble.
 * When [collapseWhenReady] is called, the collapse fires immediately if the mouse is
 * not hovering — otherwise it defers until mouse-out.
 */
class ThinkingChipComponent(
    private var active: Boolean,
    private val onToggle: () -> Unit,
) : BaseChipComponent(null) {

    private val emojiLabel: JLabel
    private val textLabel: JLabel
    private var pendingCollapseAction: (() -> Unit)? = null

    init {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        emojiLabel = JLabel("💭").apply {
            font = chipFont()
            alignmentY = CENTER_ALIGNMENT
        }
        textLabel = JLabel(if (active) "Thinking…" else "Thought").apply {
            foreground = kindCol
            font = chipFont()
            alignmentY = CENTER_ALIGNMENT
        }
        add(emojiLabel)
        add(Box.createRigidArea(Dimension(JBUI.scale(4), 0)))
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
}
