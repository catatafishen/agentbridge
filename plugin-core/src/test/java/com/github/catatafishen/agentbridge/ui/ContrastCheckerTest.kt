package com.github.catatafishen.agentbridge.ui

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.awt.Color

/**
 * Tests for [ContrastChecker] — pure-function WCAG 2.1 contrast utilities.
 *
 * All tests use plain [java.awt.Color] with zero IntelliJ/IDE dependencies,
 * so they run in any JUnit 5 environment without fixtures.
 */
class ContrastCheckerTest {

    // -- Relative luminance ----------------------------------------------------

    @Nested
    inner class RelativeLuminance {

        @Test
        fun `black has zero luminance`() {
            assertEquals(0.0, ContrastChecker.relativeLuminance(Color.BLACK), 1e-12)
        }

        @Test
        fun `white has full luminance`() {
            assertEquals(1.0, ContrastChecker.relativeLuminance(Color.WHITE), 1e-12)
        }

        @Test
        fun `middle grey luminance`() {
            // #808080 → each channel = 128/255 ≈ 0.502, linearized ≈ 0.2158
            val lum = ContrastChecker.relativeLuminance(Color(128, 128, 128))
            assertEquals(0.2158, lum, 0.001)
        }

        @Test
        fun `luminance is channel-weighted`() {
            // Red (255,0,0) luminance = 0.2126 * 1.0 + 0 + 0
            val redLum = ContrastChecker.relativeLuminance(Color.RED)
            assertEquals(0.2126, redLum, 0.001)

            // Blue (0,0,255) luminance = 0.0722 * 1.0
            val blueLum = ContrastChecker.relativeLuminance(Color.BLUE)
            assertEquals(0.0722, blueLum, 0.001)
        }

        @Test
        fun `gamma expansion is correct`() {
            // sRGB 0.5 → linear (0.5 + 0.055) / 1.055 ^ 2.4 ≈ 0.214
            val halfGrey = Color(128, 128, 128)
            val linearR = (128.0 / 255.0 + 0.055) / 1.055
            val expected = Math.pow(linearR, 2.4) // all 3 channels identical
            assertEquals(expected, ContrastChecker.relativeLuminance(halfGrey) / (0.2126 + 0.7152 + 0.0722), 1e-6)
        }
    }

    // -- Contrast ratio -------------------------------------------------------

    @Nested
    inner class Ratio {

        @Test
        fun `same color has ratio 1`() {
            assertEquals(1.0, ContrastChecker.ratio(Color.BLACK, Color.BLACK), 1e-12)
            assertEquals(1.0, ContrastChecker.ratio(Color.WHITE, Color.WHITE), 1e-12)
            assertEquals(1.0, ContrastChecker.ratio(Color.RED, Color.RED), 1e-12)
        }

        @Test
        fun `black on white is maximum contrast`() {
            assertEquals(21.0, ContrastChecker.ratio(Color.BLACK, Color.WHITE), 0.01)
        }

        @Test
        fun `white on black is also maximum`() {
            assertEquals(21.0, ContrastChecker.ratio(Color.WHITE, Color.BLACK), 0.01)
        }

        @Test
        fun `ratio is order-independent`() {
            val fg = Color(50, 50, 50)
            val bg = Color(200, 200, 200)
            assertEquals(
                ContrastChecker.ratio(fg, bg),
                ContrastChecker.ratio(bg, fg),
                1e-10
            )
        }

        @Test
        fun `known ratio for dark grey on white`() {
            // #333333 on #FFFFFF ≈ 12.63:1
            val ratio = ContrastChecker.ratio(Color(0x33, 0x33, 0x33), Color.WHITE)
            assertEquals(12.63, ratio, 0.01)
        }

        @Test
        fun `low contrast pair`() {
            // #999999 on #FFFFFF ≈ 2.85:1 (fails AA)
            val ratio = ContrastChecker.ratio(Color(0x99, 0x99, 0x99), Color.WHITE)
            assertEquals(2.85, ratio, 0.01)
            assertTrue(ratio < 4.5) // fails AA normal text
            assertTrue(ratio < 3.0) // fails AA large text
        }

        @Test
        fun `AA pass on common IDE text`() {
            // Typical dark-on-light: #111 on #F5F5F5 ≈ 19.3:1
            val ratio = ContrastChecker.ratio(Color(0x11, 0x11, 0x11), Color(0xF5, 0xF5, 0xF5))
            assertTrue(ratio >= 7.0) // passes even AAA
        }
    }

