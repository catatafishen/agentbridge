package com.github.catatafishen.agentbridge.services;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for pure static utility methods in {@link ActiveAgentManager}.
 *
 * <p>Package-private methods are called directly (same package).
 * Private methods are accessed via reflection.</p>
 */
class ActiveAgentManagerStaticMethodsTest {

    // ── Reflection handles for private helpers ──────────────

    private static final Method PARSE_INT_OR_DEFAULT;
    private static final Method CLAMP;

    static {
        try {
            PARSE_INT_OR_DEFAULT = ActiveAgentManager.class
                    .getDeclaredMethod("parseIntOrDefault", String.class, int.class);
            PARSE_INT_OR_DEFAULT.setAccessible(true);

            CLAMP = ActiveAgentManager.class
                    .getDeclaredMethod("clamp", int.class, int.class, int.class);
            CLAMP.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("Method not found — signature changed?", e);
        }
    }

    // ── normalizeSharedTurnTimeoutMinutes ────────────────────

    @Nested
    @DisplayName("normalizeSharedTurnTimeoutMinutes")
    class NormalizeTurnTimeout {

        @Test
        @DisplayName("null input → default (120)")
        void nullInput_returnsDefault() {
            assertEquals(120, ActiveAgentManager.normalizeSharedTurnTimeoutMinutes(null));
        }

        @Test
        @DisplayName("blank string → default (120)")
        void blankString_returnsDefault() {
            assertEquals(120, ActiveAgentManager.normalizeSharedTurnTimeoutMinutes(""));
        }

        @Test
        @DisplayName("\"60\" → 60")
        void validValue_returnsParsed() {
            assertEquals(60, ActiveAgentManager.normalizeSharedTurnTimeoutMinutes("60"));
        }

        @Test
        @DisplayName("\"0\" → 1 (clamped to min)")
        void zero_clampedToMin() {
            assertEquals(1, ActiveAgentManager.normalizeSharedTurnTimeoutMinutes("0"));
        }

        @Test
        @DisplayName("\"-5\" → 1 (clamped to min)")
        void negative_clampedToMin() {
            assertEquals(1, ActiveAgentManager.normalizeSharedTurnTimeoutMinutes("-5"));
        }

        @Test
        @DisplayName("\"1440\" → 1440 (max boundary)")
        void maxBoundary_returnsMax() {
            assertEquals(1440, ActiveAgentManager.normalizeSharedTurnTimeoutMinutes("1440"));
        }

        @Test
        @DisplayName("\"2000\" → 1440 (clamped to max)")
        void aboveMax_clampedToMax() {
            assertEquals(1440, ActiveAgentManager.normalizeSharedTurnTimeoutMinutes("2000"));
        }

        @Test
        @DisplayName("\"abc\" → 120 (parse failure → default)")
        void nonNumeric_returnsDefault() {
            assertEquals(120, ActiveAgentManager.normalizeSharedTurnTimeoutMinutes("abc"));
        }
    }

    // ── normalizeSharedInactivityTimeoutSeconds ─────────────

    @Nested
    @DisplayName("normalizeSharedInactivityTimeoutSeconds")
    class NormalizeInactivityTimeout {

        @Test
        @DisplayName("null → default (3000)")
        void nullInput_returnsDefault() {
            assertEquals(3000, ActiveAgentManager.normalizeSharedInactivityTimeoutSeconds(null));
        }

        @Test
        @DisplayName("\"300\" → 300")
        void validValue_returnsParsed() {
            assertEquals(300, ActiveAgentManager.normalizeSharedInactivityTimeoutSeconds("300"));
        }

        @Test
        @DisplayName("\"10\" → 30 (clamped to min)")
        void belowMin_clampedToMin() {
            assertEquals(30, ActiveAgentManager.normalizeSharedInactivityTimeoutSeconds("10"));
        }

        @Test
        @DisplayName("\"100000\" → 86400 (clamped to max)")
        void aboveMax_clampedToMax() {
            assertEquals(86400, ActiveAgentManager.normalizeSharedInactivityTimeoutSeconds("100000"));
        }
    }

    // ── normalizeSharedMaxToolCallsPerTurn ───────────────────

    @Nested
    @DisplayName("normalizeSharedMaxToolCallsPerTurn")
    class NormalizeMaxToolCalls {

