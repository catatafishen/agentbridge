package com.github.catatafishen.agentbridge.ui

import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.JLabel
import javax.swing.Timer
import kotlin.math.sin

class ThinkingChipComponent(
    private var active: Boolean,
    private val onToggle: () -> Unit,
) : BaseChipComponent(null) {

    private val emojiLabel: JLabel
    private val textLabel: JLabel
    private var pendingCollapseAction: (() -> Unit)? = null

    /** Phase in radians, advanced by the pulse timer each tick (~30 fps). */
    private var pulsePhase = 0.0

    /**
     * Pulses only the 💭 emoji alpha between ~35% and 100% using a sine wave.
     * The chip background stays static; only the icon breathes to signal activity.
     * Runs only while [active]; stopped and reset in [setActive].
     */
    private val pulseTimer = Timer(33) {
        pulsePhase = (pulsePhase + 0.08) % (2 * Math.PI)
        repaint()
    }.also { it.isCoalesce = true }

    init {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        emojiLabel = object : JLabel("💭") {
            override fun paintComponent(g: Graphics) {
                if (!active) {
                    super.paintComponent(g)
                    return
                }
                val g2 = g.create() as Graphics2D
                val t = (sin(pulsePhase) + 1.0) / 2.0
                val alpha = (0.35f + t.toFloat() * 0.65f).coerceIn(0f, 1f)
                g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
                super.paintComponent(g2)
                g2.dispose()
            }
        }.apply {
            applyChatFont(-2)
            alignmentY = CENTER_ALIGNMENT
        }
        textLabel = JLabel(if (active) "Thinking…" else "Thought").apply {
            foreground = kindCol
            applyChatFont(-2)
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

        if (active) pulseTimer.start()
    }

    fun setActive(isActive: Boolean) {
        active = isActive
        textLabel.text = if (isActive) "Thinking…" else "Thought"
        if (isActive) {
            pulsePhase = 0.0
            pulseTimer.start()
        } else {
            pulseTimer.stop()
        }
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
