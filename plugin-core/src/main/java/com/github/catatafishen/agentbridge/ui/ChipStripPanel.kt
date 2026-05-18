package com.github.catatafishen.agentbridge.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * A two-row strip holding tool/thinking chips and an optional collapsible thought bubble.
 *
 * Layout (BoxLayout.Y_AXIS):
 * - [chipRow]: horizontal row with the optional thinking chip pinned left, then ‹/› nav
 *   buttons flanking a scrollable area of tool chips.
 * - [thinkingBubble]: optional thought bubble below [chipRow]. When hidden
 *   ([isVisible] = false), it takes **zero** space — no border, no margin.
 *
 * **Drag-to-scroll**: the [dragListener] is added to each chip as well as the
 * viewport and inner panel, because mouse events on chip children don't propagate
 * to the parent panel. Drag coordinates are always converted to viewport space so
 * the delta is consistent regardless of which component initiated the drag.
 */
class ChipStripPanel : JPanel() {

    /** Single source of truth for the row height — nav buttons and scroll pane all use this. */
    private val rowHeight get() = BaseChipComponent.CHIP_HEIGHT

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
        minimumSize = Dimension(0, rowHeight)
        maximumSize = Dimension(Short.MAX_VALUE.toInt(), rowHeight)
    }

    private val leftBtn = createNavBtn("‹", -1)
    private val rightBtn = createNavBtn("›", +1)

    /**
     * Wraps [leftBtn], [chipScrollPane], and [rightBtn] together so that the nav
     * buttons always flank the scrollable area and cannot overlap the thinking chip.
     */
    private val scrollSection = object : JPanel() {
        init {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentY = CENTER_ALIGNMENT
        }

        override fun getMinimumSize(): Dimension = Dimension(0, rowHeight)
        override fun getMaximumSize(): Dimension = Dimension(Short.MAX_VALUE.toInt(), rowHeight)
    }

    /**
     * Horizontal chip row: optional thinking chip pinned left + [scrollSection].
     * This is the first (top) row in the Y_AXIS layout of [ChipStripPanel].
     */
    private val chipRow = object : JPanel() {
        init {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
        }

        override fun getMinimumSize(): Dimension = Dimension(0, rowHeight)
        override fun getMaximumSize(): Dimension = Dimension(Short.MAX_VALUE.toInt(), rowHeight)
        override fun getPreferredSize(): Dimension = Dimension(super.getPreferredSize().width, rowHeight)
    }

    private var thinkingChip: JComponent? = null
    private var thinkingChipSpacer: JComponent? = null
    private val toolChips = mutableListOf<JComponent>()

    /** Optional thought bubble below [chipRow]. Null until [setThinkingBubble] is called. */
    private var thinkingBubble: JPanel? = null

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
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty()

        scrollSection.add(leftBtn)
        scrollSection.add(chipScrollPane)
        scrollSection.add(rightBtn)

        chipRow.add(scrollSection)
        add(chipRow)

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
        thinkingChip?.let { chipRow.remove(it) }
        thinkingChipSpacer?.let { chipRow.remove(it) }
        thinkingChip = chip
        val spacer = Box.createRigidArea(Dimension(JBUI.scale(6), 0)) as JComponent
        thinkingChipSpacer = spacer
        chip.alignmentY = CENTER_ALIGNMENT
        // Thinking chip pinned left, outside the scrollable area — no drag listener.
        // Inserted before scrollSection so nav buttons always flank the scroll area.
        chipRow.add(chip, 0)
        chipRow.add(spacer, 1)
        isVisible = true
        revalidate()
    }

    /**
     * Sets the thought bubble shown below [chipRow].
     *
     * The bubble starts **hidden** — call [showThinkingBubble] to display it when
     * thinking begins streaming. When hidden, it contributes zero height and zero
     * spacing to the layout (BoxLayout skips invisible components entirely).
     */
    fun setThinkingBubble(bubble: JPanel) {
        thinkingBubble?.let { remove(it) }
        thinkingBubble = bubble
        bubble.isVisible = false
        bubble.alignmentX = LEFT_ALIGNMENT
        add(bubble)
        revalidate()
    }

    fun showThinkingBubble() {
        thinkingBubble?.let {
            it.isVisible = true
            revalidate()
            repaint()
        }
    }

    fun hideThinkingBubble() {
        thinkingBubble?.let {
            it.isVisible = false
            revalidate()
            repaint()
        }
    }

    fun toggleThinkingBubble() {
        val bubble = thinkingBubble ?: return
        bubble.isVisible = !bubble.isVisible
        if (bubble.isVisible) bubble.revalidate()
        revalidate()
        repaint()
    }

    fun isThinkingBubbleVisible(): Boolean = thinkingBubble?.isVisible ?: false

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
        val btnWidth = JBUI.scale(18)
        val bg = JBColor(Color(130, 130, 130, 30), Color(200, 200, 200, 30))
        val bgHover = JBColor(Color(130, 130, 130, 60), Color(200, 200, 200, 60))
        val borderCol = JBColor(Color(130, 130, 130, 80), Color(200, 200, 200, 80))
        return object : JButton(label) {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = if (model.isRollover) bgHover else bg
                val r = JBUI.scale(4)
                g2.fillRoundRect(0, 0, width, height, r, r)
                g2.color = borderCol
                g2.drawRoundRect(0, 0, width - 1, height - 1, r, r)
                g2.dispose()
                super.paintComponent(g)
            }
        }.apply {
            isVisible = false
            isFocusPainted = false
            isContentAreaFilled = false
            isBorderPainted = false
            border = JBUI.Borders.empty()
            foreground = UIUtil.getLabelForeground()
            font = UIUtil.getLabelFont().deriveFont(JBUI.scaleFontSize(14f)).deriveFont(Font.BOLD)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            alignmentY = CENTER_ALIGNMENT
            preferredSize = Dimension(btnWidth, rowHeight)
            minimumSize = Dimension(btnWidth, rowHeight)
            maximumSize = Dimension(btnWidth, rowHeight)
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
