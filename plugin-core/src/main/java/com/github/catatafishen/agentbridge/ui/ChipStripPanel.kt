package com.github.catatafishen.agentbridge.ui

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import javax.swing.*

/**
 * A horizontally scrollable strip of tool chips with left/right navigation buttons.
 *
 * Layout: [ThinkingChip?] [‹] [chip1  chip2  chip3 ...] [›]
 *
 * - The thinking chip (if any) is pinned on the left, always visible outside the scroll area.
 * - Tool chips go inside the scrollable area; the last added chip is auto-scrolled into view.
 * - Nav buttons (‹/›) appear only when chips overflow the available width.
 *
 * Mirrors the JCEF `message-meta` / `.chip-strip` / `.chip-nav` layout.
 */
class ChipStripPanel : JPanel() {

    private val toolChipInner = object : JPanel() {
        init {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
        }
    }

    private val chipScrollPane = JScrollPane(toolChipInner).apply {
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

    /** Ordered list of tool chip components (excludes BoxLayout spacers). */
    private val toolChips = mutableListOf<JComponent>()

    private val hbar get() = chipScrollPane.horizontalScrollBar

    init {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(JBUI.scale(3), 0)
        add(leftBtn)
        add(chipScrollPane)
        add(rightBtn)
        hbar.addAdjustmentListener { updateNav() }
    }

    /** Pins a thinking chip on the left, before the scroll area. At most one at a time. */
    fun addThinkingChip(chip: JComponent) {
        thinkingChip?.let { remove(it) }
        thinkingChip = chip
        chip.alignmentY = Component.CENTER_ALIGNMENT
        add(chip, 0)
        isVisible = true
        revalidate()
    }

    /** Adds a tool chip into the scrollable area and auto-scrolls to make it visible. */
    fun addToolChip(chip: JComponent) {
        if (toolChips.isNotEmpty()) {
            toolChipInner.add(Box.createRigidArea(Dimension(JBUI.scale(4), 0)))
        }
        chip.alignmentY = Component.CENTER_ALIGNMENT
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
        return JButton(label).apply {
            isVisible = false
            isFocusPainted = false
            isContentAreaFilled = false
            isBorderPainted = false
            border = JBUI.Borders.empty(0, JBUI.scale(2))
            foreground = UIUtil.getContextHelpForeground()
            font = UIUtil.getLabelFont()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            alignmentY = Component.CENTER_ALIGNMENT
            addActionListener { scrollByChip(direction) }
        }
    }

    private fun scrollByChip(direction: Int) {
        val bar = hbar
        val viewportWidth = chipScrollPane.viewport.width
        if (direction > 0) {
            val scrollEnd = bar.value + viewportWidth
            val target = toolChips.firstOrNull { it.x + it.width > scrollEnd + 1 }
            bar.value = if (target != null) target.x else bar.maximum
        } else {
            val target = toolChips.reversed().firstOrNull { it.x < bar.value - 1 }
            bar.value = if (target != null) maxOf(0, target.x + target.width - viewportWidth) else 0
        }
        updateNav()
    }

    override fun getMaximumSize(): Dimension = Dimension(Short.MAX_VALUE.toInt(), preferredSize.height)
}
