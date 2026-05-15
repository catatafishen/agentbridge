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
    val AGENT_BUBBLE_BG = JBColor(Color(150, 200, 150, 12), Color(150, 200, 150, 15))

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

    val NUDGE_BG: JBColor = JBColor(Color(0xF0, 0xF0, 0xFF), Color(0x2A, 0x2A, 0x3A))
    val NUDGE_BORDER: JBColor = JBColor(Color(0xD0, 0xD0, 0xE8), Color(0x40, 0x40, 0x60))
    val NUDGE_FG: JBColor = JBColor(Color(0x50, 0x50, 0x70), Color(0xB0, 0xB0, 0xD0))
    val QUEUED_BG: JBColor = JBColor(Color(0xF0, 0xFF, 0xF0), Color(0x2A, 0x30, 0x2A))
    val QUEUED_BORDER: JBColor = JBColor(Color(0xD0, 0xE8, 0xD0), Color(0x40, 0x60, 0x40))
    val QUEUED_FG: JBColor = JBColor(Color(0x50, 0x70, 0x50), Color(0xB0, 0xD0, 0xB0))

    val CODE_BG: JBColor = JBColor(Color(0xEB, 0xEB, 0xEB), Color(0x33, 0x33, 0x33))
    val TABLE_BORDER: JBColor = JBColor(Color(0xD0, 0xD0, 0xD0), Color(0x44, 0x44, 0x44))
    val LINK: JBColor = JBColor(Color(0x24, 0x70, 0xB3), Color(0x58, 0x9D, 0xF6))
}
