package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat
import com.github.catatafishen.agentbridge.settings.McpServerSettings
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.swing.BoxLayout
import javax.swing.JPanel

/**
 * Shared base for [ToolChipComponent] and [ThinkingChipComponent].
 *
 * Provides a horizontal no-wrap [BoxLayout] container with a fixed chip height,
 * kind-derived colors, rounded-rect painting with hover highlight, and consistent
 * sizing overrides so both chip types render identically in height.
 *
 * @param settings per-project [McpServerSettings] used to resolve user color overrides.
 *   Pass `null` to use the default palette.
 */
abstract class BaseChipComponent(kind: String?, settings: McpServerSettings?) : JPanel() {

    protected val kindCol: Color = NativeChatColors.kindColor(kind, settings)
    protected val bgCol: Color = NativeChatColors.kindBg(kindCol)
    protected val borderCol: Color = NativeChatColors.kindBorder(kindCol)
    protected val hoverBgCol: Color = NativeChatColors.kindBgHover(kindCol)
    protected val hoverBorderCol: Color = NativeChatColors.kindBorderHover(kindCol)

    /** Toggled by the subclass's hover mouse listener; drives [paintComponent]. */
    var hovered = false
        set(value) {
            if (field != value) {
                field = value
                repaint()
            }
        }

    private var cachedNormal: BufferedImage? = null
    private var cachedHover: BufferedImage? = null
    private var cachedW = -1
    private var cachedH = -1
    private var cachedNormalBgRGB = Int.MIN_VALUE
    private var cachedHoverBgRGB = Int.MIN_VALUE

    companion object {
        /** Chip height tracks editor font size so text never clips when the user scales up. DPI-aware. */
        val CHIP_HEIGHT: Int get() = JBUI.scale(PlatformApiCompat.getEditorFontSize() + 9)
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
        val w = width;
        val h = height
        if (w <= 0 || h <= 0) return
        val normalRGB = bgCol.rgb
        val hoverRGB = hoverBgCol.rgb
        if (cachedW != w || cachedH != h || cachedNormalBgRGB != normalRGB || cachedHoverBgRGB != hoverRGB) {
            cachedNormal = null; cachedHover = null
            cachedW = w; cachedH = h
            cachedNormalBgRGB = normalRGB; cachedHoverBgRGB = hoverRGB
        }
        val img = if (hovered) {
            cachedHover ?: renderChipImage(w, h, hovered = true).also { cachedHover = it }
        } else {
            cachedNormal ?: renderChipImage(w, h, hovered = false).also { cachedNormal = it }
        }
        UIUtil.drawImage(g, img, 0, 0, null)
    }

    private fun renderChipImage(w: Int, h: Int, hovered: Boolean): BufferedImage {
        val img = UIUtil.createImage(this, w, h, BufferedImage.TYPE_INT_ARGB)
        val g2 = img.createGraphics()
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val r = JBUI.scale(6)
            g2.color = if (hovered) hoverBgCol else bgCol
            g2.fillRoundRect(0, 0, w, h, r, r)
            g2.color = if (hovered) hoverBorderCol else borderCol
            g2.drawRoundRect(0, 0, w - 1, h - 1, r, r)
        } finally {
            g2.dispose()
        }
        return img
    }
}
