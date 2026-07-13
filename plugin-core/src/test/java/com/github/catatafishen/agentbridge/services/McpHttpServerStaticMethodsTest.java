package com.github.catatafishen.agentbridge.services;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.project.Project;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

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

    // ── Streamable HTTP session ownership ─────────────────

    @Nested
    class TransportSessionResolutionTest {

        @Test
        void initializePublishesSessionOnlyWithInitializeResult() throws Exception {
            McpHttpServer server = new McpHttpServer(mock(Project.class));
            Headers requestHeaders = new Headers();
            Headers responseHeaders = new Headers();
            HttpExchange exchange = exchange(requestHeaders, responseHeaders,
                new ByteArrayOutputStream());

            McpHttpServer.HttpOwnerResolution resolution = server.resolveHttpOwner(exchange,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}");
            assertNotNull(resolution);
            String sessionId = resolution.newSessionId();

            assertNotNull(sessionId);
            assertEquals("http:" + sessionId, resolution.ownerKey());
            assertNull(responseHeaders.getFirst(McpHttpServer.MCP_SESSION_ID_HEADER));

            assertTrue(server.completeInitialization(exchange, resolution,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"));
            assertEquals(sessionId,
                responseHeaders.getFirst(McpHttpServer.MCP_SESSION_ID_HEADER));

            requestHeaders.set(McpHttpServer.MCP_SESSION_ID_HEADER, sessionId);
            McpHttpServer.HttpOwnerResolution established = server.resolveHttpOwner(exchange,
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
            assertNotNull(established);
            assertEquals(resolution.ownerKey(), established.ownerKey());
            assertNull(established.newSessionId());
        }

        @Test
        void failedInitializeDoesNotPublishOrRetainSession() throws Exception {
            McpHttpServer server = new McpHttpServer(mock(Project.class));
            Headers requestHeaders = new Headers();
            Headers responseHeaders = new Headers();
            HttpExchange exchange = exchange(requestHeaders, responseHeaders,
                new ByteArrayOutputStream());

            McpHttpServer.HttpOwnerResolution resolution = server.resolveHttpOwner(exchange,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}");
            assertNotNull(resolution);
            assertFalse(server.completeInitialization(exchange, resolution,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32602}}"));
            assertNull(responseHeaders.getFirst(McpHttpServer.MCP_SESSION_ID_HEADER));

            requestHeaders.set(McpHttpServer.MCP_SESSION_ID_HEADER, resolution.newSessionId());
            assertNull(server.resolveHttpOwner(exchange,
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"));
        }

        @Test
        void establishedRequestWithoutSessionIsRejected() throws Exception {
            McpHttpServer server = new McpHttpServer(mock(Project.class));
            ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
            HttpExchange exchange = exchange(new Headers(), new Headers(), responseBody);

            assertNull(server.resolveHttpOwner(exchange,
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"));

            verify(exchange).sendResponseHeaders(eq(400), anyLong());
            JsonObject response = JsonParser.parseString(
                responseBody.toString(java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            assertTrue(response.getAsJsonObject("error").get("message").getAsString()
                .contains(McpHttpServer.MCP_SESSION_ID_HEADER));
        }

        @Test
        void unknownSessionIsRejected() throws Exception {
            McpHttpServer server = new McpHttpServer(mock(Project.class));
            Headers requestHeaders = new Headers();
            requestHeaders.set(McpHttpServer.MCP_SESSION_ID_HEADER, "unknown");
            HttpExchange exchange = exchange(requestHeaders, new Headers(),
                new ByteArrayOutputStream());

            assertNull(server.resolveHttpOwner(exchange,
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"));

            verify(exchange).sendResponseHeaders(eq(404), anyLong());
        }

        private HttpExchange exchange(
            Headers requestHeaders,
            Headers responseHeaders,
            ByteArrayOutputStream responseBody
        ) {
            HttpExchange exchange = mock(HttpExchange.class);
            when(exchange.getRequestHeaders()).thenReturn(requestHeaders);
            when(exchange.getResponseHeaders()).thenReturn(responseHeaders);
            when(exchange.getResponseBody()).thenReturn(responseBody);
            return exchange;
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
            assertEquals("agentbridge", parsed.get("server").getAsString());
            assertNotNull(parsed.get("version").getAsString());
        }

        @Test
        void stoppedWithNoneTransport() {
            String json = McpHttpServer.buildHealthResponse(false, "none", "test-project");
            JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
            assertEquals("stopped", parsed.get("status").getAsString());
            assertEquals("none", parsed.get("transport").getAsString());
            assertEquals("test-project", parsed.get("project").getAsString());
            assertEquals("agentbridge", parsed.get("server").getAsString());
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
            // Gson properly escapes double quotes; the result should be valid JSON
            JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
            assertEquals("my \"project\"", parsed.get("project").getAsString());
        }

        @Test
        void emptyProjectName() {
            String json = McpHttpServer.buildHealthResponse(true, "SSE", "");
            JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
            assertEquals("", parsed.get("project").getAsString());
        }

        @Test
        void hasFiveFields() {
            String json = McpHttpServer.buildHealthResponse(true, "SSE", "proj");
            JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
            assertEquals(5, parsed.size(), "Health response should have exactly 5 fields: status, transport, project, server, version");
        }
    }
}
