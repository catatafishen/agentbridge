package com.github.catatafishen.agentbridge.ui.statistics;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the extracted static helper methods in {@link ToolStatisticsPanel}.
 * These methods are package-private, allowing direct testing without instantiating
 * the Swing panel (which requires IntelliJ's UI framework).
 */
class ToolStatisticsPanelTest {

    @Test
    void computeSinceLastHour() {
        Instant now = Instant.parse("2026-01-15T12:00:00Z");
        String since = ToolStatisticsPanel.computeSince(0, now);
        assertNotNull(since);
        assertEquals(now.minus(1, ChronoUnit.HOURS).toString(), since);
    }

    @Test
    void computeSinceLast24Hours() {
        Instant now = Instant.parse("2026-01-15T12:00:00Z");
        String since = ToolStatisticsPanel.computeSince(1, now);
        assertNotNull(since);
        assertEquals(now.minus(24, ChronoUnit.HOURS).toString(), since);
    }

    @Test
    void computeSinceLast7Days() {
        Instant now = Instant.parse("2026-01-15T12:00:00Z");
        String since = ToolStatisticsPanel.computeSince(2, now);
        assertNotNull(since);
        assertEquals(now.minus(7, ChronoUnit.DAYS).toString(), since);
    }

    @Test
    void computeSinceAllTimeReturnsNull() {
        Instant now = Instant.now();
        assertNull(ToolStatisticsPanel.computeSince(3, now));
    }

    @Test
    void computeSinceUnknownIndexReturnsNull() {
        assertNull(ToolStatisticsPanel.computeSince(99, Instant.now()));
    }

    @Test
    void formatSummaryWithData() {
        Map<String, Long> summary = new LinkedHashMap<>();
        summary.put("totalCalls", 42L);
        summary.put("totalErrors", 3L);
        summary.put("totalInputBytes", 10240L);
        summary.put("totalOutputBytes", 2097152L);
        summary.put("totalDurationMs", 5000L);

        String result = ToolStatisticsPanel.formatSummary(summary);
        assertTrue(result.contains("42 calls"));
        assertTrue(result.contains("3 errors"));
        assertTrue(result.contains("10.0 KB"));
        assertTrue(result.contains("2.0 MB"));
    }

    @Test
    void formatSummaryEmptyMap() {
        assertEquals("No data", ToolStatisticsPanel.formatSummary(Map.of()));
    }

    @Test
    void formatSummaryWithZeroes() {
        Map<String, Long> summary = new LinkedHashMap<>();
        summary.put("totalCalls", 0L);
        summary.put("totalErrors", 0L);
        summary.put("totalInputBytes", 0L);
        summary.put("totalOutputBytes", 0L);

        String result = ToolStatisticsPanel.formatSummary(summary);
        assertTrue(result.contains("0 calls"));
        assertTrue(result.contains("0 errors"));
        assertTrue(result.contains("0 B"));
    }

    @Test
    void formatSummaryHandlesMissingKeys() {
        Map<String, Long> summary = new LinkedHashMap<>();
        summary.put("totalCalls", 5L);
        // Other keys missing — should default to 0

        String result = ToolStatisticsPanel.formatSummary(summary);
        assertTrue(result.contains("5 calls"));
        assertTrue(result.contains("0 errors"));
    }
}
