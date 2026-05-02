package com.github.catatafishen.agentbridge.acp.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link KiroClient#extractPurposeFromArgs(String)} — index-based extraction
 * of the {@code __tool_use_purpose} field from raw JSON argument strings.
 */
@DisplayName("KiroClient.extractPurposeFromArgs")
class KiroToolPurposeTest {

    // ── Happy path ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Happy path")
    class HappyPath {

        @Test
        @DisplayName("extracts purpose from typical tool call args")
        void typicalArgs() {
            String args = "{\"path\": \"src/main.java\", \"__tool_use_purpose\": \"Reading file to understand structure\"}";
            assertEquals("Reading file to understand structure", KiroClient.extractPurposeFromArgs(args));
        }

        @Test
        @DisplayName("extracts purpose when it is the first field")
        void purposeFirst() {
            String args = "{\"__tool_use_purpose\": \"Check build output\", \"module\": \"core\"}";
            assertEquals("Check build output", KiroClient.extractPurposeFromArgs(args));
        }

        @Test
        @DisplayName("extracts purpose when it is the only field")
        void purposeOnly() {
            String args = "{\"__tool_use_purpose\": \"Standalone purpose\"}";
            assertEquals("Standalone purpose", KiroClient.extractPurposeFromArgs(args));
        }

        @Test
        @DisplayName("handles extra whitespace around colon and value")
        void extraWhitespace() {
            String args = "{\"__tool_use_purpose\" :  \"Whitespace test\" }";
            assertEquals("Whitespace test", KiroClient.extractPurposeFromArgs(args));
        }

        @Test
        @DisplayName("handles no whitespace around colon")
        void noWhitespace() {
            String args = "{\"__tool_use_purpose\":\"Compact format\"}";
            assertEquals("Compact format", KiroClient.extractPurposeFromArgs(args));
        }

        @Test
        @DisplayName("handles purpose with special characters")
        void specialChars() {
            String args = "{\"__tool_use_purpose\": \"Read file: src/main.rs (line 42)\"}";
            assertEquals("Read file: src/main.rs (line 42)", KiroClient.extractPurposeFromArgs(args));
        }

        @Test
        @DisplayName("extracts purpose when preceded by many fields")
        void manyFieldsBefore() {
            String args = "{\"path\": \"/a/b.java\", \"query\": \"test\", \"limit\": 10, \"__tool_use_purpose\": \"Search for tests\"}";
            assertEquals("Search for tests", KiroClient.extractPurposeFromArgs(args));
        }

        @Test
        @DisplayName("extracts purpose followed by more fields")
        void fieldsAfterPurpose() {
            String args = "{\"__tool_use_purpose\": \"Explore structure\", \"path\": \".\", \"depth\": 3}";
            assertEquals("Explore structure", KiroClient.extractPurposeFromArgs(args));
        }
    }

    // ── Null / absent / empty ───────────────────────────────────────────────

    @Nested
    @DisplayName("Null, absent, and empty")
    class NullAbsentEmpty {

        @Test
        @DisplayName("null args → null")
        void nullArgs() {
            assertNull(KiroClient.extractPurposeFromArgs(null));
        }

        @Test
        @DisplayName("empty string → null")
        void emptyString() {
            assertNull(KiroClient.extractPurposeFromArgs(""));
        }

        @Test
        @DisplayName("no __tool_use_purpose field → null")
        void noPurposeField() {
            String args = "{\"path\": \"src/main.java\"}";
            assertNull(KiroClient.extractPurposeFromArgs(args));
        }

        @Test
        @DisplayName("empty JSON object → null")
        void emptyObject() {
            assertNull(KiroClient.extractPurposeFromArgs("{}"));
        }
    }

    // ── Edge cases ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("purpose with empty value")
        void emptyPurpose() {
            String args = "{\"__tool_use_purpose\": \"\"}";
            assertEquals("", KiroClient.extractPurposeFromArgs(args));
        }

        @Test
        @DisplayName("purpose value containing a colon")
        void purposeWithColon() {
            String args = "{\"__tool_use_purpose\": \"Check: is the test passing?\"}";
            assertEquals("Check: is the test passing?", KiroClient.extractPurposeFromArgs(args));
        }

        @ParameterizedTest(name = "malformed or mismatched key: {0}")
        @ValueSource(strings = {
            "{\"x__tool_use_purpose\": \"should not match\"}",
            "{\"description\": \"the __tool_use_purpose field\"}",
            "{\"__tool_use_purpose\"",
            "{\"__tool_use_purpose\": }"
        })
        void malformedOrMismatchedKeyReturnsNull(String args) {
            assertNull(KiroClient.extractPurposeFromArgs(args));
        }

        @Test
        @DisplayName("purpose key in a nested object is still found (best-effort)")
        void nestedPurpose() {
            String args = "{\"inner\": {\"__tool_use_purpose\": \"Nested\"}}";
            assertEquals("Nested", KiroClient.extractPurposeFromArgs(args));
        }
    }
}
