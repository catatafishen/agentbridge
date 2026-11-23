package com.github.catatafishen.agentbridge.ui

import com.intellij.openapi.diagnostic.Logger
import java.awt.Color
import java.awt.Component
import java.awt.Graphics

/**
 * Works out the solid colour a non-opaque component is painted over, so it can render into an
 * opaque image. Java2D draws subpixel (LCD) text only onto opaque destinations.
 */
internal object PaneBackdrop {

    /**
     * The colour behind [component]: the [RoundedPanel] fills between it and its nearest opaque
     * ancestor, composited over that ancestor's flat background. Null when an ancestor in between
     * paints something this cannot model, or when no fully opaque ancestor exists.
     */
    fun resolve(component: Component): Color? = backdropOf(component.parent)

    private fun backdropOf(ancestor: Component?): Color? {
        if (ancestor == null || paintsCustomContent(ancestor)) return null
        if (ancestor.isOpaque) return ancestor.background?.takeIf { it.alpha == OPAQUE }
        val behind = backdropOf(ancestor.parent) ?: return null
        val fill = (ancestor as? RoundedPanel)?.fillColor ?: return behind
        return over(fill, behind)
    }

    /** [fill] composited over the opaque [base] with source-over blending. */
    fun over(fill: Color, base: Color): Color {
        val a = fill.alpha
        return Color(blend(fill.red, base.red, a), blend(fill.green, base.green, a), blend(fill.blue, base.blue, a))
    }

    /** Rounds each term separately, as Java2D's source-over loops do, so the result matches the painted pixels. */
    private fun blend(src: Int, dst: Int, alpha: Int): Int = mul8(alpha, src) + mul8(OPAQUE - alpha, dst)

    /** Java2D's `mul8table`: a × b / 255, rounded. */
    private fun mul8(a: Int, b: Int): Int = ((a.toLong() * b * 0x010101 + (1L shl 23)) ushr 24).toInt()

    /** True when a class between Swing and [component]'s own class overrides painting, apart from [RoundedPanel]. */
    private fun paintsCustomContent(component: Component): Boolean {
        var cls: Class<*>? = component.javaClass
        while (cls != null && cls != RoundedPanel::class.java && !isSwingOrAwt(cls)) {
            if (declaresPaint.get(cls)) return true
            cls = cls.superclass
        }
        return false
    }

    private fun isSwingOrAwt(cls: Class<*>): Boolean =
        cls.name.startsWith("javax.swing.") || cls.name.startsWith("java.awt.")

    private val PAINT_METHODS = setOf("paint", "paintComponent", "paintChildren", "paintBorder")

    /** True for classes whose painting cannot be modelled, either declared or unreadable; logged once per class. */
    private val declaresPaint = object : ClassValue<Boolean>() {
        override fun computeValue(type: Class<*>): Boolean {
            val declares = try {
                type.declaredMethods.any {
                    it.name in PAINT_METHODS && it.parameterTypes.contentEquals(arrayOf(Graphics::class.java))
                }
            } catch (e: LinkageError) {
                LOG.debug("Cannot read the methods of ${type.name}", e)
                true
            }
            if (declares) LOG.debug("${type.name} paints itself; panes inside it keep translucent frames")
            return declares
        }
    }

    private val LOG = Logger.getInstance(PaneBackdrop::class.java)

    private const val OPAQUE = 255
}
