package com.github.catatafishen.agentbridge.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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
        assertEquals("60m 0s", TimerDisplayFormatter.INSTANCE.formatElapsedTime(3600));
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
        assertEquals("\u2022 1 tools", TimerDisplayFormatter.INSTANCE.formatToolCount(1));
    }

    @Test
    void formatToolCount_hundred() {
        assertEquals("\u2022 100 tools", TimerDisplayFormatter.INSTANCE.formatToolCount(100));
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
}
