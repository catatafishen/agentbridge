package com.github.catatafishen.agentbridge.ui

import java.awt.Color

/**
 * WCAG 2.1 contrast ratio calculator and validator.
 *
 * All methods are pure functions — no Swing, IntelliJ, or UI dependencies.
 * Suitable for unit testing with no fixtures.
 *
 * @see <a href="https://www.w3.org/TR/WCAG21/#contrast-minimum">WCAG 2.1 SC 1.4.3</a>
 * @see <a href="https://www.w3.org/TR/WCAG21/#non-text-contrast">WCAG 2.1 SC 1.4.11</a>
 */
object ContrastChecker {

    /**
     * Computes the WCAG 2.1 contrast ratio between two sRGB colors.
     * Range: 1.0 (same color) to 21.0 (black on white).
     *
     * Formula: (L1 + 0.05) / (L2 + 0.05) where L1 >= L2.
     */
    fun ratio(fg: Color, bg: Color): Double {
        val l1 = relativeLuminance(fg)
        val l2 = relativeLuminance(bg)
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /**
     * Computes the relative luminance of an sRGB color per WCAG 2.1.
     * L = 0.2126 * R + 0.7152 * G + 0.0722 * B
     * where each channel is linearized (gamma-expanded).
     */
    fun relativeLuminance(c: Color): Double {
        fun linearize(channel: Int): Double {
            val v = channel / 255.0
            return if (v <= 0.04045) v / 12.92
            else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * linearize(c.red) + 0.7152 * linearize(c.green) + 0.0722 * linearize(c.blue)
    }

    /**
     * WCAG AA threshold for normal text (< 18pt or < 14pt bold).
     */
    const val AA_NORMAL_TEXT: Double = 4.5

    /**
     * WCAG AA threshold for large text (>= 18pt or >= 14pt bold)
     * and UI components (SC 1.4.11).
     */
    const val AA_LARGE_TEXT: Double = 3.0

    /**
     * WCAG AAA threshold for normal text.
     */
    const val AAA_NORMAL_TEXT: Double = 7.0

    /**
     * WCAG AAA threshold for large text.
     */
    const val AAA_LARGE_TEXT: Double = 4.5

    /** Result of a contrast check. */
    data class ContrastResult(
        val ratio: Double,
        val passesAANormal: Boolean,
        val passesAALarge: Boolean,
        val passesAAANormal: Boolean,
        val passesAAALarge: Boolean,
    )

    /**
     * Runs a complete WCAG 2.1 contrast check for a foreground/background pair.
     */
    fun check(fg: Color, bg: Color): ContrastResult {
        val r = ratio(fg, bg)
        return ContrastResult(
            ratio = r,
            passesAANormal = r >= AA_NORMAL_TEXT,
            passesAALarge = r >= AA_LARGE_TEXT,
            passesAAANormal = r >= AAA_NORMAL_TEXT,
            passesAAALarge = r >= AAA_LARGE_TEXT,
        )
    }

    /**
     * Returns a human-readable summary string for a contrast result.
     * Example: "4.5:1 — AA normal ✓, AA large ✓, AAA normal ✗, AAA large ✓"
     */
    fun format(result: ContrastResult): String {
        val r = "%.1f:1".format(result.ratio)
        val aaN = if (result.passesAANormal) "✓" else "✗"
        val aaL = if (result.passesAALarge) "✓" else "✗"
        val aaaN = if (result.passesAAANormal) "✓" else "✗"
        val aaaL = if (result.passesAAALarge) "✓" else "✗"
        return "$r — AA normal $aaN, AA large $aaL, AAA normal $aaaN, AAA large $aaaL"
    }

    /**
     * Finds the minimum alpha multiplier needed for an accent color over a background
     * to meet a target contrast ratio. Useful for determining if current alpha-composited
     * bubble backgrounds pass WCAG thresholds.
     *
     * @param accent the accent color (e.g., ChatTheme.USER_COLOR)
     * @param background the background color (e.g., panel background)
     * @param textOnTop the text or foreground color on top
     * @param targetRatio the target contrast ratio (default AA_LARGE_TEXT = 3.0)
     * @return the minimum alpha multiplier (0.0–1.0), or null if impossible at 1.0
     */
    fun minAlphaForContrast(
        accent: Color,
        background: Color,
        textOnTop: Color,
        targetRatio: Double = AA_LARGE_TEXT,
    ): Double? {
        for (alpha in 0..100) {
            val a = alpha / 100.0
            val composite = compositeColor(accent, background, a)
            if (ratio(textOnTop, composite) >= targetRatio) {
                return a
            }
        }
        return null
    }

    /**
     * Alpha-composites [foreground] over [background] at [alpha] (0.0–1.0).
     * Result = fg * alpha + bg * (1 - alpha)
     */
    fun compositeColor(fg: Color, bg: Color, alpha: Double): Color {
        val a = alpha.coerceIn(0.0, 1.0)
        val r = (fg.red * a + bg.red * (1 - a)).toInt().coerceIn(0, 255)
        val g = (fg.green * a + bg.green * (1 - a)).toInt().coerceIn(0, 255)
        val b = (fg.blue * a + bg.blue * (1 - a)).toInt().coerceIn(0, 255)
        return Color(r, g, b)
    }
}
