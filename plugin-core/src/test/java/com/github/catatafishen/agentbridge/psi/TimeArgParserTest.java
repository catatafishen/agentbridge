package com.github.catatafishen.agentbridge.psi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TimeArgParser}.
 */
class TimeArgParserTest {

    /**
     * Maximum tolerance in seconds for relative-time assertions.
     */
    private static final long TOLERANCE_SECONDS = 2;

    private static void assertApproximatelyEqual(LocalDateTime expected, LocalDateTime actual) {
        long diff = Math.abs(ChronoUnit.SECONDS.between(expected, actual));
        assertTrue(diff <= TOLERANCE_SECONDS,
            "Expected " + expected + " but got " + actual + " (diff=" + diff + "s, tolerance=" + TOLERANCE_SECONDS + "s)");
    }

    // -----------------------------------------------------------------------
    // parseLocalDateTime
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("parseLocalDateTime — null and blank input")
    class ParseLocalDateTimeNullAndBlank {

        @Test
        void nullReturnsNull() {
            assertNull(TimeArgParser.parseLocalDateTime(null));
        }

        @Test
        void emptyStringReturnsNull() {
            assertNull(TimeArgParser.parseLocalDateTime(""));
        }

        @Test
        void whitespaceOnlyReturnsNull() {
            assertNull(TimeArgParser.parseLocalDateTime("   "));
        }
    }

    @Nested
    @DisplayName("parseLocalDateTime — relative time")
    class ParseLocalDateTimeRelative {

        @Test
        void fiveMinutes() {
            LocalDateTime result = TimeArgParser.parseLocalDateTime("5m");
            assertApproximatelyEqual(LocalDateTime.now().minusMinutes(5), result);
        }

        @Test
        void twoHours() {
            LocalDateTime result = TimeArgParser.parseLocalDateTime("2h");
            assertApproximatelyEqual(LocalDateTime.now().minusHours(2), result);
        }

        @Test
        void thirtySeconds() {
            LocalDateTime result = TimeArgParser.parseLocalDateTime("30s");
            assertApproximatelyEqual(LocalDateTime.now().minusSeconds(30), result);
        }

        @Test
        void oneHourLongForm() {
            LocalDateTime result = TimeArgParser.parseLocalDateTime("1hour");
            assertApproximatelyEqual(LocalDateTime.now().minusHours(1), result);
        }

        @Test
        void twoHoursLongForm() {
            LocalDateTime result = TimeArgParser.parseLocalDateTime("2hours");
            assertApproximatelyEqual(LocalDateTime.now().minusHours(2), result);
        }

        @Test
        void threeMin() {
            LocalDateTime result = TimeArgParser.parseLocalDateTime("3min");
            assertApproximatelyEqual(LocalDateTime.now().minusMinutes(3), result);
        }

        @Test
        void fiveMinutesLongForm() {
            LocalDateTime result = TimeArgParser.parseLocalDateTime("5minutes");
            assertApproximatelyEqual(LocalDateTime.now().minusMinutes(5), result);
        }

        @Test
        void tenSec() {
            LocalDateTime result = TimeArgParser.parseLocalDateTime("10sec");
            assertApproximatelyEqual(LocalDateTime.now().minusSeconds(10), result);
        }

        @Test
        void ninetySecondsLongForm() {
            LocalDateTime result = TimeArgParser.parseLocalDateTime("90seconds");
            assertApproximatelyEqual(LocalDateTime.now().minusSeconds(90), result);
        }

        @Test
        void zeroMinutesIsApproximatelyNow() {
            LocalDateTime result = TimeArgParser.parseLocalDateTime("0m");
            assertApproximatelyEqual(LocalDateTime.now(), result);
        }

        @ParameterizedTest(name = "case insensitive: \"{0}\"")
        @CsvSource({
            "5M,   5",
            "2H,   2",
            "30S, 30",
        })
        void caseInsensitive(String input, @SuppressWarnings("unused") long amount) {
            LocalDateTime result = TimeArgParser.parseLocalDateTime(input);
            assertNotNull(result);
            // Just verify it parsed without error and is in the past
            assertTrue(result.isBefore(LocalDateTime.now().plusSeconds(TOLERANCE_SECONDS)));
        }
    }

    @Nested
    @DisplayName("parseLocalDateTime — ISO 8601")
    class ParseLocalDateTimeIso {

