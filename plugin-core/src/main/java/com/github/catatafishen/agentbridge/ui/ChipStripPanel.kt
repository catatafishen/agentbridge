package com.github.catatafishen.agentbridge.ui

import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

class ChipStripPanel : JPanel() {

    private val toolChipInner = object : JPanel() {
        init {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
        }
    }

    private val chipScrollPane = JBScrollPane(toolChipInner).apply {
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
        border = JBUI.Borders.empty()
        isOpaque = false
        viewport.isOpaque = false
        minimumSize = Dimension(0, JBUI.scale(22))
    }

    private val leftBtn = createNavBtn("‹", -1)
    private val rightBtn = createNavBtn("›", +1)

    private var thinkingChip: JComponent? = null
    private val toolChips = mutableListOf<JComponent>()

    private val hbar get() = chipScrollPane.horizontalScrollBar

    private var dragStartX = 0
    private var dragScrollStart = 0

    init {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(JBUI.scale(2), 0)
        add(leftBtn)
        add(chipScrollPane)
        add(rightBtn)
        hbar.addAdjustmentListener { updateNav() }

        val dragListener = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                dragStartX = e.x
                dragScrollStart = hbar.value
                chipScrollPane.viewport.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
            }

            override fun mouseReleased(e: MouseEvent) {
                chipScrollPane.viewport.cursor = Cursor.getDefaultCursor()
            }

            override fun mouseDragged(e: MouseEvent) {
                val delta = dragStartX - e.x
                hbar.value = (dragScrollStart + delta).coerceIn(0, hbar.maximum - hbar.visibleAmount)
                updateNav()
            }
        }
        chipScrollPane.viewport.addMouseListener(dragListener)
        chipScrollPane.viewport.addMouseMotionListener(dragListener)
        toolChipInner.addMouseListener(dragListener)
        toolChipInner.addMouseMotionListener(dragListener)
    }

    fun addThinkingChip(chip: JComponent) {
        thinkingChip?.let { remove(it) }
        thinkingChip = chip
        chip.alignmentY = CENTER_ALIGNMENT
        add(chip, 0)
        isVisible = true
        revalidate()
    }

    fun addToolChip(chip: JComponent) {
        if (toolChips.isNotEmpty()) {
            toolChipInner.add(Box.createRigidArea(Dimension(JBUI.scale(4), 0)))
        }
        chip.alignmentY = CENTER_ALIGNMENT
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
