package com.github.catatafishen.agentbridge.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimerDisplayFormatterTest {

    // ── formatElapsedTime ───────────────────────────────────────────────

    @Test
    void formatElapsedTime_zeroSeconds() {
        assertEquals("0s", TimerDisplayFormatter.INSTANCE.formatElapsedTime(0));
    }

    @Test
    void formatElapsedTime_oneSecond() {
        assertEquals("1s", TimerDisplayFormatter.INSTANCE.formatElapsedTime(1));
    }

    @Test
    void formatElapsedTime_fiftyNineSeconds() {
        assertEquals("59s", TimerDisplayFormatter.INSTANCE.formatElapsedTime(59));
    }

    @Test
    void formatElapsedTime_exactlyOneMinute() {
        assertEquals("1m 0s", TimerDisplayFormatter.INSTANCE.formatElapsedTime(60));
    }

    @Test
    void formatElapsedTime_oneMinuteOneSecond() {
        assertEquals("1m 1s", TimerDisplayFormatter.INSTANCE.formatElapsedTime(61));
    }

    @Test
    void formatElapsedTime_twoMinutes() {
        assertEquals("2m 0s", TimerDisplayFormatter.INSTANCE.formatElapsedTime(120));
    }

    @Test
    void formatElapsedTime_oneHour() {
        assertEquals("1h 0m", TimerDisplayFormatter.INSTANCE.formatElapsedTime(3600));
    }

    @Test
    void formatElapsedTime_oneHourThirtyMinutes() {
        assertEquals("1h 30m", TimerDisplayFormatter.INSTANCE.formatElapsedTime(5400));
    }

    @Test
    void formatElapsedTime_twoHours() {
        assertEquals("2h 0m", TimerDisplayFormatter.INSTANCE.formatElapsedTime(7200));
    }

    @Test
    void formatElapsedTime_justUnderAnHour() {
        assertEquals("59m 59s", TimerDisplayFormatter.INSTANCE.formatElapsedTime(3599));
    }

    // ── formatLinesAdded ────────────────────────────────────────────────

    @Test
    void formatLinesAdded_zero_returnsEmpty() {
        assertEquals("", TimerDisplayFormatter.INSTANCE.formatLinesAdded(0));
    }

    @Test
    void formatLinesAdded_one_returnsPlus1() {
        assertEquals("+1", TimerDisplayFormatter.INSTANCE.formatLinesAdded(1));
    }

    @Test
    void formatLinesAdded_negative_returnsEmpty() {
        assertEquals("", TimerDisplayFormatter.INSTANCE.formatLinesAdded(-1));
    }

    @Test
    void formatLinesAdded_largeNumber() {
        assertEquals("+999999", TimerDisplayFormatter.INSTANCE.formatLinesAdded(999999));
    }

    // ── formatLinesRemoved ──────────────────────────────────────────────

    @Test
    void formatLinesRemoved_zero_returnsEmpty() {
        assertEquals("", TimerDisplayFormatter.INSTANCE.formatLinesRemoved(0));
    }

    @Test
    void formatLinesRemoved_one_returnsMinus1() {
        assertEquals("-1", TimerDisplayFormatter.INSTANCE.formatLinesRemoved(1));
    }

    @Test
    void formatLinesRemoved_negative_returnsEmpty() {
        assertEquals("", TimerDisplayFormatter.INSTANCE.formatLinesRemoved(-1));
    }

    @Test
    void formatLinesRemoved_largeNumber() {
        assertEquals("-500000", TimerDisplayFormatter.INSTANCE.formatLinesRemoved(500000));
    }

    // ── formatToolCount ─────────────────────────────────────────────────

    @Test
    void formatToolCount_zero_returnsEmpty() {
        assertEquals("", TimerDisplayFormatter.INSTANCE.formatToolCount(0));
    }

    @Test
    void formatToolCount_one_returnsBulletOneTool() {
        assertEquals("• 1 tools", TimerDisplayFormatter.INSTANCE.formatToolCount(1));
    }

    @Test
    void formatToolCount_hundred() {
        assertEquals("• 100 tools", TimerDisplayFormatter.INSTANCE.formatToolCount(100));
    }

    @Test
    void formatToolCount_negative_returnsEmpty() {
        assertEquals("", TimerDisplayFormatter.INSTANCE.formatToolCount(-1));
    }

    // ── hasDisplayableUsage ─────────────────────────────────────────────

    @Test
    void hasDisplayableUsage_isRunning_alwaysFalse() {
        assertFalse(TimerDisplayFormatter.INSTANCE.hasDisplayableUsage(true, 1.0, 100, 100));
    }

    @Test
    void hasDisplayableUsage_isRunningWithNullCost_false() {
        assertFalse(TimerDisplayFormatter.INSTANCE.hasDisplayableUsage(true, null, 500, 500));
    }

    @Test
    void hasDisplayableUsage_notRunning_allZerosNullCost_false() {
        assertFalse(TimerDisplayFormatter.INSTANCE.hasDisplayableUsage(false, null, 0, 0));
    }

    @Test
    void hasDisplayableUsage_notRunning_zeroCostZeroTokens_false() {
        assertFalse(TimerDisplayFormatter.INSTANCE.hasDisplayableUsage(false, 0.0, 0, 0));
    }

    @Test
    void hasDisplayableUsage_notRunning_positiveCostZeroTokens_true() {
        assertTrue(TimerDisplayFormatter.INSTANCE.hasDisplayableUsage(false, 0.01, 0, 0));
    }

    @Test
    void hasDisplayableUsage_notRunning_nullCostPositiveInputTokens_true() {
        assertTrue(TimerDisplayFormatter.INSTANCE.hasDisplayableUsage(false, null, 100, 0));
    }

    @Test
    void hasDisplayableUsage_notRunning_nullCostPositiveOutputTokens_true() {
        assertTrue(TimerDisplayFormatter.INSTANCE.hasDisplayableUsage(false, null, 0, 200));
    }

    @Test
    void hasDisplayableUsage_notRunning_nullCostBothTokensPositive_true() {
        assertTrue(TimerDisplayFormatter.INSTANCE.hasDisplayableUsage(false, null, 50, 50));
    }

    @Test
    void hasDisplayableUsage_notRunning_positiveCostAndTokens_true() {
        assertTrue(TimerDisplayFormatter.INSTANCE.hasDisplayableUsage(false, 0.5, 100, 200));
    }

    @Test
    void hasDisplayableUsage_notRunning_negativeCostZeroTokens_false() {
        assertFalse(TimerDisplayFormatter.INSTANCE.hasDisplayableUsage(false, -0.01, 0, 0));
    }

    // ── formatTokenCount ────────────────────────────────────────────────

    @Test
    void formatTokenCount_zero() {
        assertEquals("0", TimerDisplayFormatter.INSTANCE.formatTokenCount(0));
    }

    @Test
    void formatTokenCount_below1k() {
        assertEquals("999", TimerDisplayFormatter.INSTANCE.formatTokenCount(999));
    }

    @Test
    void formatTokenCount_exactly1k() {
        assertEquals("1.0k", TimerDisplayFormatter.INSTANCE.formatTokenCount(1000));
    }

    @Test
    void formatTokenCount_fractionalK() {
        assertEquals("1.2k", TimerDisplayFormatter.INSTANCE.formatTokenCount(1234));
    }

    @Test
    void formatTokenCount_exactly1M() {
        assertEquals("1.0M", TimerDisplayFormatter.INSTANCE.formatTokenCount(1_000_000));
    }

    @Test
    void formatTokenCount_fractionalM() {
        assertEquals("1.5M", TimerDisplayFormatter.INSTANCE.formatTokenCount(1_500_000));
    }

    // ── formatCost ──────────────────────────────────────────────────────

    @Test
    void formatCost_zero() {
        assertEquals("$0.00", TimerDisplayFormatter.INSTANCE.formatCost(0.0));
    }

    @Test
    void formatCost_negative() {
        assertEquals("$0.00", TimerDisplayFormatter.INSTANCE.formatCost(-1.0));
    }

    @Test
    void formatCost_subCent() {
        assertEquals("$0.005", TimerDisplayFormatter.INSTANCE.formatCost(0.005));
    }

    @Test
    void formatCost_normal() {
        assertEquals("$1.23", TimerDisplayFormatter.INSTANCE.formatCost(1.23));
    }

    @Test
    void formatCost_wholeDollar() {
        assertEquals("$5.00", TimerDisplayFormatter.INSTANCE.formatCost(5.0));
    }

    // ── formatLinesChanged ──────────────────────────────────────────────

    @Test
    void formatLinesChanged_bothZero() {
        assertEquals("", TimerDisplayFormatter.INSTANCE.formatLinesChanged(0, 0));
    }

    @Test
    void formatLinesChanged_onlyAdded() {
        assertEquals("+10", TimerDisplayFormatter.INSTANCE.formatLinesChanged(10, 0));
    }

    @Test
    void formatLinesChanged_onlyRemoved() {
        assertEquals("-5", TimerDisplayFormatter.INSTANCE.formatLinesChanged(0, 5));
    }

    @Test
    void formatLinesChanged_bothNonZero() {
        assertEquals("+42 / -7", TimerDisplayFormatter.INSTANCE.formatLinesChanged(42, 7));
    }

    // ── formatDiffCountHtml ─────────────────────────────────────────────

    private static final java.awt.Color GREEN = new java.awt.Color(0x00, 0xAA, 0x00);
    private static final java.awt.Color RED = new java.awt.Color(0xCC, 0x00, 0x00);

    @Test
    void formatDiffCountHtml_bothZero_returnsEmpty() {
        assertEquals("", TimerDisplayFormatter.formatDiffCountHtml(0, 0, GREEN, RED));
    }

    @Test
    void formatDiffCountHtml_onlyAdded() {
        String result = TimerDisplayFormatter.formatDiffCountHtml(5, 0, GREEN, RED);
        assertTrue(result.startsWith("<html>"), "should be wrapped in html");
        assertTrue(result.contains("+5"), "should contain +5");
        assertFalse(result.contains("−"), "should not contain minus sign");
    }

    @Test
    void formatDiffCountHtml_onlyRemoved() {
        String result = TimerDisplayFormatter.formatDiffCountHtml(0, 3, GREEN, RED);
        assertTrue(result.startsWith("<html>"));
        assertTrue(result.contains("−3"), "should contain −3");
        assertFalse(result.contains("+"), "should not contain plus sign");
    }

    @Test
    void formatDiffCountHtml_bothNonZero() {
        String result = TimerDisplayFormatter.formatDiffCountHtml(10, 4, GREEN, RED);
        assertTrue(result.startsWith("<html>"));
        assertTrue(result.contains("+10"));
        assertTrue(result.contains("−4"));
        assertTrue(result.contains("#00aa00"), "should contain green hex color");
        assertTrue(result.contains("#cc0000"), "should contain red hex color");
    }

    @Test
    void formatDiffCountHtml_negativeValuesIgnored() {
        assertEquals("", TimerDisplayFormatter.formatDiffCountHtml(-1, -1, GREEN, RED));
    }
}
