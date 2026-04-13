package com.github.catatafishen.agentbridge.services;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for static helper methods in {@link McpHttpServer}.
 */
class McpHttpServerStaticMethodsTest {

    // ── truncateForLog (private, via reflection) ───────────

    private static Method truncateForLog;

    @BeforeAll
    static void setup() throws Exception {
        truncateForLog = McpHttpServer.class.getDeclaredMethod("truncateForLog", String.class);
        truncateForLog.setAccessible(true);
    }

    private String callTruncateForLog(String s) throws Exception {
        return (String) truncateForLog.invoke(null, s);
    }

    @Test
    void nullReturnsNull() throws Exception {
        assertNull(callTruncateForLog(null));
    }

    @Test
    void emptyStringUnchanged() throws Exception {
        assertEquals("", callTruncateForLog(""));
    }

    @Test
    void shortStringUnchanged() throws Exception {
        assertEquals("hello", callTruncateForLog("hello"));
    }

    @Test
    void exactlyAtLimitUnchanged() throws Exception {
        String atLimit = "x".repeat(2000);
        assertEquals(atLimit, callTruncateForLog(atLimit));
    }

    @Test
    void overLimitTruncatesWithSuffix() throws Exception {
        String overLimit = "a".repeat(2500);
        String result = callTruncateForLog(overLimit);
        assertTrue(result.startsWith("a".repeat(2000)));
        assertTrue(result.contains("[truncated 500 chars]"));
    }

    @Test
    void truncationCountIsAccurate() throws Exception {
        String input = "b".repeat(3000);
        String result = callTruncateForLog(input);
        assertTrue(result.contains("[truncated 1000 chars]"));
    }

    @Test
    void onePastLimitTruncates() throws Exception {
        String input = "c".repeat(2001);
        String result = callTruncateForLog(input);
        assertTrue(result.contains("[truncated 1 chars]"));
        assertTrue(result.startsWith("c".repeat(2000)));
    }

    // ── buildJsonRpcErrorResponse ──────────────────────────

    @Nested
    class BuildJsonRpcErrorResponseTest {

        @Test
        void basicStructure() {
            String json = McpHttpServer.buildJsonRpcErrorResponse(-32600, "Invalid Request");
            JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();

            assertEquals("2.0", parsed.get("jsonrpc").getAsString());
            assertTrue(parsed.has("error"));
            JsonObject error = parsed.getAsJsonObject("error");
            assertEquals(-32600, error.get("code").getAsInt());
            assertEquals("Invalid Request", error.get("message").getAsString());
        }

        @Test
        void internalErrorCode() {
            String json = McpHttpServer.buildJsonRpcErrorResponse(-32603, "Internal error: NPE");
            JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();

            assertEquals("2.0", parsed.get("jsonrpc").getAsString());
            JsonObject error = parsed.getAsJsonObject("error");
            assertEquals(-32603, error.get("code").getAsInt());
            assertEquals("Internal error: NPE", error.get("message").getAsString());
        }

        @Test
        void specialCharactersInMessageAreEscaped() {
            String msg = "Error with \"quotes\" and \\ backslash and newline\n";
            String json = McpHttpServer.buildJsonRpcErrorResponse(-32000, msg);
            // Gson handles JSON escaping; the result should be valid JSON
            JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
            assertEquals(msg, parsed.getAsJsonObject("error").get("message").getAsString());
        }

        @Test
        void emptyMessage() {
            String json = McpHttpServer.buildJsonRpcErrorResponse(-32600, "");
            JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
            assertEquals("", parsed.getAsJsonObject("error").get("message").getAsString());
        }

        @Test
        void noIdFieldPresent() {
            String json = McpHttpServer.buildJsonRpcErrorResponse(-32600, "test");
            JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
            assertFalse(parsed.has("id"), "Error response should not include 'id' field");
        }

        @Test
        void parseNotFoundCode() {
            String json = McpHttpServer.buildJsonRpcErrorResponse(-32601, "Method not found");
            JsonObject error = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("error");
            assertEquals(-32601, error.get("code").getAsInt());
        }
    }

    // ── buildHealthResponse ────────────────────────────────

    @Nested
    class BuildHealthResponseTest {

        @Test
        void runningWithSseTransport() {
            String json = McpHttpServer.buildHealthResponse(true, "SSE", "my-project");
            JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
            assertEquals("ok", parsed.get("status").getAsString());
            assertEquals("SSE", parsed.get("transport").getAsString());
            assertEquals("my-project", parsed.get("project").getAsString());
        }

        @Test
        void stoppedWithNoneTransport() {
            String json = McpHttpServer.buildHealthResponse(false, "none", "test-project");
            JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
            assertEquals("stopped", parsed.get("status").getAsString());
            assertEquals("none", parsed.get("transport").getAsString());
            assertEquals("test-project", parsed.get("project").getAsString());
        }

        @Test
        void runningWithStreamableHttpTransport() {
            String json = McpHttpServer.buildHealthResponse(true, "STREAMABLE_HTTP", "demo");
            JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
            assertEquals("ok", parsed.get("status").getAsString());
            assertEquals("STREAMABLE_HTTP", parsed.get("transport").getAsString());
        }

        @Test
        void projectNameWithQuotesIsEscaped() {
            String json = McpHttpServer.buildHealthResponse(true, "SSE", "my \"project\"");
            // Quotes in project name should be replaced with single quotes for safe embedding
            assertTrue(json.contains("my 'project'"));
            assertFalse(json.contains("my \\\"project\\\""));
        }

        @Test
        void emptyProjectName() {
            String json = McpHttpServer.buildHealthResponse(true, "SSE", "");
            JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
            assertEquals("", parsed.get("project").getAsString());
        }

        @Test
        void hasThreeFields() {
            String json = McpHttpServer.buildHealthResponse(true, "SSE", "proj");
            JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
            assertEquals(3, parsed.size(), "Health response should have exactly 3 fields");
        }
    }
}
