package com.github.catatafishen.agentbridge.bridge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ProfileBasedAgentConfig#formatJsonSafely} and related protected methods.
 */
class ProfileBasedAgentConfigExtendedTest {

    /**
     * Minimal subclass to expose the protected {@code formatJsonSafely} method for testing.
     */
    private static class TestableConfig extends ProfileBasedAgentConfig {
        TestableConfig() {
            super(stubProfile(), null);
        }

        String testFormatJson(String json) {
            return formatJsonSafely(json);
        }

        private static com.github.catatafishen.agentbridge.services.AgentProfile stubProfile() {
            var p = new com.github.catatafishen.agentbridge.services.AgentProfile();
            p.setId("test");
            p.setDisplayName("Test Agent");
            return p;
        }
    }

    private final TestableConfig config = new TestableConfig();

    @Nested
    @DisplayName("formatJsonSafely")
    class FormatJsonSafely {

        @Test
        @DisplayName("valid JSON object is pretty-printed")
        void validJsonObject() {
            String result = config.testFormatJson("{\"key\":\"value\"}");

            // Pretty-printed output should contain newlines and indentation
            assertTrue(result.contains("\n"), "should contain newlines");
            assertTrue(result.contains("key"));
            assertTrue(result.contains("value"));
        }

        @Test
        @DisplayName("empty object {} is formatted")
        void emptyObject() {
            String result = config.testFormatJson("{}");

            assertNotNull(result);
            assertTrue(result.contains("{"));
            assertTrue(result.contains("}"));
        }

        @Test
        @DisplayName("nested JSON is formatted with indentation")
        void nestedJson() {
            String result = config.testFormatJson("{\"a\":{\"b\":{\"c\":1}}}");

            assertTrue(result.contains("\n"));
            assertTrue(result.contains("\"a\""));
            assertTrue(result.contains("\"b\""));
            assertTrue(result.contains("\"c\""));
        }

        @Test
        @DisplayName("invalid JSON returns raw input unchanged")
        void invalidJson() {
            String raw = "this is not json {{{";
            String result = config.testFormatJson(raw);

            assertEquals(raw, result);
        }

        @Test
        @DisplayName("JSON with null value can be formatted")
        void jsonWithNull() {
            // formatJsonSafely should handle null values in JSON without throwing
            String input = "{\"key\":null}";
            assertDoesNotThrow(() -> config.testFormatJson(input));
        }

        @Test
        @DisplayName("JSON array is formatted")
        void jsonArray() {
            String result = config.testFormatJson("[1,2,3]");

            assertTrue(result.contains("1"));
            assertTrue(result.contains("2"));
            assertTrue(result.contains("3"));
        }

        @Test
        @DisplayName("JSON with boolean and number types")
        void jsonWithMixedTypes() {
            String result = config.testFormatJson("{\"flag\":true,\"count\":42,\"name\":\"test\"}");

            assertTrue(result.contains("true"));
            assertTrue(result.contains("42"));
            assertTrue(result.contains("test"));
        }

        @Test
        @DisplayName("deeply nested JSON is formatted correctly")
        void deeplyNested() {
            String input = "{\"l1\":{\"l2\":{\"l3\":{\"l4\":\"deep\"}}}}";
            String result = config.testFormatJson(input);

            assertTrue(result.contains("\"deep\""));
            // Pretty printing should produce multiple lines
            long lineCount = result.lines().count();
            assertTrue(lineCount > 1, "should produce multiple lines, got: " + lineCount);
        }

        @Test
        @DisplayName("empty string is handled gracefully (invalid JSON)")
        void emptyString() {
            // Empty string is not valid JSON; formatJsonSafely should handle it
            // without throwing an unhandled exception
            assertDoesNotThrow(() -> config.testFormatJson(""));
        }
    }
}
