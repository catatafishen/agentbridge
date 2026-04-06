package com.github.catatafishen.agentbridge.ui

import com.intellij.ui.JBColor

/**
 * Named theme-aware colors for user selection in settings.
 * Each entry maps a human-readable [displayName] to a [JBColor] that adapts to
 * light/dark IDE themes. Colors reference [ChatTheme] constants so they remain
 * consistent with the chat panel palette.
 *
 * The enum name (e.g. "TEAL") is used as the persistence key.
 */
enum class ThemeColor(val displayName: String, val color: JBColor) {
    TEAL("Teal", ChatTheme.SA_COLORS[0]),
    AMBER("Amber", ChatTheme.SA_COLORS[1]),
    PURPLE("Purple", ChatTheme.SA_COLORS[2]),
    PINK("Pink", ChatTheme.SA_COLORS[3]),
    CYAN("Cyan", ChatTheme.SA_COLORS[4]),
    LIME("Lime", ChatTheme.SA_COLORS[5]),
    CORAL("Coral", ChatTheme.SA_COLORS[6]),
    BLUE("Blue", ChatTheme.SA_COLORS[7]),
    GREEN("Green", ChatTheme.AGENT_COLOR),
    GRAY("Gray", ChatTheme.THINK_COLOR),
    RED("Red", ChatTheme.ERROR_COLOR);

    companion object {
        /** Returns the [ThemeColor] whose name matches [key], or null if not found or key is blank. */
        @JvmStatic
        fun fromKey(key: String?): ThemeColor? = key?.let { k -> entries.firstOrNull { it.name == k } }
    }
}
