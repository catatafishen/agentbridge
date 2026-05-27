package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.settings.McpServerSettings
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import java.awt.Color

/**
 * Resolves semantic tool-kind colors, honoring per-project user overrides stored in
 * [McpServerSettings]. Defaults are sourced from the [ThemeColor] enum so the palette
 * has a single canonical source — there are no hardcoded RGB values in this file.
 *
 * These colors are used in:
 * - Quick Permissions combo-box tints in [PermissionsPanel]
 * - Tool kind color accents in [com.github.catatafishen.agentbridge.settings.ToolsConfigurable]
 * - CSS `--kind-*` variables in [ChatTheme.buildCssVars]
 */
object ToolKindColors {

    /** Default theme color for each kind. Override in [McpServerSettings] per project. */
    @JvmField
    val DEFAULT_READ_KEY: ThemeColor = ThemeColor.TEAL

    @JvmField
    val DEFAULT_SEARCH_KEY: ThemeColor = ThemeColor.BLUE

    @JvmField
    val DEFAULT_EDIT_KEY: ThemeColor = ThemeColor.AMBER

    @JvmField
    val DEFAULT_EXECUTE_KEY: ThemeColor = ThemeColor.GREEN

    @JvmStatic
    fun readColor(settings: McpServerSettings?): JBColor =
        ThemeColor.fromKey(settings?.kindReadColorKey)?.color ?: DEFAULT_READ_KEY.color

    @JvmStatic
    fun searchColor(settings: McpServerSettings?): JBColor =
        ThemeColor.fromKey(settings?.kindSearchColorKey)?.color ?: DEFAULT_SEARCH_KEY.color

    @JvmStatic
    fun editColor(settings: McpServerSettings?): JBColor =
        ThemeColor.fromKey(settings?.kindEditColorKey)?.color ?: DEFAULT_EDIT_KEY.color

    @JvmStatic
    fun executeColor(settings: McpServerSettings?): JBColor =
        ThemeColor.fromKey(settings?.kindExecuteColorKey)?.color ?: DEFAULT_EXECUTE_KEY.color

    /**
     * Returns a tinted background by blending [alpha] proportion of [color] into the
     * panel background. Alpha 0.22 gives a clear but not overpowering tint.
     */
    @JvmStatic
    @JvmOverloads
    fun tintedBackground(color: Color, alpha: Double = 0.22): Color {
        val base = UIUtil.getPanelBackground()
        return Color(
            ((color.red * alpha + base.red * (1 - alpha)).toInt()).coerceIn(0, 255),
            ((color.green * alpha + base.green * (1 - alpha)).toInt()).coerceIn(0, 255),
            ((color.blue * alpha + base.blue * (1 - alpha)).toInt()).coerceIn(0, 255),
        )
    }

    /** Encodes a [Color] to a lowercase hex string (e.g. `"#3a9595"`). */
    @JvmStatic
    fun toHex(color: Color): String = "#%02x%02x%02x".format(color.red, color.green, color.blue)
}
