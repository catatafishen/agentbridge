package com.github.catatafishen.agentbridge.bridge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for pure utility methods in {@link MessageFormatter}.
 */
@DisplayName("MessageFormatter")
class MessageFormatterTest {

    @Nested
    @DisplayName("escapeHtml")
    class EscapeHtml {

        @Test
        void escapesAmpersand() {
            assertEquals("a&amp;b", MessageFormatter.INSTANCE.escapeHtml("a&b"));
        }

        @Test
        void escapesLessThan() {
            assertEquals("&lt;div&gt;", MessageFormatter.INSTANCE.escapeHtml("<div>"));
        }

        @Test
        void escapesQuotes() {
            assertEquals("&quot;hello&quot;", MessageFormatter.INSTANCE.escapeHtml("\"hello\""));
        }

        @Test
        void escapesSingleQuote() {
            assertEquals("it&#39;s", MessageFormatter.INSTANCE.escapeHtml("it's"));
        }

        @Test
        void escapesBacktick() {
            assertEquals("&#96;code&#96;", MessageFormatter.INSTANCE.escapeHtml("`code`"));
        }

        @Test
        void emptyStringUnchanged() {
            assertEquals("", MessageFormatter.INSTANCE.escapeHtml(""));
        }

        @Test
        void plainTextUnchanged() {
            assertEquals("hello world", MessageFormatter.INSTANCE.escapeHtml("hello world"));
        }

        @Test
        void allSpecialChars() {
            String result = MessageFormatter.INSTANCE.escapeHtml("<script>alert('xss' & \"bad\" `eval`)</script>");
            assertTrue(result.contains("&lt;script&gt;"));
            assertTrue(result.contains("&#39;xss&#39;"));
            assertTrue(result.contains("&amp;"));
            assertTrue(result.contains("&quot;bad&quot;"));
            assertTrue(result.contains("&#96;eval&#96;"));
        }
    }

    @Nested
    @DisplayName("escapeJs")
    class EscapeJs {

        @Test
        void escapesBackslash() {
            assertEquals("a\\\\b", MessageFormatter.INSTANCE.escapeJs("a\\b"));
        }

        @Test
        void escapesSingleQuote() {
            assertEquals("it\\'s", MessageFormatter.INSTANCE.escapeJs("it's"));
        }

        @Test
        void escapesBacktick() {
            assertEquals("\\`template\\`", MessageFormatter.INSTANCE.escapeJs("`template`"));
        }

        @Test
        void escapesNewline() {
            assertEquals("line1\\nline2", MessageFormatter.INSTANCE.escapeJs("line1\nline2"));
        }

        @Test
        void escapesCarriageReturn() {
            assertEquals("a\\rb", MessageFormatter.INSTANCE.escapeJs("a\rb"));
        }

        @Test
        void emptyStringUnchanged() {
            assertEquals("", MessageFormatter.INSTANCE.escapeJs(""));
        }

        @Test
        void plainTextUnchanged() {
            assertEquals("hello world", MessageFormatter.INSTANCE.escapeJs("hello world"));
        }
    }

    @Nested
    @DisplayName("encodeBase64")
    class EncodeBase64 {

        @Test
        void encodesSimpleString() {
            String result = MessageFormatter.INSTANCE.encodeBase64("hello");
            assertEquals("aGVsbG8=", result);
        }

        @Test
        void encodesEmptyString() {
            assertEquals("", MessageFormatter.INSTANCE.encodeBase64(""));
        }

        @Test
        void encodesUnicode() {
            String result = MessageFormatter.INSTANCE.encodeBase64("café ☕");
            String decoded = new String(Base64.getDecoder().decode(result), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals("café ☕", decoded);
        }

        @Test
        void encodesMultiline() {
            String result = MessageFormatter.INSTANCE.encodeBase64("line1\nline2");
            String decoded = new String(Base64.getDecoder().decode(result), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals("line1\nline2", decoded);
        }
    }

    @Nested
    @DisplayName("formatToolSubtitle")
    class FormatToolSubtitle {

        @Test
        void nullArgumentsReturnsNull() {
            assertNull(MessageFormatter.INSTANCE.formatToolSubtitle("read_file", null));
        }

        @Test
        void blankArgumentsReturnsNull() {
            assertNull(MessageFormatter.INSTANCE.formatToolSubtitle("read_file", "   "));
        }

        @Test
        void unknownToolReturnsNull() {
            assertNull(MessageFormatter.INSTANCE.formatToolSubtitle("unknown_tool", "{\"foo\": \"bar\"}"));
        }

        @Test
        void readFileExtractsPath() {
            String result = MessageFormatter.INSTANCE.formatToolSubtitle("read_file", "{\"path\": \"src/Main.java\"}");
            assertEquals("src/Main.java", result);
        }

        @Test
        void searchTextExtractsQuery() {
            String result = MessageFormatter.INSTANCE.formatToolSubtitle("search_text", "{\"query\": \"TODO\"}");
            assertEquals("TODO", result);
        }

        @Test
        void longValueTruncatedWithEllipsis() {
            String longPath = "a".repeat(50);
            var result = MessageFormatter.INSTANCE.formatToolSubtitle("read_file", "{\"path\": \"" + longPath + "\"}");
            assert result != null;
            assertTrue(result.startsWith("…"));
            assertEquals(38, result.length());
        }

        @Test
        void invalidJsonReturnsNull() {
            assertNull(MessageFormatter.INSTANCE.formatToolSubtitle("read_file", "not json"));
        }

        @Test
        void missingKeyReturnsNull() {
            assertNull(MessageFormatter.INSTANCE.formatToolSubtitle("read_file", "{\"other\": \"value\"}"));
        }
    }
}
