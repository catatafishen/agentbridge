package com.github.catatafishen.agentbridge.ui

import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Point
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import kotlin.math.max

/**
 * Horizontal clipping container that keeps the trailing controls visible when space is tight.
 */
class RightAnchoredFooterScrollPane(view: Component) : JBScrollPane(
    view,
    VERTICAL_SCROLLBAR_NEVER,
    HORIZONTAL_SCROLLBAR_NEVER,
) {

    init {
        isOpaque = false
        border = JBUI.Borders.empty()
        viewport.isOpaque = false
        val listener = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                scrollToRight()
            }
        }
        viewport.addComponentListener(listener)
        view.addComponentListener(listener)
    }

    override fun doLayout() {
        super.doLayout()
        scrollToRight()
    }

    private fun scrollToRight() {
        val view = viewport.view ?: return
        val maxX = max(0, view.width - viewport.extentSize.width)
        val current = viewport.viewPosition
        if (current.x != maxX) {
            viewport.viewPosition = Point(maxX, current.y)
        }
    }
}
