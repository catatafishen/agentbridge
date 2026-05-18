package com.github.catatafishen.agentbridge.ui

import com.intellij.ui.Gray
import com.intellij.ui.JBColor
import java.awt.Color

/**
 * Color palette for the native Swing chat panel.
 *
 * Values are derived from the JCEF chat.css design tokens. Accent colors use
 * alpha compositing so they blend naturally with both light and dark IDE themes.
 */
object NativeChatColors {
    val THINK: JBColor = JBColor(Gray._100, Gray._176)

    val USER_BUBBLE_BG = JBColor(Color(86, 156, 214, 25), Color(86, 156, 214, 31))
    val USER_BUBBLE_BORDER = JBColor(Color(86, 156, 214, 45), Color(86, 156, 214, 55))

    val AGENT_BUBBLE_BG = JBColor(Color(150, 200, 150, 12), Color(150, 200, 150, 15))
    val AGENT_BUBBLE_BORDER = JBColor(Color(150, 200, 150, 35), Color(150, 200, 150, 45))

    val THINK_BG = JBColor(Color(128, 128, 128, 15), Color(128, 128, 128, 20))
    val THINK_BORDER = JBColor(Color(128, 128, 128, 30), Color(128, 128, 128, 41))

    val ERROR = JBColor(Color(204, 0, 0), Color(255, 107, 107))
    val ERROR_BG = JBColor(Color(199, 34, 34, 15), Color(199, 34, 34, 20))

    private val KIND_READ = JBColor(Color(80, 150, 80), Color(120, 190, 120))
    private val KIND_EDIT = JBColor(Color(175, 125, 65), Color(205, 155, 95))
    private val KIND_EXECUTE = JBColor(Color(180, 75, 75), Color(210, 105, 105))
    private val KIND_SEARCH = JBColor(Color(80, 135, 180), Color(110, 165, 210))
    private val KIND_THINK = JBColor(Color(140, 125, 180), Color(170, 155, 210))
    private val KIND_OTHER = JBColor(Color(130, 135, 140), Color(160, 165, 170))

    fun kindColor(kind: String?): Color = when (kind?.lowercase()) {
        "read" -> KIND_READ
        "edit", "write", "move" -> KIND_EDIT
        "execute", "delete" -> KIND_EXECUTE
        "search" -> KIND_SEARCH
        "think" -> KIND_THINK
        else -> KIND_OTHER
    }

    /** 10% alpha background derived from the kind's accent color. */
    fun kindBg(kind: String?): JBColor {
        val base = kindColor(kind)
        return JBColor(Color(base.red, base.green, base.blue, 26), Color(base.red, base.green, base.blue, 26))
    }

    /** 22% alpha border derived from the kind's accent color. */
    fun kindBorder(kind: String?): JBColor {
        val base = kindColor(kind)
        return JBColor(Color(base.red, base.green, base.blue, 56), Color(base.red, base.green, base.blue, 56))
    }

    /** 18% alpha hover background derived from the kind's accent color. */
    fun kindBgHover(kind: String?): JBColor {
        val base = kindColor(kind)
        return JBColor(Color(base.red, base.green, base.blue, 46), Color(base.red, base.green, base.blue, 46))
    }

    /** 32% alpha hover border derived from the kind's accent color. */
    fun kindBorderHover(kind: String?): JBColor {
        val base = kindColor(kind)
        return JBColor(Color(base.red, base.green, base.blue, 82), Color(base.red, base.green, base.blue, 82))
    }

    val CODE_BG: JBColor = JBColor(Color(0xEB, 0xEB, 0xEB), Color(0x33, 0x33, 0x33))
    val TABLE_BORDER: JBColor = JBColor(Color(0xD0, 0xD0, 0xD0), Color(0x44, 0x44, 0x44))
    val LINK: JBColor = JBColor(Color(0x24, 0x70, 0xB3), Color(0x58, 0x9D, 0xF6))

    /**
     * Background color for a dynamic agent bubble using an accent color sampled from [ChatTheme.SA_COLORS].
     * Uses a fixed alpha so the color adapts to the accent without requiring a full [JBColor] wrapper.
     */
    fun agentBubbleBg(accent: Color): Color = Color(accent.red, accent.green, accent.blue, 15)

    /**
     * Border color for a dynamic agent bubble — slightly more opaque than the background.
     */
    fun agentBubbleBorder(accent: Color): Color = Color(accent.red, accent.green, accent.blue, 45)

    /**
     * Returns the border color for a bubble background, or null for backgrounds that have no border
     * (e.g. error bubbles). Uses identity comparison — callers must pass the singleton constants.
     */
    fun bubbleBorder(bg: Color): Color? = when (bg) {
        USER_BUBBLE_BG -> USER_BUBBLE_BORDER
        AGENT_BUBBLE_BG -> AGENT_BUBBLE_BORDER
        THINK_BG -> THINK_BORDER
        else -> null
    }
}