        @Test
        @DisplayName("storedCount=\"10\", legacy=5 → 10 (stored takes priority)")
        void storedPresent_takesPriority() {
            assertEquals(10, ActiveAgentManager.normalizeSharedMaxToolCallsPerTurn("10", 5));
        }

        @Test
        @DisplayName("storedCount=null, legacy=5 → 5 (legacy fallback)")
        void storedNull_fallsBackToLegacy() {
            assertEquals(5, ActiveAgentManager.normalizeSharedMaxToolCallsPerTurn(null, 5));
        }

        @Test
        @DisplayName("storedCount=\"0\", legacy=5 → 0 (stored zero is valid)")
        void storedZero_isValid() {
            assertEquals(0, ActiveAgentManager.normalizeSharedMaxToolCallsPerTurn("0", 5));
        }

        @Test
        @DisplayName("storedCount=\"-3\", legacy=5 → 0 (clamped to 0)")
        void storedNegative_clampedToZero() {
            assertEquals(0, ActiveAgentManager.normalizeSharedMaxToolCallsPerTurn("-3", 5));
        }

        @Test
        @DisplayName("storedCount=null, legacy=-1 → 0 (legacy clamped to 0)")
        void legacyNegative_clampedToZero() {
            assertEquals(0, ActiveAgentManager.normalizeSharedMaxToolCallsPerTurn(null, -1));
        }

        @Test
        @DisplayName("storedCount=\"abc\", legacy=7 → 0 (parse failure → default 0)")
        void storedNonNumeric_returnsDefaultZero() {
            assertEquals(0, ActiveAgentManager.normalizeSharedMaxToolCallsPerTurn("abc", 7));
        }
    }

    // ── parseIntOrDefault (private) ─────────────────────────

    @Nested
    @DisplayName("parseIntOrDefault")
    class ParseIntOrDefault {

        @Test
        @DisplayName("null → defaultValue")
        void nullValue_returnsDefault() throws Exception {
            assertEquals(99, invokeParseIntOrDefault(null, 99));
        }

        @Test
        @DisplayName("empty string → defaultValue")
        void emptyString_returnsDefault() throws Exception {
            assertEquals(99, invokeParseIntOrDefault("", 99));
        }

        @Test
        @DisplayName("blank (spaces) → defaultValue")
        void blankString_returnsDefault() throws Exception {
            assertEquals(99, invokeParseIntOrDefault("   ", 99));
        }

        @Test
        @DisplayName("\"42\" → 42")
        void validNumber_returnsParsed() throws Exception {
            assertEquals(42, invokeParseIntOrDefault("42", 0));
        }

        @Test
        @DisplayName("\"abc\" → defaultValue")
        void nonNumeric_returnsDefault() throws Exception {
            assertEquals(7, invokeParseIntOrDefault("abc", 7));
        }

        @Test
        @DisplayName("MAX_VALUE string → Integer.MAX_VALUE")
        void maxValueString_returnsMaxValue() throws Exception {
            assertEquals(Integer.MAX_VALUE,
                    invokeParseIntOrDefault(String.valueOf(Integer.MAX_VALUE), 0));
        }
    }

    // ── clamp (private) ─────────────────────────────────────

    @Nested
    @DisplayName("clamp")
    class Clamp {

        @Test
        @DisplayName("50 in [1, 100] → 50 (within range)")
        void withinRange_returnsValue() throws Exception {
            assertEquals(50, invokeClamp(50, 1, 100));
        }

        @Test
        @DisplayName("0 in [1, 100] → 1 (below min)")
        void belowMin_returnsMin() throws Exception {
            assertEquals(1, invokeClamp(0, 1, 100));
        }

        @Test
        @DisplayName("200 in [1, 100] → 100 (above max)")
        void aboveMax_returnsMax() throws Exception {
            assertEquals(100, invokeClamp(200, 1, 100));
        }
    }

    // ── Reflection helpers ──────────────────────────────────

    private static int invokeParseIntOrDefault(String value, int defaultValue) throws Exception {
        return (int) PARSE_INT_OR_DEFAULT.invoke(null, value, defaultValue);
    }

    private static int invokeClamp(int value, int min, int max) throws Exception {
        return (int) CLAMP.invoke(null, value, min, max);
    }
}
