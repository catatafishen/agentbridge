package com.github.copilot.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("McpStdioProxy")
class McpStdioProxyTest {

    // ── parsePort ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("parsePort")
    class ParsePort {

        @Test
        @DisplayName("returns port when --port flag is present")
        void returnsPortWhenFlagPresent() {
            assertEquals(8080, McpStdioProxy.parsePort(new String[]{"--port", "8080"}));
        }

        @Test
        @DisplayName("returns port when --port follows other flags")
        void returnsPortAfterOtherFlags() {
            assertEquals(3000, McpStdioProxy.parsePort(new String[]{"--verbose", "--port", "3000"}));
        }

        @Test
        @DisplayName("returns -1 for empty args")
        void returnsNegativeOneForEmptyArgs() {
            assertEquals(-1, McpStdioProxy.parsePort(new String[]{}));
        }

        @Test
        @DisplayName("returns -1 when --port is last arg with no value")
        void returnsNegativeOneWhenPortIsLastArg() {
            assertEquals(-1, McpStdioProxy.parsePort(new String[]{"--port"}));
        }

        @Test
        @DisplayName("returns -1 when port value is non-numeric")
        void returnsNegativeOneForNonNumericValue() {
            assertEquals(-1, McpStdioProxy.parsePort(new String[]{"--port", "abc"}));
        }

        @Test
        @DisplayName("returns -1 for single-dash flag -port")
        void returnsNegativeOneForSingleDashFlag() {
            assertEquals(-1, McpStdioProxy.parsePort(new String[]{"-port", "8080"}));
        }

        @Test
        @DisplayName("returns 0 when port value is zero")
        void returnsZeroWhenPortIsZero() {
            assertEquals(0, McpStdioProxy.parsePort(new String[]{"--port", "0"}));
        }
    }

    // ── extractJsonRpcId ────────────────────────────────────────────────

    @Nested
    @DisplayName("extractJsonRpcId")
    class ExtractJsonRpcId {

        @Test
        @DisplayName("extracts numeric id")
        void extractsNumericId() {
            String msg = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}";
            assertEquals("1", McpStdioProxy.extractJsonRpcId(msg));
        }

        @Test
        @DisplayName("extracts quoted string id with quotes")
        void extractsStringIdWithQuotes() {
            String msg = "{\"jsonrpc\":\"2.0\",\"id\":\"abc-123\",\"method\":\"test\"}";
            assertEquals("\"abc-123\"", McpStdioProxy.extractJsonRpcId(msg));
        }

        @Test
        @DisplayName("returns null literal when no id key present")
        void returnsNullWhenNoIdKey() {
            String msg = "{\"jsonrpc\":\"2.0\",\"method\":\"notify\"}";
            assertEquals("null", McpStdioProxy.extractJsonRpcId(msg));
        }

        @Test
        @DisplayName("handles whitespace around numeric value")
        void handlesWhitespaceAroundValue() {
            String msg = "{\"jsonrpc\":\"2.0\",\"id\": 42 ,\"method\":\"test\"}";
            assertEquals("42", McpStdioProxy.extractJsonRpcId(msg));
        }

        @Test
        @DisplayName("returns null literal when id value is explicit null")
        void returnsNullForExplicitNullId() {
            String msg = "{\"id\":null,\"method\":\"test\"}";
            assertEquals("null", McpStdioProxy.extractJsonRpcId(msg));
        }

        @Test
        @DisplayName("returns null literal for empty string input")
        void returnsNullForEmptyString() {
            assertEquals("null", McpStdioProxy.extractJsonRpcId(""));
        }

        @Test
        @DisplayName("extracts id when id is the last field before closing brace")
        void extractsIdAsLastField() {
            String msg = "{\"method\":\"test\",\"id\":99}";
            assertEquals("99", McpStdioProxy.extractJsonRpcId(msg));
        }
    }

    // ── buildErrorResponse ──────────────────────────────────────────────

    @Nested
    @DisplayName("buildErrorResponse")
    class BuildErrorResponse {

        @Test
        @DisplayName("builds error response with integer id")
        void buildsErrorWithIntegerId() {
            String original = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}";
            String result = McpStdioProxy.buildErrorResponse(original, "connection refused");

            assertTrue(result.contains("\"jsonrpc\":\"2.0\""), "should contain jsonrpc version");
            assertTrue(result.contains("\"id\":1"), "should contain the extracted id");
            assertTrue(result.contains("\"code\":-32603"), "should contain error code -32603");
            assertTrue(result.contains("\"message\":\"connection refused\""), "should contain error message");
        }

        @Test
        @DisplayName("preserves quoted string id in error response")
        void preservesStringId() {
            String original = "{\"jsonrpc\":\"2.0\",\"id\":\"req-42\",\"method\":\"test\"}";
            String result = McpStdioProxy.buildErrorResponse(original, "timeout");

            assertTrue(result.contains("\"id\":\"req-42\""), "should preserve quoted string id");
            assertTrue(result.contains("\"message\":\"timeout\""));
        }

        @Test
        @DisplayName("escapes double quotes in error message to single quotes")
        void escapesQuotesInErrorMessage() {
            String original = "{\"id\":5,\"method\":\"test\"}";
            String result = McpStdioProxy.buildErrorResponse(original, "bad \"value\" here");

            assertTrue(result.contains("bad 'value' here"), "double quotes should be replaced with single quotes");
            assertFalse(result.contains("bad \"value\""), "original double quotes should not remain");
        }

        @Test
        @DisplayName("uses null id when original has no id field")
        void usesNullIdWhenNoIdInOriginal() {
            String original = "{\"jsonrpc\":\"2.0\",\"method\":\"notify\"}";
            String result = McpStdioProxy.buildErrorResponse(original, "some error");

            assertTrue(result.contains("\"id\":null"), "should use null for missing id");
        }

        @Test
        @DisplayName("produces valid JSON-RPC error structure")
        void producesValidJsonRpcStructure() {
            String original = "{\"id\":7,\"method\":\"test\"}";
            String result = McpStdioProxy.buildErrorResponse(original, "fail");

            String expected = "{\"jsonrpc\":\"2.0\",\"id\":7,\"error\":{\"code\":-32603,\"message\":\"fail\"}}";
            assertEquals(expected, result);
        }
    }
}
