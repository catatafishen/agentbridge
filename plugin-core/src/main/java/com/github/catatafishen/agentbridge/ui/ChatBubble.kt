package com.github.catatafishen.agentbridge.ui

import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.ContainerAdapter
import java.awt.event.ContainerEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Path2D
import javax.swing.*

internal const val BUBBLE_V_PAD = 8
private const val BUBBLE_H_PAD = 14
private const val MAX_BUBBLE_WIDTH_FRACTION = 0.94

/**
 * Which corner of the bubble to leave unrounded (square).
 * Used to "anchor" the bubble to its side: left-aligned bubbles square their top-left corner,
 * right-aligned bubbles square their bottom-right corner.
 */
enum class BubbleCorner { NONE, TOP_LEFT, BOTTOM_LEFT, BOTTOM_RIGHT }

/**
 * A JPanel that paints a rounded rectangle background with optional per-corner squaring and border.
 *
 * - [squaredCorner] leaves one corner sharp so the bubble appears anchored to its alignment side.
 * - [borderColor] draws a 1px border when non-null, matching the style of tool chip borders.
 */
open class RoundedPanel(
    private val bgColor: Color,
    private val borderColor: Color? = null,
    private val radius: Int = JBUI.scale(10),
    private val squaredCorner: BubbleCorner = BubbleCorner.NONE,
) : JPanel(BorderLayout()) {

    init {
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val r = radius.toFloat()
        g2.color = bgColor
        g2.fill(cornerPath(0f, 0f, width.toFloat(), height.toFloat(), r, squaredCorner))
        borderColor?.let {
            g2.color = it
            g2.draw(cornerPath(0.5f, 0.5f, width - 1f, height - 1f, r, squaredCorner))
        }
        g2.dispose()
    }

    private companion object {
        /**
         * Builds a Path2D for a rounded rectangle where one optional corner is squared
         * (per [squaredCorner]) and all others are rounded with [radius].
         */
        fun cornerPath(
            x: Float, y: Float, w: Float, h: Float,
            radius: Float, squaredCorner: BubbleCorner,
        ): Path2D.Float {
            val tlR = if (squaredCorner == BubbleCorner.TOP_LEFT) 0f else radius
            val brR = if (squaredCorner == BubbleCorner.BOTTOM_RIGHT) 0f else radius
            val blR = if (squaredCorner == BubbleCorner.BOTTOM_LEFT) 0f else radius
            val p = Path2D.Float()
            p.moveTo(x + tlR, y)
            p.lineTo(x + w - radius, y)
            p.quadTo(x + w, y, x + w, y + radius)
            p.lineTo(x + w, y + h - brR)
            if (brR > 0f) p.quadTo(x + w, y + h, x + w - brR, y + h)
            p.lineTo(x + blR, y + h)
            if (blR > 0f) p.quadTo(x, y + h, x, y + h - blR)
            p.lineTo(x, y + tlR)
            if (tlR > 0f) p.quadTo(x, y, x + tlR, y)
            p.closePath()
            return p
        }
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
 * - One corner on the alignment side is squared to visually anchor the bubble.
 *   Left-aligned → top-left squared; right-aligned → bottom-right squared.
 * - A subtle border is drawn when the background maps to a known border color
 *   (see [NativeChatColors.bubbleBorder]).
 *
 * Returns a [BubbleRow] that supports:
 * - Kotlin destructuring: `val (row, bubble) = createBubble(...)`
 * - Hover button registration: `bubbleRow.addHoverButton(icon, tooltip) { ... }`
 *
 * `row` is the component to add to the parent container.
 * `bubble` is the [RoundedPanel] to add content to.
 */
fun createBubble(
    bg: Color,
    rightAligned: Boolean = false,
    explicitBorder: Color? = null,
    noBorder: Boolean = false
): BubbleRow {
    val borderColor = if (noBorder) null else (explicitBorder ?: NativeChatColors.bubbleBorder(bg))
    val squaredCorner = if (rightAligned) BubbleCorner.BOTTOM_RIGHT else BubbleCorner.TOP_LEFT

    val bubble = object : RoundedPanel(bg, borderColor, JBUI.scale(10), squaredCorner) {
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
