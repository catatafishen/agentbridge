package com.github.catatafishen.ideagentforcopilot.ui

import com.github.catatafishen.ideagentforcopilot.settings.McpServerSettings
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import java.awt.Color

/**
 * Resolves semantic tool-kind colors, honoring per-project user overrides stored in
 * [McpServerSettings]. Falls back to the defaults that match the chat panel CSS palette
 * in [ChatTheme].
 *
 * These colors are used in:
 * - Quick Permissions combo-box tints in [PermissionsPanel]
 * - Tool kind color accents in [com.github.catatafishen.ideagentforcopilot.settings.ToolsConfigurable]
 * - CSS `--kind-*` variables in [ChatTheme.buildCssVars]
 */
object ToolKindColors {

    // Defaults match ChatTheme.KIND_*_COLOR values.
    @JvmField
    val DEFAULT_READ: JBColor = JBColor(Color(0x3A, 0x95, 0x95), Color(100, 185, 185))

    @JvmField
    val DEFAULT_EDIT: JBColor = JBColor(Color(0xA0, 0x7A, 0x3A), Color(205, 155, 95))

    @JvmField
    val DEFAULT_EXECUTE: JBColor = JBColor(Color(0x4A, 0x90, 0x4A), Color(130, 190, 130))

    @JvmStatic
    fun readColor(settings: McpServerSettings?): JBColor =
        ThemeColor.fromKey(settings?.kindReadColorKey)?.color ?: DEFAULT_READ

    @JvmStatic
    fun editColor(settings: McpServerSettings?): JBColor =
        ThemeColor.fromKey(settings?.kindEditColorKey)?.color ?: DEFAULT_EDIT

    @JvmStatic
    fun executeColor(settings: McpServerSettings?): JBColor =
        ThemeColor.fromKey(settings?.kindExecuteColorKey)?.color ?: DEFAULT_EXECUTE

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
