package com.github.catatafishen.agentbridge.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent
import javax.swing.UIManager

/**
 * Immutable snapshot of billing-cycle progress used to render the usage graph.
 */
data class UsageGraphData(
    val currentDay: Int,
    val totalDays: Int,
    val usedSoFar: Int,
    val entitlement: Int,
)

/**
 * Tiny sparkline panel showing cumulative premium-request usage over the
 * billing cycle, a linear projection to end-of-month, and a horizontal
 * entitlement threshold line.  Over-quota usage is rendered in red.
 *
 * Set [graphData] and call [repaint] to update the visual.
 */
class UsageGraphPanel : JBPanel<UsageGraphPanel>() {
    var graphData: UsageGraphData? = null

    init {
        isOpaque = false
        val h = JBUI.scale(28)
        preferredSize = Dimension(JBUI.scale(120), h)
        minimumSize = preferredSize
        maximumSize = Dimension(JBUI.scale(120), h)
        border = JBUI.Borders.empty(1, JBUI.scale(2))
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val data = graphData ?: return
        if (data.entitlement <= 0) return

        val g2 = (g as Graphics2D).also {
            it.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        }
        val pad = 1
        val w = width - 2 * pad
        val h = height - 2 * pad
        if (w <= 0 || h <= 0) return

        val arc = JBUI.scale(6)

        drawBorder(g2, arc)

        val clipShape = RoundRectangle2D.Float(
            pad.toFloat(), pad.toFloat(), w.toFloat(), h.toFloat(), arc.toFloat(), arc.toFloat()
        )
        val oldClip = g2.clip
        g2.clip(clipShape)

        val rate = if (data.currentDay > 0) data.usedSoFar.toFloat() / data.currentDay else 0f
        val projected = (rate * data.totalDays).toInt()
        val maxY = maxOf(data.entitlement, projected, data.usedSoFar) * 1.15f
        val overQuota = data.usedSoFar > data.entitlement

        fun dx(day: Float) = pad + (day / data.totalDays * w)
        fun dy(v: Float) = pad + h - (v / maxY * h)

        drawEntitlementLine(g2, dy(data.entitlement.toFloat()), pad, w, overQuota)

        val baseY = dy(0f)
        val curX = dx(data.currentDay.toFloat())
        val curY = dy(data.usedSoFar.toFloat())

        if (overQuota) {
            drawOverQuotaUsage(g2, baseY, curX, curY, dy(data.entitlement.toFloat()), pad)
        } else {
            drawNormalUsage(g2, baseY, curX, curY, pad)
        }

        drawProjectionLine(g2, data, curX, curY, projected, ::dx, ::dy)
        drawCurrentDayDot(g2, curX, curY, overQuota)

        g2.clip = oldClip
    }

    private fun drawBorder(g2: Graphics2D, arc: Int) {
        val borderShape = RoundRectangle2D.Float(
            0.5f, 0.5f, (width - 1).toFloat(), (height - 1).toFloat(), arc.toFloat(), arc.toFloat()
        )
        g2.color = UIManager.getColor("Component.borderColor") ?: JBColor(0xC4C4C4, 0x5E6060)
        g2.stroke = BasicStroke(1f)
        g2.draw(borderShape)
    }

    private fun drawEntitlementLine(g2: Graphics2D, entY: Float, pad: Int, w: Int, overQuota: Boolean) {
        g2.color = if (overQuota)
            JBColor(Color(0xE0, 0x40, 0x40, 0x70), Color(0xE0, 0x60, 0x60, 0x70))
        else
            JBColor(Color(0x80, 0x80, 0x80, 0x40), Color(0xA0, 0xA0, 0xA0, 0x40))
        g2.stroke = BasicStroke(
            1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
            floatArrayOf(3f, 3f), 0f
        )
        g2.drawLine(pad, entY.toInt(), pad + w, entY.toInt())
    }

