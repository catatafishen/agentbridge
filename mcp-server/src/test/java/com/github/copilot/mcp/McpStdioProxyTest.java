package com.github.copilot.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("McpStdioProxy")
class McpStdioProxyTest {

    // ── stateless (2026-07-28) headers ──────────────────────────────────

    @Nested
    @DisplayName("buildStatelessHeaders")
    class BuildStatelessHeaders {

        private static final String META =
            "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
                + "\"io.modelcontextprotocol/clientInfo\":{\"name\":\"X\",\"version\":\"1\"}}";

        @Test
        @DisplayName("legacy initialize gets no stateless headers")
        void legacyInitializeIsNull() {
            assertTrue(McpStdioProxy.buildStatelessHeaders(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}").isEmpty());
        }

        @Test
        @DisplayName("legacy tools/call with only a progressToken gets no stateless headers")
        void legacyToolsCallIsNull() {
            assertTrue(McpStdioProxy.buildStatelessHeaders(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"x\",\"_meta\":{\"progressToken\":\"t\"}}}").isEmpty());
        }

        @Test
        @DisplayName("message without params gets no stateless headers")
        void noParamsIsNull() {
            assertTrue(McpStdioProxy.buildStatelessHeaders(
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}").isEmpty());
        }

        @Test
        @DisplayName("the version only counts when it is inside params._meta")
        void versionElsewhereIsIgnored() {
            assertTrue(McpStdioProxy.buildStatelessHeaders(
                "{\"method\":\"tools/list\",\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
                    + "\"params\":{\"arguments\":{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\"}}}}").isEmpty());
        }

        @Test
        @DisplayName("tools/call yields version, method and name headers")
        void toolsCallHeaders() {
            var headers = McpStdioProxy.buildStatelessHeaders(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"read_file\",\"arguments\":{\"name\":\"decoy\"}," + META + "}}");
            assertEquals("2026-07-28", headers.get("MCP-Protocol-Version"));
            assertEquals("tools/call", headers.get("Mcp-Method"));
            assertEquals("read_file", headers.get("Mcp-Name"));
        }

        @Test
        @DisplayName("resources/read uses params.uri for Mcp-Name")
        void resourcesReadHeaders() {
            var headers = McpStdioProxy.buildStatelessHeaders(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/read\","
                    + "\"params\":{\"uri\":\"file:///a.txt\"," + META + "}}");
            assertEquals("file:///a.txt", headers.get("Mcp-Name"));
        }

        @Test
        @DisplayName("methods other than tools/call and resources/read get no Mcp-Name")
        void listHasNoName() {
            var headers = McpStdioProxy.buildStatelessHeaders(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{" + META + "}}");
            assertEquals("tools/list", headers.get("Mcp-Method"));
            assertFalse(headers.containsKey("Mcp-Name"));
        }

        @Test
        @DisplayName("non-ASCII names are Base64-encoded")
        void nonAsciiNameIsEncoded() {
            var headers = McpStdioProxy.buildStatelessHeaders(
                "{\"method\":\"tools/call\",\"params\":{\"name\":\"h\u00e9llo\"," + META + "}}");
            String expected = "=?base64?"
                + java.util.Base64.getEncoder().encodeToString("h\u00e9llo".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + "?=";
            assertEquals(expected, headers.get("Mcp-Name"));
        }

        @Test
        @DisplayName("encodeHeaderValue leaves plain values and encodes padded or sentinel-like ones")
        void encodeHeaderValue() {
            assertEquals("plain_name", McpStdioProxy.encodeHeaderValue("plain_name"));
            assertTrue(McpStdioProxy.encodeHeaderValue(" padded ").startsWith("=?base64?"));
            assertTrue(McpStdioProxy.encodeHeaderValue("=?base64?x?=").startsWith("=?base64?"));
            assertTrue(McpStdioProxy.encodeHeaderValue("a\nb").startsWith("=?base64?"));
        }

        @Test
        @DisplayName("JSON string escapes in names are decoded before being mirrored")
        void escapesAreDecoded() {
            var headers = McpStdioProxy.buildStatelessHeaders(
                "{\"method\":\"tools/call\",\"params\":{\"name\":\"a\\\"b\\\\c\\u0041\\/d\"," + META + "}}");
            // Decoded name is a"b\cA/d: printable ASCII, so it is sent as-is (not Base64).
            assertEquals("a\"b\\cA/d", headers.get("Mcp-Name"));
        }

        @Test
        @DisplayName("an escaped quote does not end a key or value early")
        void escapedQuoteInsideValue() {
            var headers = McpStdioProxy.buildStatelessHeaders(
                "{\"method\":\"tools/call\",\"params\":{\"note\":\"x\\\"name\\\":\\\"evil\",\"name\":\"real\"," + META + "}}");
            assertEquals("real", headers.get("Mcp-Name"));
        }

        @Test
        @DisplayName("a malformed unicode escape is kept literally instead of failing")
        void malformedUnicodeEscape() {
            var headers = McpStdioProxy.buildStatelessHeaders(
                "{\"method\":\"tools/call\",\"params\":{\"name\":\"a\\uZZZZb\"," + META + "}}");
            assertEquals("a\\uZZZZb", headers.get("Mcp-Name"));
        }
    }

    // ── buildMcpUrl ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("buildMcpUrl")
    class BuildMcpUrl {

        @Test
        @DisplayName("builds correct MCP URL for typical port")
        void buildsCorrectUrl() {
            assertEquals("http://127.0.0.1:8080/mcp", McpStdioProxy.buildMcpUrl(8080));
        }

        @Test
        @DisplayName("builds correct MCP URL for port 0")
        void buildsUrlForPortZero() {
            assertEquals("http://127.0.0.1:0/mcp", McpStdioProxy.buildMcpUrl(0));
        }

        @Test
        @DisplayName("builds correct MCP URL for high port number")
        void buildsUrlForHighPort() {
            assertEquals("http://127.0.0.1:65535/mcp", McpStdioProxy.buildMcpUrl(65535));
        }
    }

    // ── buildHealthUrl ──────────────────────────────────────────────────

    @Nested
    @DisplayName("buildHealthUrl")
    class BuildHealthUrl {

        @Test
        @DisplayName("builds correct health URL for typical port")
        void buildsCorrectUrl() {
            assertEquals("http://127.0.0.1:3000/health", McpStdioProxy.buildHealthUrl(3000));
        }

        @Test
        @DisplayName("builds correct health URL for port 0")
        void buildsUrlForPortZero() {
            assertEquals("http://127.0.0.1:0/health", McpStdioProxy.buildHealthUrl(0));
        }

        @Test
        @DisplayName("MCP and health URLs share same host and port")
        void mcpAndHealthUrlsShareHostPort() {
            String mcp = McpStdioProxy.buildMcpUrl(9090);
            String health = McpStdioProxy.buildHealthUrl(9090);
            // Both start with the same host:port prefix
            String prefix = "http://127.0.0.1:9090";
            assertTrue(mcp.startsWith(prefix), "MCP URL should start with " + prefix);
            assertTrue(health.startsWith(prefix), "health URL should start with " + prefix);
            // But have different paths
            assertNotEquals(mcp, health);
        }
    }

    // ── isBlankLine ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("isBlankLine")
    class IsBlankLine {

        @Test
        @DisplayName("returns true for null")
        void returnsTrueForNull() {
            assertTrue(McpStdioProxy.isBlankLine(null));
        }

        @Test
        @DisplayName("returns true for empty string")
        void returnsTrueForEmpty() {
            assertTrue(McpStdioProxy.isBlankLine(""));
        }

        @Test
        @DisplayName("returns false for non-empty string")
        void returnsFalseForNonEmpty() {
            assertFalse(McpStdioProxy.isBlankLine("{\"jsonrpc\":\"2.0\"}"));
        }

        @Test
        @DisplayName("returns false for whitespace-only string (caller trims before calling)")
        void returnsFalseForWhitespaceOnly() {
            // The main loop trims before calling isBlankLine, so whitespace-only
            // strings would already be trimmed to empty. But if called directly
            // with whitespace, it returns false since it only checks isEmpty().
            assertFalse(McpStdioProxy.isBlankLine("  "));
        }

        @Test
        @DisplayName("returns false for single character")
        void returnsFalseForSingleChar() {
            assertFalse(McpStdioProxy.isBlankLine("x"));
        }
    }

    // ── shouldForwardResponse ───────────────────────────────────────────

    @Nested
    @DisplayName("shouldForwardResponse")
    class ShouldForwardResponse {

        @Test
        @DisplayName("returns false for null (notification / HTTP 202)")
        void returnsFalseForNull() {
            assertFalse(McpStdioProxy.shouldForwardResponse(null));
        }

        @Test
        @DisplayName("returns false for empty string")
        void returnsFalseForEmpty() {
            assertFalse(McpStdioProxy.shouldForwardResponse(""));
        }

        @Test
        @DisplayName("returns true for valid JSON-RPC response")
        void returnsTrueForValidResponse() {
            assertTrue(McpStdioProxy.shouldForwardResponse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"));
        }

        @Test
        @DisplayName("returns true for error response")
        void returnsTrueForErrorResponse() {
            assertTrue(McpStdioProxy.shouldForwardResponse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32603,\"message\":\"fail\"}}"));
        }

        @Test
        @DisplayName("returns true for whitespace-only string")
        void returnsTrueForWhitespace() {
            // Whitespace is not empty, so it would be forwarded
            assertTrue(McpStdioProxy.shouldForwardResponse("  "));
        }
    }

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

    // ── isInitializeMessage ─────────────────────────────────────────────

    @Nested
    @DisplayName("isInitializeMessage")
    class IsInitializeMessage {

        @Test
        @DisplayName("returns true for a well-formed initialize request")
        void returnsTrueForInitialize() {
            String msg = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";
            assertTrue(McpStdioProxy.isInitializeMessage(msg));
        }

        @Test
        @DisplayName("returns true when method is the last field before closing brace")
        void returnsTrueWhenMethodIsLastField() {
            String msg = "{\"id\":1,\"method\":\"initialize\"}";
            assertTrue(McpStdioProxy.isInitializeMessage(msg));
        }

        @Test
        @DisplayName("returns true with whitespace around the method value")
        void returnsTrueWithWhitespace() {
            String msg = "{\"id\":1,\"method\": \"initialize\" ,\"params\":{}}";
            assertTrue(McpStdioProxy.isInitializeMessage(msg));
        }

        @Test
        @DisplayName("returns false for other methods, e.g. tools/list")
        void returnsFalseForOtherMethods() {
            String msg = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}";
            assertFalse(McpStdioProxy.isInitializeMessage(msg));
        }

        @Test
        @DisplayName("returns false when no method key present")
        void returnsFalseWhenNoMethodKey() {
            String msg = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}";
            assertFalse(McpStdioProxy.isInitializeMessage(msg));
        }

        @Test
        @DisplayName("returns false for empty string input")
        void returnsFalseForEmptyString() {
            assertFalse(McpStdioProxy.isInitializeMessage(""));
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
