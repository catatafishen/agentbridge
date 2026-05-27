package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.settings.McpServerSettings
import com.intellij.ui.JBColor
import java.awt.Color

/**
 * Color palette for the native Swing chat panel.
 *
 * Values are derived from the JCEF chat.css design tokens. Accent colors use
 * alpha compositing so they blend naturally with both light and dark IDE themes.
 *
 * Kind colors delegate to [ToolKindColors], which is the single source of truth
 * and honors per-project user overrides from [McpServerSettings].
 */
object NativeChatColors {

    val USER_BUBBLE_BG = JBColor(Color(86, 156, 214, 25), Color(86, 156, 214, 31))
    val USER_BUBBLE_BORDER = JBColor(Color(86, 156, 214, 45), Color(86, 156, 214, 55))

    /** Muted variant of the user bubble used for messages that are queued but not yet sent. */
    val QUEUED_BUBBLE_BG = JBColor(Color(86, 156, 214, 12), Color(86, 156, 214, 16))
    val QUEUED_BUBBLE_BORDER = JBColor(Color(86, 156, 214, 22), Color(86, 156, 214, 28))

    val AGENT_BUBBLE_BG = JBColor(Color(150, 200, 150, 12), Color(150, 200, 150, 15))
    val AGENT_BUBBLE_BORDER = JBColor(Color(150, 200, 150, 35), Color(150, 200, 150, 45))

    val THINK_BG = JBColor(Color(128, 128, 128, 15), Color(128, 128, 128, 20))
    val THINK_BORDER = JBColor(Color(128, 128, 128, 30), Color(128, 128, 128, 41))

    val ERROR = JBColor(Color(204, 0, 0), Color(255, 107, 107))
    val ERROR_BG = JBColor(Color(199, 34, 34, 15), Color(199, 34, 34, 20))

    /**
     * Resolves the kind accent color, delegating to [ToolKindColors] for the four
     * user-configurable kinds (read, search, edit, execute). The "think" and "other"
     * kinds are not user-configurable and use the shared defaults from [ChatTheme].
     *
     * [settings] is required to apply per-project user overrides; pass `null` to use defaults.
     */
    fun kindColor(kind: String?, settings: McpServerSettings? = null): Color = when (kind?.lowercase()) {
        "read" -> ToolKindColors.readColor(settings)
        "edit", "write", "delete", "move" -> ToolKindColors.editColor(settings)
        "execute" -> ToolKindColors.executeColor(settings)
        "search" -> ToolKindColors.searchColor(settings)
        else -> ChatTheme.THINK_COLOR
    }

    /** 10% alpha background derived from the kind's accent color. */
    fun kindBg(color: Color): JBColor =
        JBColor(Color(color.red, color.green, color.blue, 26), Color(color.red, color.green, color.blue, 26))

    /** 22% alpha border derived from the kind's accent color. */
    fun kindBorder(color: Color): JBColor =
        JBColor(Color(color.red, color.green, color.blue, 56), Color(color.red, color.green, color.blue, 56))

    /** 18% alpha hover background derived from the kind's accent color. */
    fun kindBgHover(color: Color): JBColor =
        JBColor(Color(color.red, color.green, color.blue, 46), Color(color.red, color.green, color.blue, 46))

    /** 32% alpha hover border derived from the kind's accent color. */
    fun kindBorderHover(color: Color): JBColor =
        JBColor(Color(color.red, color.green, color.blue, 82), Color(color.red, color.green, color.blue, 82))

    val CODE_BG: JBColor = JBColor(Color(0xEB, 0xEB, 0xEB), Color(0x33, 0x33, 0x33))
    val TABLE_BORDER: JBColor = JBColor(Color(0xD0, 0xD0, 0xD0), Color(0x44, 0x44, 0x44))
    val LINK: JBColor = JBColor(Color(0x24, 0x70, 0xB3), Color(0x58, 0x9D, 0xF6))

    /**
     * Background color for a dynamic agent bubble using an accent color sampled from [ChatTheme.SA_COLORS].
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
        QUEUED_BUBBLE_BG -> QUEUED_BUBBLE_BORDER
        AGENT_BUBBLE_BG -> AGENT_BUBBLE_BORDER
        THINK_BG -> THINK_BORDER
        else -> null
    }
}
