package com.github.catatafishen.agentbridge.ui.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link TimestampDisplayFormatter}.
 */
@DisplayName("TimestampDisplayFormatter")
class TimestampDisplayFormatterTest {

    @Nested
    @DisplayName("formatIsoTimestamp")
    class FormatIsoTimestamp {

        @Test
        void emptyStringReturnsEmpty() {
            assertEquals("", TimestampDisplayFormatter.formatIsoTimestamp(""));
        }

        @Test
        void invalidFormatReturnsInputAsIs() {
            assertEquals("not-a-date", TimestampDisplayFormatter.formatIsoTimestamp("not-a-date"));
        }

        @Test
        void validIsoReturnsFormattedString() {
            String result = TimestampDisplayFormatter.formatIsoTimestamp("2024-01-15T10:30:00Z");
            // Should contain a time component like HH:mm
            assertTrue(result.matches(".*\\d{2}:\\d{2}.*"), "Expected time in result: " + result);
        }

        @Test
        void isoWithMillisecondsWorks() {
            String result = TimestampDisplayFormatter.formatIsoTimestamp("2024-06-20T14:25:30.123Z");
            assertTrue(result.contains(":"), "Expected time separator: " + result);
        }

        @Test
        void isoWithTimezoneOffsetWorks() {
            String result = TimestampDisplayFormatter.formatIsoTimestamp("2024-03-10T08:00:00+02:00");
            assertTrue(result.contains(":"), "Expected time separator: " + result);
        }
    }

    @Nested
    @DisplayName("formatEpochMillis")
    class FormatEpochMillis {

        @Test
        void zeroReturnsEmpty() {
            assertEquals("", TimestampDisplayFormatter.formatEpochMillis(0L));
        }

        @Test
        void negativeReturnsEmpty() {
            assertEquals("", TimestampDisplayFormatter.formatEpochMillis(-1L));
        }

        @Test
        void validEpochReturnsFormattedString() {
            // 2024-01-15T10:30:00Z in millis
            long millis = 1705313400000L;
            String result = TimestampDisplayFormatter.formatEpochMillis(millis);
            assertTrue(result.matches(".*\\d{2}:\\d{2}.*"), "Expected time in result: " + result);
        }

        @Test
        void recentEpochShowsToday() {
            // Use noon today to avoid midnight rollover flakiness
            long todayNoon = java.time.LocalDate.now()
                .atTime(12, 0)
                .atZone(java.time.ZoneId.systemDefault())
                .toInstant().toEpochMilli();
            String result = TimestampDisplayFormatter.formatEpochMillis(todayNoon);
            assertTrue(result.startsWith("Today "), "Expected 'Today' prefix: " + result);
        }

        @Test
        void yesterdayEpochShowsYesterday() {
            // Use noon yesterday to avoid DST and midnight rollover flakiness
            long yesterdayNoon = java.time.LocalDate.now()
                .minusDays(1)
                .atTime(12, 0)
                .atZone(java.time.ZoneId.systemDefault())
                .toInstant().toEpochMilli();
            String result = TimestampDisplayFormatter.formatEpochMillis(yesterdayNoon);
            assertTrue(result.startsWith("Yesterday "), "Expected 'Yesterday' prefix: " + result);
        }
    }
}
