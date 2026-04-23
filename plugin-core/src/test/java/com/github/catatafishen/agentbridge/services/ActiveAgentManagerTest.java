package com.github.catatafishen.agentbridge.services;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ActiveAgentManagerTest {

    // ── normalizeSharedTurnTimeoutMinutes ────────────────────────────────

    @Nested
    class NormalizeSharedTurnTimeoutMinutes {
        @Test
        void nullReturnsDefault() {
            assertEquals(ActiveAgentManager.DEFAULT_TURN_TIMEOUT_MINUTES,
                ActiveAgentManager.normalizeSharedTurnTimeoutMinutes(null));
        }

        @Test
        void blankReturnsDefault() {
            assertEquals(ActiveAgentManager.DEFAULT_TURN_TIMEOUT_MINUTES,
                ActiveAgentManager.normalizeSharedTurnTimeoutMinutes("  "));
        }

        @ParameterizedTest
        @CsvSource({"1,1", "60,60", "1440,1440", "120,120"})
        void inRangeReturnsSame(String input, int expected) {
            assertEquals(expected, ActiveAgentManager.normalizeSharedTurnTimeoutMinutes(input));
        }

        @Test
        void belowMinClampsToOne() {
            assertEquals(1, ActiveAgentManager.normalizeSharedTurnTimeoutMinutes("0"));
            assertEquals(1, ActiveAgentManager.normalizeSharedTurnTimeoutMinutes("-5"));
        }

        @Test
        void aboveMaxClampsTo1440() {
            assertEquals(1440, ActiveAgentManager.normalizeSharedTurnTimeoutMinutes("1441"));
            assertEquals(1440, ActiveAgentManager.normalizeSharedTurnTimeoutMinutes("99999"));
        }

        @Test
        void nonNumericReturnsDefault() {
            assertEquals(ActiveAgentManager.DEFAULT_TURN_TIMEOUT_MINUTES,
                ActiveAgentManager.normalizeSharedTurnTimeoutMinutes("abc"));
        }
    }

    // ── normalizeSharedInactivityTimeoutSeconds ─────────────────────────

    @Nested
    class NormalizeSharedInactivityTimeoutSeconds {
        @Test
        void nullReturnsDefault() {
            assertEquals(ActiveAgentManager.DEFAULT_INACTIVITY_TIMEOUT_SECONDS,
                ActiveAgentManager.normalizeSharedInactivityTimeoutSeconds(null));
        }

        @Test
        void blankReturnsDefault() {
            assertEquals(ActiveAgentManager.DEFAULT_INACTIVITY_TIMEOUT_SECONDS,
                ActiveAgentManager.normalizeSharedInactivityTimeoutSeconds(""));
        }

        @ParameterizedTest
        @CsvSource({"30,30", "3000,3000", "86400,86400"})
        void inRangeReturnsSame(String input, int expected) {
            assertEquals(expected, ActiveAgentManager.normalizeSharedInactivityTimeoutSeconds(input));
        }

        @Test
        void belowMinClampsTo30() {
            assertEquals(30, ActiveAgentManager.normalizeSharedInactivityTimeoutSeconds("0"));
            assertEquals(30, ActiveAgentManager.normalizeSharedInactivityTimeoutSeconds("29"));
            assertEquals(30, ActiveAgentManager.normalizeSharedInactivityTimeoutSeconds("-1"));
        }

        @Test
        void aboveMaxClampsTo86400() {
            assertEquals(86400, ActiveAgentManager.normalizeSharedInactivityTimeoutSeconds("86401"));
        }

        @Test
        void nonNumericReturnsDefault() {
            assertEquals(ActiveAgentManager.DEFAULT_INACTIVITY_TIMEOUT_SECONDS,
                ActiveAgentManager.normalizeSharedInactivityTimeoutSeconds("xyz"));
        }
    }

    // ── normalizeSharedMaxToolCallsPerTurn ───────────────────────────────

    @Nested
    class NormalizeSharedMaxToolCallsPerTurn {
        @Test
        void nullWithDefaultFallback() {
            int result = ActiveAgentManager.normalizeSharedMaxToolCallsPerTurn(
                null, ActiveAgentManager.DEFAULT_MAX_TOOL_CALLS_PER_TURN);
            assertEquals(ActiveAgentManager.DEFAULT_MAX_TOOL_CALLS_PER_TURN, result);
        }

        @Test
        void validValue() {
            assertEquals(50, ActiveAgentManager.normalizeSharedMaxToolCallsPerTurn("50",
                ActiveAgentManager.DEFAULT_MAX_TOOL_CALLS_PER_TURN));
        }

        @Test
        void zeroReturnsClamped() {
            // clamp(0, 0, 1000) = 0 (0 is the min)
            assertEquals(0, ActiveAgentManager.normalizeSharedMaxToolCallsPerTurn("0",
                ActiveAgentManager.DEFAULT_MAX_TOOL_CALLS_PER_TURN));
        }

        @Test
        void negativeClampedToZero() {
            assertEquals(0, ActiveAgentManager.normalizeSharedMaxToolCallsPerTurn("-5",
                ActiveAgentManager.DEFAULT_MAX_TOOL_CALLS_PER_TURN));
        }

        @Test
        void aboveMaxReturnsSame() {
            // no upper clamp — value is returned as-is (only Math.max(0, x) applies)
            assertEquals(9999, ActiveAgentManager.normalizeSharedMaxToolCallsPerTurn("9999",
                ActiveAgentManager.DEFAULT_MAX_TOOL_CALLS_PER_TURN));
        }

        @Test
        void nonNumericUsesDefaultNotLegacy() {
            // storedCount != null so legacyCount is ignored; parseIntOrDefault returns DEFAULT (0)
            assertEquals(0, ActiveAgentManager.normalizeSharedMaxToolCallsPerTurn("abc", 42));
        }

        @Test
        void nullUsesLegacyCount() {
            assertEquals(42, ActiveAgentManager.normalizeSharedMaxToolCallsPerTurn(null, 42));
        }

        @Test
        void nullWithNegativeLegacyClampedToZero() {
            assertEquals(0, ActiveAgentManager.normalizeSharedMaxToolCallsPerTurn(null, -5));
        }
    }

    // ── parseIntOrDefault ───────────────────────────────────────────────

    @Nested
    class ParseIntOrDefault {
        @Test
        void nullReturnsDefault() {
            assertEquals(7, ActiveAgentManager.parseIntOrDefault(null, 7));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"  ", "\t"})
        void blankishReturnsDefault(String input) {
            assertEquals(42, ActiveAgentManager.parseIntOrDefault(input, 42));
        }

        @Test
        void validInt() {
            assertEquals(123, ActiveAgentManager.parseIntOrDefault("123", 0));
        }

        @Test
        void negativeInt() {
            assertEquals(-5, ActiveAgentManager.parseIntOrDefault("-5", 0));
        }

        @Test
        void nonNumericReturnsDefault() {
            assertEquals(99, ActiveAgentManager.parseIntOrDefault("hello", 99));
        }

        @Test
        void zeroValue() {
            assertEquals(0, ActiveAgentManager.parseIntOrDefault("0", 99));
        }
    }

    // ── clamp ───────────────────────────────────────────────────────────

    @Nested
    class Clamp {
        @Test
        void inRange() {
            assertEquals(5, ActiveAgentManager.clamp(5, 1, 10));
        }

        @Test
        void belowMin() {
            assertEquals(1, ActiveAgentManager.clamp(-5, 1, 10));
        }

        @Test
        void aboveMax() {
            assertEquals(10, ActiveAgentManager.clamp(99, 1, 10));
        }

        @Test
        void atMin() {
            assertEquals(1, ActiveAgentManager.clamp(1, 1, 10));
        }

        @Test
        void atMax() {
            assertEquals(10, ActiveAgentManager.clamp(10, 1, 10));
        }
    }

    // ── Default constants ───────────────────────────────────────────────

    @Nested
    class DefaultConstants {
        @Test
        void turnTimeoutMinutes() {
            assertEquals(120, ActiveAgentManager.DEFAULT_TURN_TIMEOUT_MINUTES);
        }

        @Test
        void inactivityTimeoutSeconds() {
            assertEquals(3000, ActiveAgentManager.DEFAULT_INACTIVITY_TIMEOUT_SECONDS);
        }

        @Test
        void maxToolCallsPerTurn() {
            assertEquals(0, ActiveAgentManager.DEFAULT_MAX_TOOL_CALLS_PER_TURN);
        }
    }
}
