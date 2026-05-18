package com.github.catatafishen.agentbridge.ui

import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.ContainerAdapter
import java.awt.event.ContainerEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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
 * A layout row containing an aligned bubble and an optional hover-button strip.
 *
 * Call [addHoverButton] to register icon buttons that appear beside the bubble only while
 * the cursor is over the row. Supports Kotlin pair destructuring so all existing callers
 * continue to work without modification:
 * ```
 * val (row, bubble) = createBubble(bg, rightAligned = true)
 * ```
 */
class BubbleRow(
    val row: JPanel,
    val bubble: RoundedPanel,
    private val hoverButtonsPanel: JPanel,
) {
    operator fun component1() = row
    operator fun component2() = bubble

    private var hoverSetupDone = false

    /**
     * Adds a small icon button to the hover strip. The strip is shown while the
     * cursor is anywhere inside the row and hidden when it leaves.
     */
    fun addHoverButton(icon: Icon, tooltip: String, action: () -> Unit): BubbleRow {
        if (!hoverSetupDone) {
            hoverSetupDone = true
            setupHoverDetection()
        }
        val sz = Dimension(JBUI.scale(20), JBUI.scale(20))
        hoverButtonsPanel.add(JButton(icon).apply {
            toolTipText = tooltip
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            preferredSize = sz; minimumSize = sz; maximumSize = sz
            addActionListener { action() }
        })
        return this
    }

    private fun setupHoverDetection() {
        val listener = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                hoverButtonsPanel.isVisible = true
                row.revalidate()
                row.repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                try {
                    val rowLoc = row.locationOnScreen
                    val exitPt = e.locationOnScreen
                    if (!Rectangle(rowLoc.x, rowLoc.y, row.width, row.height).contains(exitPt)) {
                        hoverButtonsPanel.isVisible = false
                        row.revalidate()
                        row.repaint()
                    }
                } catch (_: Exception) {
                    // Component not yet on screen or being removed — ignore
                }
            }
        }
        addHoverListenersRecursively(row, listener)
    }

    private companion object {
        /**
         * Adds [listener] to [comp] and recursively to all its descendants.
         * Also installs a [ContainerAdapter] so future children are covered automatically.
         */
        fun addHoverListenersRecursively(comp: Component, listener: MouseAdapter) {
            comp.addMouseListener(listener)
            if (comp is Container) {
                comp.addContainerListener(object : ContainerAdapter() {
                    override fun componentAdded(e: ContainerEvent) =
                        addHoverListenersRecursively(e.child, listener)
                })
                comp.components.forEach { addHoverListenersRecursively(it, listener) }
            }
        }
    }
}

/**
 * Creates a width-capped rounded bubble and its alignment wrapper in one call.
 *
 * Returns a [BubbleRow] that supports:
 * - Kotlin destructuring: `val (row, bubble) = createBubble(...)`
 * - Hover button registration: `bubbleRow.addHoverButton(icon, tooltip) { ... }`
 *
 * `row` is the component to add to the parent container.
 * `bubble` is the [RoundedPanel] to add content to.
 */
fun createBubble(bg: Color, rightAligned: Boolean = false): BubbleRow {
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
        alignmentY = Component.TOP_ALIGNMENT
    }

    // Hover-button strip: invisible until the row is hovered. Positioned between the
    // glue and the bubble so it appears at the corner closest to the bubble's content.
    val hoverButtons = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        isVisible = false
        alignmentY = Component.TOP_ALIGNMENT
    }

    val row = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        if (rightAligned) {
            add(Box.createHorizontalGlue())
            add(hoverButtons)
            add(bubble)
        } else {
            add(bubble)
            add(hoverButtons)
            add(Box.createHorizontalGlue())
        }
        alignmentX = if (rightAligned) Component.RIGHT_ALIGNMENT else Component.LEFT_ALIGNMENT
    }

    return BubbleRow(row, bubble, hoverButtons)
}
