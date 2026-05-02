package com.github.catatafishen.agentbridge.psi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ToolError} and {@link McpErrorCode}.
 */
class ToolErrorTest {

    @Nested
    @DisplayName("ToolError.of(code, message)")
    class OfTwoArgs {

        @Test
        @DisplayName("produces Error [CODE]: message format")
        void basicFormat() {
            String result = ToolError.of(McpErrorCode.FILE_NOT_FOUND, "/foo/bar.txt");
            assertEquals("Error [FILE_NOT_FOUND]: /foo/bar.txt", result);
        }

        @ParameterizedTest
        @EnumSource(McpErrorCode.class)
        @DisplayName("all error codes produce valid format")
        void allCodesProduceValidFormat(McpErrorCode code) {
            String result = ToolError.of(code, "test message");
            assertTrue(result.startsWith("Error ["), "must start with 'Error [': " + result);
            assertTrue(result.contains(code.name()), "must contain code name: " + result);
            assertTrue(result.contains("test message"), "must contain message: " + result);
        }

        @ParameterizedTest
        @EnumSource(McpErrorCode.class)
        @DisplayName("all codes maintain backward compatibility with isError detection")
        void backwardCompatible(McpErrorCode code) {
            String result = ToolError.of(code, "anything");
            assertTrue(result.startsWith("Error"), "must start with 'Error' for isError detection");
        }
    }

    @Nested
    @DisplayName("ToolError.of(code, message, hint)")
    class OfThreeArgs {

        @Test
        @DisplayName("includes hint on new line")
        void withHint() {
            String result = ToolError.of(McpErrorCode.MISSING_PARAM,
                "'path' parameter is required",
                "Add 'path' to the arguments.");
            assertEquals(
                "Error [MISSING_PARAM]: 'path' parameter is required\n"
                    + "Hint: Add 'path' to the arguments.",
                result);
        }

        @Test
        @DisplayName("hint is on a separate line from message")
        void hintOnSeparateLine() {
            String result = ToolError.of(McpErrorCode.INDEX_NOT_READY, "still indexing",
                "Call get_indexing_status first.");
            String[] lines = result.split("\n");
            assertEquals(2, lines.length, "should have exactly 2 lines");
            assertTrue(lines[0].startsWith("Error [INDEX_NOT_READY]:"));
            assertTrue(lines[1].startsWith("Hint: "));
        }
    }

    @Nested
    @DisplayName("ToolError.extractCode()")
    class ExtractCode {

        @Test
        @DisplayName("extracts code from structured error")
        void extractFromStructured() {
            String error = ToolError.of(McpErrorCode.FILE_NOT_FOUND, "missing file");
            McpErrorCode code = ToolError.extractCode(error);
            assertEquals(McpErrorCode.FILE_NOT_FOUND, code);
        }

        @ParameterizedTest
        @EnumSource(McpErrorCode.class)
        @DisplayName("round-trips all error codes")
        void roundTripsAllCodes(McpErrorCode expected) {
            String error = ToolError.of(expected, "test");
            McpErrorCode actual = ToolError.extractCode(error);
            assertEquals(expected, actual, "round-trip failed for " + expected);
        }

        @Test
        @DisplayName("returns null for legacy error format")
        void legacyFormatReturnsNull() {
            assertNull(ToolError.extractCode("Error: something went wrong"));
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"", "Success", "Error", "Error [", "Error []:", "no error here"})
        @DisplayName("returns null for invalid inputs")
        void invalidInputsReturnNull(String input) {
            assertNull(ToolError.extractCode(input));
        }

        @Test
        @DisplayName("returns null for unknown code name")
        void unknownCodeReturnsNull() {
            assertNull(ToolError.extractCode("Error [DOES_NOT_EXIST]: something"));
        }
    }

    @Nested
    @DisplayName("ToolError.isError()")
    class IsError {

        @Test
        @DisplayName("detects structured error format")
        void structuredFormat() {
            assertTrue(ToolError.isError("Error [FILE_NOT_FOUND]: /foo"));
        }

        @Test
        @DisplayName("detects legacy error format")
        void legacyFormat() {
            assertTrue(ToolError.isError("Error: something went wrong"));
        }

        @Test
        @DisplayName("detects error with exit code format")
        void exitCodeFormat() {
            assertTrue(ToolError.isError("Error (exit 128): fatal: not a git repo"));
        }

        @Test
        @DisplayName("returns false for null")
        void nullInput() {
            assertFalse(ToolError.isError(null));
        }

        @Test
        @DisplayName("returns false for non-error text")
        void nonError() {
            assertFalse(ToolError.isError("File written successfully"));
        }
    }

    @Nested
    @DisplayName("McpErrorCode")
    class ErrorCodeEnum {

        @ParameterizedTest
        @EnumSource(McpErrorCode.class)
        @DisplayName("all codes have non-empty descriptions")
        void allHaveDescriptions(McpErrorCode code) {
            assertNotNull(code.description());
            assertFalse(code.description().isEmpty(),
                code.name() + " has empty description");
        }

        @ParameterizedTest
        @EnumSource(McpErrorCode.class)
        @DisplayName("all code names are UPPER_SNAKE_CASE")
        void allNamesAreUpperSnakeCase(McpErrorCode code) {
            assertTrue(code.name().matches("[A-Z][A-Z_]+"),
                code.name() + " is not UPPER_SNAKE_CASE");
        }
    }
}
