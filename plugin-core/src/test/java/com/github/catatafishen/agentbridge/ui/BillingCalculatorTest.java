package com.github.catatafishen.agentbridge.ui;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class BillingCalculatorTest {

    // ── formatUsageChip ───────────────────────────────────────────────

    @Test
    void formatUsageChip_allZerosNullCost_returnsEmpty() {
        assertEquals("", BillingCalculator.INSTANCE.formatUsageChip(0, 0, null));
    }

    @Test
    void formatUsageChip_allZerosZeroCost_returnsEmpty() {
        assertEquals("", BillingCalculator.INSTANCE.formatUsageChip(0, 0, 0.0));
    }

    @Test
    void formatUsageChip_tokensUnder1000_nullCost_returnsTokensOnly() {
        assertEquals("850 tok", BillingCalculator.INSTANCE.formatUsageChip(500, 350, null));
    }

    @Test
    void formatUsageChip_tokensUnder1000_zeroCost_returnsTokensOnly() {
        assertEquals("850 tok", BillingCalculator.INSTANCE.formatUsageChip(500, 350, 0.0));
    }

    @Test
    void formatUsageChip_tokensOver1000_formatsAsKiloTokens() {
        // 800 + 400 = 1200 → "1.2k tok"
        assertEquals("1.2k tok", BillingCalculator.INSTANCE.formatUsageChip(800, 400, null));
    }

    @Test
    void formatUsageChip_tokensExactly1000_formatsAsKiloTokens() {
        assertEquals("1.0k tok", BillingCalculator.INSTANCE.formatUsageChip(600, 400, null));
    }

    @Test
    void formatUsageChip_withPositiveCost_appendsCost() {
        String result = BillingCalculator.INSTANCE.formatUsageChip(800, 400, 0.004);
        assertEquals("1.2k tok · $0.004", result);
    }

    @Test
    void formatUsageChip_costWithTrailingZeros_trimsThem() {
        // 0.0100 → trimEnd('0') → "0.01" → trimEnd('.') → "0.01"
        String result = BillingCalculator.INSTANCE.formatUsageChip(500, 500, 0.01);
        assertEquals("1.0k tok · $0.01", result);
    }

    @Test
    void formatUsageChip_costWholeNumber_trimsDecimalPoint() {
        // 1.0000 → trimEnd('0') → "1." → trimEnd('.') → "1"
        String result = BillingCalculator.INSTANCE.formatUsageChip(2000, 0, 1.0);
        assertEquals("2.0k tok · $1", result);
    }

    @Test
    void formatUsageChip_zeroTokensButPositiveCost_returnsTokensAndCost() {
        // not all-zero because cost > 0
        String result = BillingCalculator.INSTANCE.formatUsageChip(0, 0, 0.005);
        assertEquals("0 tok · $0.005", result);
    }

    @Test
    void formatUsageChip_smallTokensWithCost_formatsCorrectly() {
        String result = BillingCalculator.INSTANCE.formatUsageChip(50, 0, 0.001);
        assertEquals("50 tok · $0.001", result);
    }

    // ── parseMultiplier ───────────────────────────────────────────────

    @Test
    void parseMultiplier_integerWithX_parsesCorrectly() {
        assertEquals(3.0, BillingCalculator.INSTANCE.parseMultiplier("3x"));
    }

    @Test
    void parseMultiplier_decimalWithX_parsesCorrectly() {
        assertEquals(0.33, BillingCalculator.INSTANCE.parseMultiplier("0.33x"));
    }

    @Test
    void parseMultiplier_oneX_returnsOne() {
        assertEquals(1.0, BillingCalculator.INSTANCE.parseMultiplier("1x"));
    }

    @Test
    void parseMultiplier_noSuffix_parsesAsNumber() {
        assertEquals(5.0, BillingCalculator.INSTANCE.parseMultiplier("5"));
    }

    @Test
    void parseMultiplier_invalidString_returnsDefault() {
        assertEquals(1.0, BillingCalculator.INSTANCE.parseMultiplier("abc"));
    }

    @Test
    void parseMultiplier_emptyString_returnsDefault() {
        assertEquals(1.0, BillingCalculator.INSTANCE.parseMultiplier(""));
    }

    // ── formatPremium ─────────────────────────────────────────────────

    @Test
    void formatPremium_wholeNumber_returnsInteger() {
        assertEquals("3", BillingCalculator.INSTANCE.formatPremium(3.0));
    }

    @Test
    void formatPremium_fractionalNumber_returnsOneDecimal() {
        assertEquals("2.5", BillingCalculator.INSTANCE.formatPremium(2.5));
    }

    @Test
    void formatPremium_zero_returnsZero() {
        assertEquals("0", BillingCalculator.INSTANCE.formatPremium(0.0));
    }

    @Test
    void formatPremium_largeWholeNumber_returnsInteger() {
        assertEquals("100", BillingCalculator.INSTANCE.formatPremium(100.0));
    }

    @Test
    void formatPremium_smallFraction_returnsOneDecimal() {
        assertEquals("0.1", BillingCalculator.INSTANCE.formatPremium(0.1));
    }

    // ── buildGraphTooltip ─────────────────────────────────────────────

    @Test
    void buildGraphTooltip_noOverage_noProjectedOverage() {
        LocalDate resetDate = LocalDate.of(2025, 2, 1);
        String tooltip = BillingCalculator.INSTANCE.buildGraphTooltip(
                50, 500, 15, 30, resetDate, "#FF0000");

        assertTrue(tooltip.startsWith("<html>"));
        assertTrue(tooltip.endsWith("</html>"));
        assertTrue(tooltip.contains("Day 16 / 30"));
        assertTrue(tooltip.contains("Usage: 50 / 500"));
        assertTrue(tooltip.contains("Projected: ~100 by cycle end"));
        assertTrue(tooltip.contains("Resets: Feb 1, 2025"));
        assertFalse(tooltip.contains("Overage:"));
        assertFalse(tooltip.contains("Projected overage:"));
    }

    @Test
    void buildGraphTooltip_withOverage_showsOverageCost() {
        LocalDate resetDate = LocalDate.of(2025, 3, 15);
        String tooltip = BillingCalculator.INSTANCE.buildGraphTooltip(
                550, 500, 20, 30, resetDate, "#FF0000");

        assertTrue(tooltip.contains("Usage: 550 / 500"));
        // overage = 50 * 0.04 = $2.00
        assertTrue(tooltip.contains("Overage: 50 reqs ($2.00)"));
        assertTrue(tooltip.contains("<font color='#FF0000'>Overage:"));
    }

    @Test
    void buildGraphTooltip_projectedOverage_showsProjectedOverageCost() {
        // used 300 in 10 days → rate = 30/day → projected = 900 for 30 days
        // projected overage = 900 - 500 = 400 → cost = 400 * 0.04 = $16.00
        LocalDate resetDate = LocalDate.of(2025, 4, 1);
        String tooltip = BillingCalculator.INSTANCE.buildGraphTooltip(
                300, 500, 10, 30, resetDate, "#E53935");

        assertFalse(tooltip.contains("<font color='#E53935'>Overage:"));
        assertTrue(tooltip.contains("Projected: ~900 by cycle end"));
        assertTrue(tooltip.contains("Projected overage: ~400 ($16.00)"));
        assertTrue(tooltip.contains("<font color='#E53935'>Projected overage:"));
    }

    @Test
    void buildGraphTooltip_dayZero_rateIsZero_projectedIsZero() {
        LocalDate resetDate = LocalDate.of(2025, 1, 1);
        String tooltip = BillingCalculator.INSTANCE.buildGraphTooltip(
                0, 500, 0, 30, resetDate, "#FF0000");

        assertTrue(tooltip.contains("Day 1 / 30"));
        assertTrue(tooltip.contains("Usage: 0 / 500"));
        assertTrue(tooltip.contains("Projected: ~0 by cycle end"));
        assertFalse(tooltip.contains("Overage:"));
        assertFalse(tooltip.contains("Projected overage:"));
    }

    @Test
    void buildGraphTooltip_bothOverages_showsBoth() {
        // used = 600, entitlement = 500 → overage = 100
        // rate = 600/15 = 40/day → projected = 40*30 = 1200
        // projected overage = 1200 - 500 = 700
        LocalDate resetDate = LocalDate.of(2025, 6, 1);
        String tooltip = BillingCalculator.INSTANCE.buildGraphTooltip(
                600, 500, 15, 30, resetDate, "#FF0000");

        assertTrue(tooltip.contains("Overage: 100 reqs ($4.00)"));
        assertTrue(tooltip.contains("Projected overage: ~700 ($28.00)"));
        assertTrue(tooltip.contains("Resets: Jun 1, 2025"));
    }

    // ── interpolateColor ──────────────────────────────────────────────

    @Test
    void interpolateColor_ratioZero_returnsFromColor() {
        Color from = new Color(100, 150, 200);
        Color to = new Color(200, 50, 0);
        Color result = BillingCalculator.INSTANCE.interpolateColor(from, to, 0.0f);
        assertEquals(from, result);
    }

    @Test
    void interpolateColor_ratioOne_returnsToColor() {
        Color from = new Color(100, 150, 200);
        Color to = new Color(200, 50, 0);
        Color result = BillingCalculator.INSTANCE.interpolateColor(from, to, 1.0f);
        assertEquals(to, result);
    }

    @Test
    void interpolateColor_ratioHalf_returnsMidpoint() {
        Color from = new Color(0, 0, 0);
        Color to = new Color(200, 100, 50);
        Color result = BillingCalculator.INSTANCE.interpolateColor(from, to, 0.5f);
        assertEquals(new Color(100, 50, 25), result);
    }

    @Test
    void interpolateColor_ratioQuarter_interpolatesCorrectly() {
        Color from = new Color(0, 0, 0);
        Color to = new Color(100, 200, 40);
        Color result = BillingCalculator.INSTANCE.interpolateColor(from, to, 0.25f);
        assertEquals(new Color(25, 50, 10), result);
    }

    @Test
    void interpolateColor_sameColors_returnsSameColor() {
        Color color = new Color(128, 128, 128);
        Color result = BillingCalculator.INSTANCE.interpolateColor(color, color, 0.5f);
        assertEquals(color, result);
    }
}