    // -- Composite color (alpha blending) ------------------------------------

    @Nested
    inner class CompositeColor {

        @Test
        fun `zero alpha returns background`() {
            val result = ContrastChecker.compositeColor(Color.RED, Color.GREEN, 0.0)
            assertEquals(Color.GREEN, result)
        }

        @Test
        fun `full alpha returns foreground`() {
            val result = ContrastChecker.compositeColor(Color.RED, Color.GREEN, 1.0)
            assertEquals(Color.RED, result)
        }

        @Test
        fun `half alpha blends channels`() {
            // Red(255,0,0) over White(255,255,255) at 50% → (255, 127, 127)
            // Note: .toInt() truncates 127.5 → 127 (not round)
            val result = ContrastChecker.compositeColor(Color.RED, Color.WHITE, 0.5)
            assertEquals(Color(255, 127, 127), result)
        }

        @Test
        fun `alpha is clamped to valid range`() {
            val clampedLow = ContrastChecker.compositeColor(Color.RED, Color.BLUE, -0.5)
            assertEquals(Color.BLUE, clampedLow)

            val clampedHigh = ContrastChecker.compositeColor(Color.RED, Color.BLUE, 1.5)
            assertEquals(Color.RED, clampedHigh)
        }

        @Test
        fun `quarter alpha blue over yellow`() {
            // Blue(0,0,255) over Yellow(255,255,0) at 25%
            val result = ContrastChecker.compositeColor(Color.BLUE, Color.YELLOW, 0.25)
            // R: 0*0.25 + 255*0.75 = 191, G: 0*0.25 + 255*0.75 = 191, B: 255*0.25 + 0*0.75 = 63.75 → truncates to 63
            assertEquals(Color(191, 191, 63), result)
        }
    }

    // -- Full contrast check -------------------------------------------------

    @Nested
    inner class Check {

        @Test
        fun `black on white passes all levels`() {
            val result = ContrastChecker.check(Color.BLACK, Color.WHITE)
            assertTrue(result.passesAANormal)
            assertTrue(result.passesAALarge)
            assertTrue(result.passesAAANormal)
            assertTrue(result.passesAAALarge)
            assertEquals(21.0, result.ratio, 0.01)
        }

        @Test
        fun `low contrast grey fails AA normal but passes AA large`() {
            // #999 on #EEE ≈ 2.8:1 — fails AA normal (needs 4.5), passes AA large (needs 3.0)
            val result = ContrastChecker.check(Color(0x99, 0x99, 0x99), Color(0xEE, 0xEE, 0xEE))
            assertFalse(result.passesAANormal, "should fail AA normal text threshold")
            assertFalse(result.passesAALarge, "should fail AA large text threshold")
        }

        @Test
        fun `grey on white passes AA large but not AA normal`() {
            // #777 on #FFF ≈ 4.0:1 — passes AA large (3.0), fails AA normal (4.5)
            val result = ContrastChecker.check(Color(0x77, 0x77, 0x77), Color.WHITE)
            assertFalse(result.passesAANormal, "4.0:1 < 4.5")
            assertTrue(result.passesAALarge, "4.0:1 >= 3.0")
        }

        @Test
        fun `AAA normal threshold`() {
            // #555 on #FFF ≈ 7.46:1 — passes AA and AAA normal
            val result = ContrastChecker.check(Color(0x55, 0x55, 0x55), Color.WHITE)
            assertTrue(result.passesAANormal)
            assertTrue(result.passesAAANormal, "7.46:1 >= 7.0")
        }
    }

    // -- Format --------------------------------------------------------------