        @Test
        void iso8601WithZ() {
            LocalDateTime result = TimeArgParser.parseLocalDateTime("2026-03-28T16:57:30Z");
            // Z means UTC — converted to local zone
            LocalDateTime expected = LocalDateTime.ofInstant(
                Instant.parse("2026-03-28T16:57:30Z"), ZoneId.systemDefault());
            assertEquals(expected, result);
        }

        @Test
        void iso8601WithoutZ() {
            LocalDateTime result = TimeArgParser.parseLocalDateTime("2026-03-28T16:57:30");
            assertEquals(LocalDateTime.of(2026, 3, 28, 16, 57, 30), result);
        }
    }

    @Nested
    @DisplayName("parseLocalDateTime — datetime with space")
    class ParseLocalDateTimeWithSpace {

        @Test
        void datetimeSpace() {
            LocalDateTime result = TimeArgParser.parseLocalDateTime("2026-03-28 16:57:30");
            assertEquals(LocalDateTime.of(2026, 3, 28, 16, 57, 30), result);
        }
    }

    @Nested
    @DisplayName("parseLocalDateTime — date only")
    class ParseLocalDateTimeDateOnly {

        @Test
        void dateOnlyStartOfDay() {
            LocalDateTime result = TimeArgParser.parseLocalDateTime("2026-03-28");
            assertEquals(LocalDateTime.of(2026, 3, 28, 0, 0, 0), result);
        }
    }

    @Nested
    @DisplayName("parseLocalDateTime — time today")
    class ParseLocalDateTimeTimeToday {

        @Test
        void timeWithSeconds() {
            LocalDateTime result = TimeArgParser.parseLocalDateTime("16:57:30");
            assertEquals(LocalDate.now().atTime(LocalTime.of(16, 57, 30)), result);
        }

        @Test
        void timeWithoutSeconds() {
            LocalDateTime result = TimeArgParser.parseLocalDateTime("16:57");
            assertEquals(LocalDate.now().atTime(LocalTime.of(16, 57)), result);
        }
    }

    @Nested
    @DisplayName("parseLocalDateTime — invalid input")
    class ParseLocalDateTimeInvalid {

        @Test
        void notATime() {
            assertThrows(IllegalArgumentException.class,
                () -> TimeArgParser.parseLocalDateTime("not-a-time"));
        }

        @Test
        void randomLetters() {
            assertThrows(IllegalArgumentException.class,
                () -> TimeArgParser.parseLocalDateTime("abc"));
        }

        @Test
        void invalidTimeValues() {
            assertThrows(IllegalArgumentException.class,
                () -> TimeArgParser.parseLocalDateTime("25:99"));
        }
    }

    // -----------------------------------------------------------------------
    // parseInstant
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("parseInstant")
    class ParseInstant {

        @Test
        void nullReturnsNull() {
            assertNull(TimeArgParser.parseInstant(null));
        }

        @Test
        void validTimeReturnsNonNull() {
            Instant result = TimeArgParser.parseInstant("2026-03-28 16:57:30");
            assertNotNull(result);
        }

        @Test
        void delegatesCorrectly() {
            Instant result = TimeArgParser.parseInstant("2026-03-28T16:57:30Z");
            // The parser converts UTC → local → back to Instant via system zone,
            // so the epoch should match the original UTC instant.
            assertEquals(Instant.parse("2026-03-28T16:57:30Z"), result);
        }
    }

    // -----------------------------------------------------------------------
    // parseEpochMillis
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("parseEpochMillis")
    class ParseEpochMillis {

        @Test
        void nullReturnsMinusOne() {
            assertEquals(-1L, TimeArgParser.parseEpochMillis(null));
        }

        @Test
        void emptyReturnsMinusOne() {
            assertEquals(-1L, TimeArgParser.parseEpochMillis(""));
        }

        @Test
        void validDateReturnsPositiveMillis() {
            long millis = TimeArgParser.parseEpochMillis("2026-03-28 16:57:30");
            assertTrue(millis > 0, "Epoch millis should be positive for a future date");
        }

        @Test
        void invalidInputThrows() {
            assertThrows(IllegalArgumentException.class,
                () -> TimeArgParser.parseEpochMillis("not-a-time"));
        }
    }
}
