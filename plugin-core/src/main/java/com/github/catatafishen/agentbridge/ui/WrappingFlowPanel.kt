package com.github.catatafishen.agentbridge.ui

import java.awt.*
import javax.swing.*

/**
 * A flow-based panel that computes preferred height for the actual number
 * of wrapped rows, so the parent BoxLayout allocates correct vertical space.
 */
class WrappingFlowPanel(hgap: Int, vgap: Int) : JPanel(FlowLayout(FlowLayout.LEFT, hgap, vgap)) {

    init {
        isOpaque = false
    }

    override fun getPreferredSize(): Dimension {
        val maxWidth = parent?.width?.takeIf { it > 0 } ?: return super.getPreferredSize()
        val fl = layout as FlowLayout
        val insets = this.insets
        var x = insets.left
        var y = insets.top
        var rowHeight = 0

        for (comp in components) {
            if (!comp.isVisible) continue
            val d = comp.preferredSize
            if (x + d.width > maxWidth - insets.right && x > insets.left) {
                y += rowHeight + fl.vgap
                x = insets.left
                rowHeight = 0
            }
            x += d.width + fl.hgap
            rowHeight = maxOf(rowHeight, d.height)
        }
        y += rowHeight + insets.bottom
        return Dimension(maxWidth, y)
    }

    override fun getMaximumSize(): Dimension =
        Dimension(Short.MAX_VALUE.toInt(), preferredSize.height)
}
