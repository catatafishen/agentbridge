package com.github.catatafishen.agentbridge.psi.tools.editor;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class QueryTurnsToolStaticMethodsTest {

    @Nested
    class ShortModelName {
        @Test
        void sonnetVariant() {
            assertEquals("claude-sonnet", QueryTurnsTool.shortModelName("claude-sonnet-4-6-20250514"));
        }

        @Test
        void haikuVariant() {
            assertEquals("claude-haiku", QueryTurnsTool.shortModelName("claude-3-haiku-20240307"));
        }

        @Test
        void opusVariant() {
            assertEquals("claude-opus", QueryTurnsTool.shortModelName("claude-3-opus-20240229"));
        }

        @Test
        void gpt4Variant() {
            assertEquals("gpt-4", QueryTurnsTool.shortModelName("gpt-4-turbo-2024-04-09"));
        }

        @Test
        void gptGeneric() {
            assertEquals("gpt", QueryTurnsTool.shortModelName("gpt-3.5-turbo"));
        }

        @Test
        void unknownShortModel() {
            assertEquals("gemini-pro", QueryTurnsTool.shortModelName("gemini-pro"));
        }

        @Test
        void unknownLongModelTruncated() {
            var longName = "a".repeat(50);
            var result = QueryTurnsTool.shortModelName(longName);
            assertEquals(30, result.length());
        }

        @Test
        void caseInsensitive() {
            assertEquals("claude-sonnet", QueryTurnsTool.shortModelName("Claude-SONNET-4"));
        }
    }

    @Nested
    class FormatInstant {
        @Test
        void epochReturnsUnknownTime() {
            assertEquals("(unknown time)", QueryTurnsTool.formatInstant(Instant.EPOCH));
        }

        @Test
        void nonEpochReturnsFormattedString() {
            var result = QueryTurnsTool.formatInstant(Instant.parse("2025-06-15T12:30:00Z"));
            assertNotNull(result);
            assertFalse(result.isEmpty());
            // Should contain the year
            assertTrue(result.contains("2025"));
        }
    }

    @Nested
    class StringOrNull {
        @Test
        void returnsNullForMissingKey() {
            var args = new JsonObject();
            assertNull(QueryTurnsTool.stringOrNull(args, "missing"));
        }

        @Test
        void returnsValueForPresentKey() {
            var args = new JsonObject();
            args.addProperty("key", "value");
            assertEquals("value", QueryTurnsTool.stringOrNull(args, "key"));
        }
    }

    @Nested
    class IntOrNull {
        @Test
        void returnsNullForMissingKey() {
            var args = new JsonObject();
            assertNull(QueryTurnsTool.intOrNull(args, "missing"));
        }

        @Test
        void returnsValueForPresentKey() {
            var args = new JsonObject();
            args.addProperty("count", 42);
            assertEquals(42, QueryTurnsTool.intOrNull(args, "count"));
        }
    }

    @Nested
    class IntOrDefault {
        @Test
        void returnsDefaultForMissingKey() {
            var args = new JsonObject();
            assertEquals(99, QueryTurnsTool.intOrDefault(args, "missing", 99));
        }

        @Test
        void returnsValueForPresentKey() {
            var args = new JsonObject();
            args.addProperty("limit", 10);
            assertEquals(10, QueryTurnsTool.intOrDefault(args, "limit", 99));
        }
    }

    @Nested
    class BoolOrDefault {
        @Test
        void returnsDefaultForMissingKey() {
            var args = new JsonObject();
            assertTrue(QueryTurnsTool.boolOrDefault(args, "missing", true));
            assertFalse(QueryTurnsTool.boolOrDefault(args, "missing", false));
        }

        @Test
        void returnsValueForPresentKey() {
            var args = new JsonObject();
            args.addProperty("flag", true);
            assertTrue(QueryTurnsTool.boolOrDefault(args, "flag", false));
        }

        @Test
        void returnsFalseWhenSetToFalse() {
            var args = new JsonObject();
            args.addProperty("flag", false);
            assertFalse(QueryTurnsTool.boolOrDefault(args, "flag", true));
        }
    }
}
