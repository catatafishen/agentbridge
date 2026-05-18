package com.github.catatafishen.agentbridge.ui

import com.intellij.icons.AllIcons
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.Icon
import javax.swing.JLabel

/**
 * A compact tool chip with a status indicator and kind-based coloring.
 * Matches the JCEF `<tool-chip>` component.
 *
 * Extends [BaseChipComponent] for consistent sizing and painting with [ThinkingChipComponent].
 * Layout is horizontal (X_AXIS) so the chip never wraps regardless of panel width.
 *
 * Status indicator icons:
 * - **running/pending**: IntelliJ built-in spinner frames (`AllIcons.Process.Step_1..8`)
 * - **complete/success/done**: filled circle (agentbridge tools) or circle outline (other tools)
 * - **else (failed/denied)**: filled broken arc — 270° sector (agentbridge) or outline arc (other)
 */
class ToolChipComponent(
    title: String,
    kind: String?,
    status: String,
    isMcpHandled: Boolean = false,
    private val onClick: (() -> Unit)? = null,
) : BaseChipComponent(kind) {

    private var currentStatus = status
    private var spinFrame = 0

    private val spinIcons = arrayOf(
        AllIcons.Process.Step_1, AllIcons.Process.Step_2, AllIcons.Process.Step_3,
        AllIcons.Process.Step_4, AllIcons.Process.Step_5, AllIcons.Process.Step_6,
        AllIcons.Process.Step_7, AllIcons.Process.Step_8,
    )

    // Icon size matches the AllIcons.Process spinner frames (16 logical px).
    private val iconSize = AllIcons.Process.Step_1.iconWidth

    /** Filled circle using the chip's kind color — "success" state for agentbridge tools. */
    private inner class FilledCircleIcon : Icon {
        override fun getIconWidth() = iconSize
        override fun getIconHeight() = iconSize
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = kindCol
            val pad = 3
            g2.fillOval(x + pad, y + pad, iconSize - 2 * pad, iconSize - 2 * pad)
            g2.dispose()
        }
    }

    /** Circle outline using the chip's kind color — "success" state for other tools. */
    private inner class OutlineCircleIcon : Icon {
        override fun getIconWidth() = iconSize
        override fun getIconHeight() = iconSize
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = kindCol
            g2.stroke = BasicStroke(1.5f)
            val pad = 3
            g2.drawOval(x + pad, y + pad, iconSize - 2 * pad - 1, iconSize - 2 * pad - 1)
            g2.dispose()
        }
    }

    /** 270° filled arc (pie sector) — "failed" state for agentbridge tools. */
    private inner class FilledArcIcon : Icon {
        override fun getIconWidth() = iconSize
        override fun getIconHeight() = iconSize
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = kindCol
            val pad = 3
            g2.fillArc(x + pad, y + pad, iconSize - 2 * pad, iconSize - 2 * pad, 0, 270)
            g2.dispose()
        }
    }

    /** 270° outline arc — "failed" state for other tools. */
    private inner class OutlineArcIcon : Icon {
        override fun getIconWidth() = iconSize
        override fun getIconHeight() = iconSize
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = kindCol
            g2.stroke = BasicStroke(1.5f)
            val pad = 3
            g2.drawArc(x + pad, y + pad, iconSize - 2 * pad - 1, iconSize - 2 * pad - 1, 0, 270)
            g2.dispose()
        }
    }

    private var successIcon: Icon = if (isMcpHandled) FilledCircleIcon() else OutlineCircleIcon()
    private var errorIcon: Icon = if (isMcpHandled) FilledArcIcon() else OutlineArcIcon()

    private val statusLabel = JLabel(currentStatusIcon()).apply {
        alignmentY = CENTER_ALIGNMENT
        preferredSize = Dimension(iconSize, iconSize)
        minimumSize = Dimension(iconSize, iconSize)
        maximumSize = Dimension(iconSize, iconSize)
    }

    private val label = JLabel(truncateLabel(title)).apply {
        foreground = kindCol
        applyChatFont(-2)
        alignmentY = CENTER_ALIGNMENT
        putClientProperty("html.disable", true)
    }

    init {
        if (onClick != null) cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        add(statusLabel)
        add(Box.createRigidArea(Dimension(JBUI.scale(4), 0)))
        add(label)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                onClick?.invoke()
            }

            override fun mouseEntered(e: MouseEvent) {
                hovered = true; repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                hovered = false; repaint()
            }
        })
    }

    fun isSpinning(): Boolean = currentStatus.lowercase() in listOf("running", "pending")

    fun updateStatus(status: String) {
        currentStatus = status
        statusLabel.icon = currentStatusIcon()
        repaint()
    }

    fun advanceSpin() {
        spinFrame = (spinFrame + 1) % 8
        if (isSpinning()) {
            statusLabel.icon = spinIcons[spinFrame]
        }
    }

    /**
     * Upgrades this chip's icons from the "other tool" (outline) variants to the
     * "agentbridge MCP" (filled) variants. Called when [ToolCallTracker] fires
     * [onCorrelated][com.github.catatafishen.agentbridge.services.ToolCallTracker.Listener.onCorrelated]
     * after the chip was already created with [isMcpHandled]=false.
     */
    fun setMcpHandled() {
        successIcon = FilledCircleIcon()
        errorIcon = FilledArcIcon()
        statusLabel.icon = currentStatusIcon()
        repaint()
    }

    private fun currentStatusIcon(): Icon = when (currentStatus.lowercase()) {
        "running", "pending" -> spinIcons[spinFrame]
        "complete", "completed", "success", "done" -> successIcon
        else -> errorIcon
    }

    companion object {
        private fun truncateLabel(text: String, max: Int = 50): String =
            if (text.length > max) text.take(max - 1) + "…" else text
    }
}
