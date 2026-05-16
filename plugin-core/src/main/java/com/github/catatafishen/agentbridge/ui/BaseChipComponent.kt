package com.github.catatafishen.agentbridge.ui

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import javax.swing.BoxLayout
import javax.swing.JPanel

/**
 * Shared base for [ToolChipComponent] and [ThinkingChipComponent].
 *
 * Provides a horizontal no-wrap [BoxLayout] container with a fixed chip height,
 * kind-derived colors, rounded-rect painting with hover highlight, and consistent
 * sizing overrides so both chip types render identically in height.
 */
abstract class BaseChipComponent(kind: String?) : JPanel() {

    protected val kindCol: Color = NativeChatColors.kindColor(kind)
    protected val bgCol: Color = NativeChatColors.kindBg(kind)
    protected val borderCol: Color = NativeChatColors.kindBorder(kind)
    protected val hoverBgCol: Color = NativeChatColors.kindBgHover(kind)
    protected val hoverBorderCol: Color = NativeChatColors.kindBorderHover(kind)

    /** Toggled by the subclass's hover mouse listener; drives [paintComponent]. */
    var hovered = false

    companion object {
        /** Fixed chip height — identical for all chip types. DPI-aware. */
        val CHIP_HEIGHT: Int get() = JBUI.scale(22)

        /** Shared font for chip labels: 88% of the IDE label font. */
        fun chipFont(): Font = UIUtil.getLabelFont().deriveFont(UIUtil.getLabelFont().size * 0.88f)
    }

    init {
        // Horizontal, no-wrap layout. Children must set alignmentY = CENTER_ALIGNMENT.
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        alignmentY = CENTER_ALIGNMENT
        // Horizontal padding only; vertical size is controlled by height overrides below.
        border = JBUI.Borders.empty(0, JBUI.scale(6), 0, JBUI.scale(6))
    }

    override fun getPreferredSize(): Dimension = Dimension(super.getPreferredSize().width, CHIP_HEIGHT)
    override fun getMinimumSize(): Dimension = Dimension(0, CHIP_HEIGHT)
    override fun getMaximumSize(): Dimension = Dimension(preferredSize.width, CHIP_HEIGHT)

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val r = JBUI.scale(6)
        g2.color = if (hovered) hoverBgCol else bgCol
        g2.fillRoundRect(0, 0, width, height, r, r)
        g2.color = if (hovered) hoverBorderCol else borderCol
        g2.drawRoundRect(0, 0, width - 1, height - 1, r, r)
        g2.dispose()
    }
}
