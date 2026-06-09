package com.github.catatafishen.agentbridge.session.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for pure static utility methods in {@link ConversationService}.
 */
class ConversationServiceStaticMethodsTest {

    private static final Method PARSE_ISO;

    static {
        try {
            PARSE_ISO = ConversationService.class
                .getDeclaredMethod("parseIsoToEpochMillis", String.class);
            PARSE_ISO.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("Method not found — signature changed?", e);
        }
    }

    // ── truncateSessionName ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("truncateSessionName")
    class TruncateSessionName {

        @Test
        @DisplayName("short text returned as-is")
        void shortTextUnchanged() {
            assertEquals("Hello world", ConversationService.truncateSessionName("Hello world"));
        }

        @Test
        @DisplayName("collapses multiple whitespace to single space")
        void collapsesWhitespace() {
            assertEquals("a b c", ConversationService.truncateSessionName("a   b\t\nc"));
        }

        @Test
        @DisplayName("trims leading/trailing whitespace")
        void trimsWhitespace() {
            assertEquals("trimmed", ConversationService.truncateSessionName("  trimmed  "));
        }

        @Test
        @DisplayName("exactly 60 chars returned as-is")
        void exactLimitUnchanged() {
            String text = "a".repeat(60);
            assertEquals(text, ConversationService.truncateSessionName(text));
        }

        @Test
        @DisplayName("61 chars gets truncated with ellipsis")
        void overLimitTruncated() {
            String text = "a".repeat(61);
            String result = ConversationService.truncateSessionName(text);
            assertEquals(60, result.length());
            assertTrue(result.endsWith("…"), "Should end with ellipsis");
            assertEquals("a".repeat(59) + "…", result);
        }

        @Test
        @DisplayName("very long text truncated to 60")
        void veryLongTruncated() {
            String text = "a".repeat(200);
            String result = ConversationService.truncateSessionName(text);
            assertEquals(60, result.length());
        }

        @Test
        @DisplayName("whitespace-only collapses to empty")
        void whitespaceOnlyBecomesEmpty() {
            assertEquals("", ConversationService.truncateSessionName("   \t\n  "));
        }

        @Test
        @DisplayName("multiline prompt collapses to single line")
        void multilineCollapsed() {
            String prompt = "Fix the bug\nin file\nMain.java";
            assertEquals("Fix the bug in file Main.java", ConversationService.truncateSessionName(prompt));
        }

        private void assertTrue(boolean condition, String message) {
            org.junit.jupiter.api.Assertions.assertTrue(condition, message);
        }
    }

    // ── parseIsoToEpochMillis ───────────────────────────────────────────────────

    @Nested
    @DisplayName("parseIsoToEpochMillis")
    class ParseIsoToEpochMillis {

        @Test
        @DisplayName("null returns 0")
        void nullReturnsZero() throws Exception {
            assertEquals(0L, invokeParseIso(null));
        }

        @Test
        @DisplayName("empty string returns 0")
        void emptyReturnsZero() throws Exception {
            assertEquals(0L, invokeParseIso(""));
        }

        @Test
        @DisplayName("invalid format returns 0")
        void invalidFormatReturnsZero() throws Exception {
            assertEquals(0L, invokeParseIso("not-a-date"));
        }

        @Test
        @DisplayName("valid ISO timestamp parsed correctly")
        void validIsoParsed() throws Exception {
            // 2024-01-01T00:00:00Z = 1704067200000ms
            long result = invokeParseIso("2024-01-01T00:00:00Z");
            assertEquals(1704067200000L, result);
        }

        @Test
        @DisplayName("ISO with fractional seconds parsed")
        void fractionalSecondsParsed() throws Exception {
            long result = invokeParseIso("2024-06-15T12:30:45.123Z");
            // Should be non-zero and close to expected
            org.junit.jupiter.api.Assertions.assertTrue(result > 0);
        }

        @Test
        @DisplayName("partial ISO format returns 0")
        void partialIsoReturnsZero() throws Exception {
            assertEquals(0L, invokeParseIso("2024-01-01"));
        }

        private long invokeParseIso(String input) throws Exception {
            return (long) PARSE_ISO.invoke(null, input);
        }
    }
}