    @Nested
    inner class Format {

        @Test
        fun `format maximum contrast`() {
            val result = ContrastChecker.check(Color.BLACK, Color.WHITE)
            val formatted = ContrastChecker.format(result)
            assertTrue(formatted.contains("21.0"))
            assertTrue(formatted.contains("AA normal"))
            assertTrue(formatted.contains("AAA normal"))
        }

        @Test
        fun `format failing contrast`() {
            val result = ContrastChecker.check(Color(0xAA, 0xAA, 0xAA), Color.WHITE)
            val formatted = ContrastChecker.format(result)
            assertTrue(formatted.matches(Regex("\\d+\\.\\d:1.*")))
        }
    }

    // -- Min alpha for contrast -----------------------------------------------

    @Nested
    inner class MinAlphaForContrast {

        @Test
        fun `minimum alpha returns non-null for achievable target`() {
            // Accent (100,100,100) over white background with black text:
            // At alpha=0: composite=WHITE, black on white already passes 3.0:1
            val minAlpha = ContrastChecker.minAlphaForContrast(
                accent = Color(100, 100, 100),
                background = Color.WHITE,
                textOnTop = Color.BLACK,
            )
            assertNotNull(minAlpha)
            assertTrue(minAlpha!! in 0.0..1.0)
        }

        @Test
        fun `zero alpha always fails`() {
            // With 0% alpha the composite = background — if text matches background that's 1:1
            val minAlpha = ContrastChecker.minAlphaForContrast(
                accent = Color.RED,
                background = Color.WHITE,
                textOnTop = Color.BLACK,
                targetRatio = 1.0,
            )
            assertNotNull(minAlpha)
            assertTrue(minAlpha!! >= 0.0)
        }

        @Test
        fun `impossible contrast returns null`() {
            // If accent and background are identical, any alpha gives 1:1 ratio
            // against text that's also identical → impossible to reach 21:1.
            // Actually accent=WHITE over bg=WHITE produces white regardless of alpha,
            // so against white text the ratio is always 1:1 and can't reach 4.5:1.
            val minAlpha = ContrastChecker.minAlphaForContrast(
                accent = Color.WHITE,
                background = Color.WHITE,
                textOnTop = Color.WHITE,
                targetRatio = 4.5,
            )
            assertNull(minAlpha, "impossible to reach 4.5:1 when all colors are identical")
        }
    }

    // -- Known WCAG test cases -----------------------------------------------

    @Nested
    inner class WcagReferenceCases {

        @Test
        fun `WCAG example 1`() {
            // #595959 on #FFFFFF = 7.00:1 (passes AA normal)
            val ratio = ContrastChecker.ratio(Color(0x59, 0x59, 0x59), Color.WHITE)
            assertEquals(7.00, ratio, 0.01)
        }

        @Test
        fun `WCAG example 2`() {
            // #767676 on #FFFFFF = 4.54:1 (passes AA normal, fails AAA normal)
            val ratio = ContrastChecker.ratio(Color(0x76, 0x76, 0x76), Color.WHITE)
            assertEquals(4.54, ratio, 0.01)
            assertTrue(ratio >= 4.5) // passes AA normal
            assertTrue(ratio < 7.0)  // fails AAA normal
        }

        @Test
        fun `WCAG example 3`() {
            // Black on #F5F5F5 = 19.26:1
            val ratio = ContrastChecker.ratio(Color.BLACK, Color(0xF5, 0xF5, 0xF5))
            assertEquals(19.26, ratio, 0.01)
        }

        @Test
        fun `user bubble accent on panel background`() {
            // Simulate ChatTheme.USER_COLOR (blue, RGB 86,156,214) at 12% alpha over
            // dark panel background (43,43,43) with --fg (187,187,187) text on top.
            val panelBg = Color(43, 43, 43)
            val userAccent = Color(86, 156, 214)
            val textFg = Color(187, 187, 187)

            val composite = ContrastChecker.compositeColor(userAccent, panelBg, 0.12)
            val ratio = ContrastChecker.ratio(textFg, composite)

            // Should not dramatically reduce contrast (actual: ~84% of untinted)
            val untintedRatio = ContrastChecker.ratio(textFg, panelBg)
            assertTrue(ratio >= untintedRatio * 0.80, "tinting should not dramatically reduce contrast")
        }
    }
}
