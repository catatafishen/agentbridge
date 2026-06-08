package com.github.catatafishen.agentbridge.psi.tools.graph;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link QueryCodeGraphTool#parseSinceMs(String)} and
 * {@link QueryCodeGraphTool#parseSinceIso(String)} helpers.
 *
 * <p>These power the {@code since} parameter on the {@code recent_changes_impact}
 * and {@code affected_tests} queries. Wrong parsing produces silently empty results,
 * so we lock the contract down with explicit cases.
 */
class QueryCodeGraphToolParseSinceTest {

    @Test
    void parsesHourSuffix() {
        long now = System.currentTimeMillis();
        long since = QueryCodeGraphTool.parseSinceMs("2h");
        long elapsed = now - since;
        assertTrue(elapsed >= 2 * 3_600_000L - 1_000 && elapsed <= 2 * 3_600_000L + 1_000,
            "Expected ~2h ago, got elapsed=" + elapsed + "ms");
    }

    @Test
    void parsesMinuteAndDayAndSecondSuffixes() {
        long now = System.currentTimeMillis();
        assertCloseTo(now - 30 * 60_000L, QueryCodeGraphTool.parseSinceMs("30m"));
        assertCloseTo(now - 86_400_000L, QueryCodeGraphTool.parseSinceMs("1d"));
        assertCloseTo(now - 45_000L, QueryCodeGraphTool.parseSinceMs("45s"));
    }

    @Test
    void parsesIsoDate() {
        long expected = java.time.LocalDate.parse("2026-06-08")
            .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
        assertEquals(expected, QueryCodeGraphTool.parseSinceMs("2026-06-08"));
    }

    @Test
    void parsesIsoInstant() {
        long expected = Instant.parse("2026-06-08T15:30:00Z").toEpochMilli();
        assertEquals(expected, QueryCodeGraphTool.parseSinceMs("2026-06-08T15:30:00Z"));
    }

    @Test
    void returnsZeroForBlankOrInvalid() {
        assertEquals(0L, QueryCodeGraphTool.parseSinceMs(null));
        assertEquals(0L, QueryCodeGraphTool.parseSinceMs(""));
        assertEquals(0L, QueryCodeGraphTool.parseSinceMs("   "));
        assertEquals(0L, QueryCodeGraphTool.parseSinceMs("garbage"));
    }

    @Test
    void parseSinceIsoReturnsValidIsoString() {
        String iso = QueryCodeGraphTool.parseSinceIso("1h");
        // Must round-trip through Instant.parse without throwing
        assertEquals(QueryCodeGraphTool.parseSinceMs("1h") / 1000L,
            Instant.parse(iso).toEpochMilli() / 1000L);
    }

    private static void assertCloseTo(long expected, long actual) {
        long diff = Math.abs(expected - actual);
        assertTrue(diff < 2_000, "Expected ~" + expected + ", got " + actual + " (diff " + diff + "ms)");
    }
}