    private fun drawOverQuotaUsage(
        g2: Graphics2D, baseY: Float, curX: Float, curY: Float, quotaY: Float, pad: Int
    ) {
        val t = (quotaY - baseY) / (curY - baseY)
        val intersectX = pad + t * (curX - pad)

        val belowPath = Path2D.Float().apply {
            moveTo(pad.toFloat(), baseY)
            lineTo(intersectX, quotaY)
            lineTo(curX, quotaY)
            lineTo(curX, baseY)
            closePath()
        }
        g2.color = JBColor(Color(0x59, 0xA8, 0x69, 0x30), Color(0x6A, 0xAB, 0x73, 0x30))
        g2.fill(belowPath)

        val overPath = Path2D.Float().apply {
            moveTo(intersectX, quotaY)
            lineTo(curX, curY)
            lineTo(curX, quotaY)
            closePath()
        }
        g2.color = JBColor(Color(0xE0, 0x40, 0x40, 0x40), Color(0xE0, 0x60, 0x60, 0x40))
        g2.fill(overPath)

        g2.color = JBColor(Color(0xE0, 0x40, 0x40), Color(0xE0, 0x60, 0x60))
        g2.stroke = BasicStroke(1.5f)
        g2.drawLine(pad, baseY.toInt(), curX.toInt(), curY.toInt())
    }

    private fun drawNormalUsage(g2: Graphics2D, baseY: Float, curX: Float, curY: Float, pad: Int) {
        val areaPath = Path2D.Float().apply {
            moveTo(pad.toFloat(), baseY)
            lineTo(curX, curY)
            lineTo(curX, baseY)
            closePath()
        }
        g2.color = JBColor(Color(0x59, 0xA8, 0x69, 0x40), Color(0x6A, 0xAB, 0x73, 0x40))
        g2.fill(areaPath)

        g2.color = JBColor(Color(0x59, 0xA8, 0x69), Color(0x6A, 0xAB, 0x73))
        g2.stroke = BasicStroke(1.5f)
        g2.drawLine(pad, baseY.toInt(), curX.toInt(), curY.toInt())
    }

    private fun drawProjectionLine(
        g2: Graphics2D, data: UsageGraphData,
        curX: Float, curY: Float, projected: Int,
        dx: (Float) -> Float, dy: (Float) -> Float,
    ) {
        if (data.currentDay >= data.totalDays) return
        val projX = dx(data.totalDays.toFloat())
        val projY = dy(projected.toFloat())
        g2.color = JBColor(Color(0x80, 0x80, 0x80, 0x80), Color(0xA0, 0xA0, 0xA0, 0x80))
        g2.stroke = BasicStroke(
            1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
            floatArrayOf(3f, 3f), 0f
        )
        g2.drawLine(curX.toInt(), curY.toInt(), projX.toInt(), projY.toInt())
    }

    private fun drawCurrentDayDot(g2: Graphics2D, curX: Float, curY: Float, overQuota: Boolean) {
        val dotColor = if (overQuota)
            JBColor(Color(0xE0, 0x40, 0x40), Color(0xE0, 0x60, 0x60))
        else
            JBColor(Color(0x59, 0xA8, 0x69), Color(0x6A, 0xAB, 0x73))
        g2.color = dotColor
        g2.fillOval(
            curX.toInt() - JBUI.scale(2), curY.toInt() - JBUI.scale(2),
            JBUI.scale(4), JBUI.scale(4)
        )
    }
}

/**
 * Toolbar action that embeds a [UsageGraphPanel] as a clickable custom component.
 * Clicking the graph opens a popup with detailed usage information.
 */
class UsageGraphAction(
    private val onGraphClicked: (JComponent) -> Unit,
    private val graphPanelSetter: (UsageGraphPanel) -> Unit,
) : AnAction("Usage Graph"), CustomComponentAction {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        // Handled via mouse listener in createCustomComponent
    }

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
        val panel = UsageGraphPanel()
        panel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        panel.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                onGraphClicked(panel)
            }
        })
        graphPanelSetter(panel)
        return panel
    }
}
