package com.github.catatafishen.agentbridge.ui

import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * A horizontally scrollable strip of tool chips with ‹/› navigation buttons.
 *
 * The optional thinking chip is pinned to the left (outside the scroll area).
 * Tool chips are inside a [JBScrollPane] that is never explicitly scrolled by the
 * user's scroll wheel — navigation is handled by the nav buttons and drag-to-scroll.
 *
 * **Drag-to-scroll**: the [dragListener] is added to each chip as well as the
 * viewport and inner panel, because mouse events on chip children don't propagate
 * to the parent panel. Drag coordinates are always converted to viewport space so
 * the delta is consistent regardless of which component initiated the drag.
 */
class ChipStripPanel : JPanel() {

    private val toolChipInner = object : JPanel() {
        init {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
        }
    }

    private val chipScrollPane = JBScrollPane(toolChipInner).apply {
        // AS_NEEDED lets the viewport allow toolChipInner to be wider than the visible area,
        // which is what makes overflow-and-scroll work. NEVER would constrain the view to the
        // viewport width and BoxLayout would squish chips. The scrollbar is hidden via size=0.
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
        horizontalScrollBar.apply {
            preferredSize = Dimension(0, 0)
            minimumSize = Dimension(0, 0)
            maximumSize = Dimension(Short.MAX_VALUE.toInt(), 0)
        }
        border = JBUI.Borders.empty()
        isOpaque = false
        viewport.isOpaque = false
        alignmentY = CENTER_ALIGNMENT
        minimumSize = Dimension(0, BaseChipComponent.CHIP_HEIGHT)
        maximumSize = Dimension(Short.MAX_VALUE.toInt(), BaseChipComponent.CHIP_HEIGHT)
    }

    private val leftBtn = createNavBtn("‹", -1)
    private val rightBtn = createNavBtn("›", +1)

    private var thinkingChip: JComponent? = null
    private var thinkingChipSpacer: JComponent? = null
    private val toolChips = mutableListOf<JComponent>()

    private val hbar get() = chipScrollPane.horizontalScrollBar

    private var dragStartX = 0
    private var dragScrollStart = 0

    /**
     * Drag-to-scroll handler. Registered on the viewport, [toolChipInner], and each
     * chip so that drags originating anywhere in the strip scroll correctly.
     *
     * Coordinates are converted to the viewport's coordinate space so that the delta
     * is consistent no matter which child component the drag started on.
     */
    private val dragListener = object : MouseAdapter() {
        override fun mousePressed(e: MouseEvent) {
            dragStartX = SwingUtilities.convertPoint(e.component, e.x, 0, chipScrollPane.viewport).x
            dragScrollStart = hbar.value
            chipScrollPane.viewport.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
        }

        override fun mouseReleased(e: MouseEvent) {
            chipScrollPane.viewport.cursor = Cursor.getDefaultCursor()
        }

        override fun mouseDragged(e: MouseEvent) {
            val viewX = SwingUtilities.convertPoint(e.component, e.x, 0, chipScrollPane.viewport).x
            val delta = dragStartX - viewX
            hbar.value = (dragScrollStart + delta).coerceIn(0, hbar.maximum - hbar.visibleAmount)
            updateNav()
        }
    }

    init {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty()

        add(leftBtn)
        add(chipScrollPane)
        add(rightBtn)

        hbar.addAdjustmentListener { updateNav() }

        chipScrollPane.viewport.addMouseListener(dragListener)
        chipScrollPane.viewport.addMouseMotionListener(dragListener)
        toolChipInner.addMouseListener(dragListener)
        toolChipInner.addMouseMotionListener(dragListener)

        // Call updateNav() when the viewport is first laid out or resized so that
        // the nav buttons appear correctly even before any scroll adjustment fires.
        chipScrollPane.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = updateNav()
        })
    }

    fun addThinkingChip(chip: JComponent) {
        thinkingChip?.let { remove(it) }
        thinkingChipSpacer?.let { remove(it) }
        thinkingChip = chip
        val spacer = Box.createRigidArea(Dimension(JBUI.scale(6), 0)) as JComponent
        thinkingChipSpacer = spacer
        chip.alignmentY = CENTER_ALIGNMENT
        // Thinking chip is pinned left, outside the scrollable area — no drag listener.
        add(chip, 0)
        add(spacer, 1)
        isVisible = true
        revalidate()
    }

    fun addToolChip(chip: JComponent) {
        if (toolChips.isNotEmpty()) {
            toolChipInner.add(Box.createRigidArea(Dimension(JBUI.scale(4), 0)))
        }
        chip.alignmentY = CENTER_ALIGNMENT
        // Add drag listener so drags on chips also scroll the strip.
        chip.addMouseListener(dragListener)
        chip.addMouseMotionListener(dragListener)
        toolChipInner.add(chip)
        toolChips += chip
        toolChipInner.revalidate()
        revalidate()
        isVisible = true
        scrollToEnd()
    }

    private fun scrollToEnd() {
        SwingUtilities.invokeLater {
            hbar.value = hbar.maximum
            updateNav()
        }
    }

    private fun updateNav() {
        val bar = hbar
        leftBtn.isVisible = bar.value > 1
        rightBtn.isVisible = bar.value + bar.visibleAmount < bar.maximum - 1
    }

    private fun createNavBtn(label: String, direction: Int): JButton {
        val size = JBUI.scale(18)
        return JButton(label).apply {
            isVisible = false
            isFocusPainted = false
            isContentAreaFilled = false
            isBorderPainted = false
            border = JBUI.Borders.empty()
            foreground = UIUtil.getContextHelpForeground()
            font = UIUtil.getLabelFont()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            alignmentY = CENTER_ALIGNMENT
            preferredSize = Dimension(size, size)
            minimumSize = Dimension(size, size)
            maximumSize = Dimension(size, size)
            addActionListener { scrollByChip(direction) }
        }
    }

    private fun scrollByChip(direction: Int) {
        val bar = hbar
        val viewportWidth = chipScrollPane.viewport.width
        if (direction > 0) {
            val scrollEnd = bar.value + viewportWidth
            val target = toolChips.firstOrNull { it.x + it.width > scrollEnd + 1 }
            bar.value = target?.x ?: bar.maximum
        } else {
            val target = toolChips.reversed().firstOrNull { it.x < bar.value - 1 }
            bar.value = if (target != null) maxOf(0, target.x + target.width - viewportWidth) else 0
        }
        updateNav()
    }

    override fun getMaximumSize(): Dimension = Dimension(Short.MAX_VALUE.toInt(), preferredSize.height)
}
