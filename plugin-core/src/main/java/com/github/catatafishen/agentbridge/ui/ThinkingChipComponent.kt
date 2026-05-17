package com.github.catatafishen.agentbridge.ui

import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.JLabel
import javax.swing.Timer
import kotlin.math.sin

/**
 * A chip representing the "Thinking…" / "Thought" state of an agent reasoning phase.
 *
 * Visually identical to a grey [ToolChipComponent] (same [BaseChipComponent] base),
 * using a 💭 emoji instead of a ring indicator. Clicking toggles the thinking bubble.
 * When [collapseWhenReady] is called, the collapse fires immediately if the mouse is
 * not hovering — otherwise it defers until mouse-out.
 *
 * While [active], the chip background pulses gently (sine-wave alpha on the fill) to
 * signal ongoing thinking. The pulse stops as soon as [setActive] is called with false.
 */
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
     * Pulses the chip background alpha between ~40% and 100% using a sine wave.
     * Runs only while [active]; stopped and reset in [setActive].
     */
    private val pulseTimer = Timer(33) {
        pulsePhase = (pulsePhase + 0.08) % (2 * Math.PI)
        repaint()
    }.also { it.isCoalesce = true }

    init {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        emojiLabel = JLabel("💭").apply {
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

    /**
     * Overrides [BaseChipComponent.paintComponent] to add a pulsing background fill
     * when [active]. The alpha is modulated by a sine wave so it breathes between
     * ~40% and 100% opacity without an abrupt start/stop.
     */
    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val r = JBUI.scale(6)
        val bg = if (hovered) hoverBgCol else bgCol
        if (active) {
            val t = ((sin(pulsePhase) + 1.0) / 2.0)  // 0.0 → 1.0
            val alpha = (100 + (t * 155).toInt()).coerceIn(0, 255)
            // bg is a resolved theme color; Color(r,g,b,a) is the only way to add alpha
            // to an already-resolved color — JBColor has no "with alpha" constructor.
            @Suppress("UseJBColor")
            val alphaFill = Color(bg.red, bg.green, bg.blue, alpha)
            g2.color = alphaFill
        } else {
            g2.color = bg
        }
        g2.fillRoundRect(0, 0, width, height, r, r)
        g2.color = if (hovered) hoverBorderCol else borderCol
        g2.drawRoundRect(0, 0, width - 1, height - 1, r, r)
        g2.dispose()
    }
}
