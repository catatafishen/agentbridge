package com.github.catatafishen.agentbridge.services;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for pure static methods in {@link McpSseTransport}.
 */
class McpSseTransportTest {

    // ── parseSessionId (private, via reflection) ───────────

    @Nested
    class ParseSessionIdTest {

        @Test
        void returnsValueForMatchingKey() throws Exception {
            assertEquals("abc123", invokeParseSessionId("sessionId=abc123"));
        }

        @Test
        void findsAmongMultipleParams() throws Exception {
            assertEquals("xyz", invokeParseSessionId("foo=bar&sessionId=xyz&baz=qux"));
        }

        @Test
        void returnsNullWhenKeyMissing() throws Exception {
            assertNull(invokeParseSessionId("foo=bar&other=value"));
        }

        @Test
        void returnsNullForNullQuery() throws Exception {
            assertNull(invokeParseSessionId(null));
        }

        @Test
        void returnsEmptyStringForEmptyValue() throws Exception {
            assertEquals("", invokeParseSessionId("sessionId="));
        }

        @Test
        void returnsNullForEmptyString() throws Exception {
            assertNull(invokeParseSessionId(""));
        }

        @Test
        void handlesNoEqualsSign() throws Exception {
            assertNull(invokeParseSessionId("sessionId"));
        }

        @Test
        void handlesValueWithEqualsSign() throws Exception {
            assertEquals("a=b", invokeParseSessionId("sessionId=a=b"));
        }

        private static String invokeParseSessionId(String query) throws Exception {
            Method m = McpSseTransport.class.getDeclaredMethod("parseSessionId", String.class);
            m.setAccessible(true);
            return (String) m.invoke(null, query);
        }
    }

    // ── formatSseEvent ─────────────────────────────────────

    @Nested
    class FormatSseEventTest {

        @Test
        void basicEventFormat() {
            String result = McpSseTransport.formatSseEvent("message", "{\"key\":\"value\"}");
            assertEquals("event: message\ndata: {\"key\":\"value\"}\n\n", result);
        }

        @Test
        void endpointEvent() {
            String result = McpSseTransport.formatSseEvent("endpoint", "/message?sessionId=abc123");
            assertEquals("event: endpoint\ndata: /message?sessionId=abc123\n\n", result);
        }

        @Test
        void startsWithEventPrefix() {
            String result = McpSseTransport.formatSseEvent("test", "data");
            assertTrue(result.startsWith("event: test\n"));
        }

        @Test
        void containsDataPrefix() {
            String result = McpSseTransport.formatSseEvent("test", "payload");
            assertTrue(result.contains("data: payload"));
        }

        @Test
        void endsWithDoubleNewline() {
            String result = McpSseTransport.formatSseEvent("evt", "d");
            assertTrue(result.endsWith("\n\n"));
        }

        @Test
        void emptyData() {
            String result = McpSseTransport.formatSseEvent("ping", "");
            assertEquals("event: ping\ndata: \n\n", result);
        }

        @Test
        void emptyEventType() {
            String result = McpSseTransport.formatSseEvent("", "hello");
            assertEquals("event: \ndata: hello\n\n", result);
        }

        @Test
        void largeJsonPayload() {
            String bigJson = "{\"data\":\"" + "x".repeat(10000) + "\"}";
            String result = McpSseTransport.formatSseEvent("message", bigJson);
            assertTrue(result.startsWith("event: message\ndata: "));
            assertTrue(result.contains(bigJson));
            assertTrue(result.endsWith("\n\n"));
        }
    }

    // ── SSE_KEEP_ALIVE ─────────────────────────────────

    @Nested
    class SseKeepAliveConstantTest {

        @Test
        void isComment() {
            assertTrue(McpSseTransport.SSE_KEEP_ALIVE.startsWith(":"),
                "SSE keep-alive must be a comment (start with ':')");
        }

        @Test
        void exactFormat() {
            assertEquals(": keepalive\n\n", McpSseTransport.SSE_KEEP_ALIVE);
        }

        @Test
        void endsWithDoubleNewline() {
            assertTrue(McpSseTransport.SSE_KEEP_ALIVE.endsWith("\n\n"));
        }
    }

    // ── buildJsonErrorResponse ─────────────────────────────

    @Nested
    class BuildJsonErrorResponseTest {

        @Test
        void basicErrorStructure() {
            String json = McpSseTransport.buildJsonErrorResponse("Something went wrong");
            JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
            assertEquals("Something went wrong", parsed.get("error").getAsString());
        }

        @Test
        void sessionLimitMessage() {
            String json = McpSseTransport.buildJsonErrorResponse("SSE session limit reached (10)");
            JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
            assertEquals("SSE session limit reached (10)", parsed.get("error").getAsString());
        }

        @Test
        void quotesInMessageAreEscaped() {
            String json = McpSseTransport.buildJsonErrorResponse("Error with \"quotes\"");
            // Gson properly escapes double quotes; the result should be valid JSON
            JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
            assertEquals("Error with \"quotes\"", parsed.get("error").getAsString());
        }

        @Test
        void emptyMessage() {
            String json = McpSseTransport.buildJsonErrorResponse("");
            JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
            assertEquals("", parsed.get("error").getAsString());
        }

        @Test
        void hasOnlyErrorField() {
            String json = McpSseTransport.buildJsonErrorResponse("test");
            JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
            assertEquals(1, parsed.size(), "Error response should have exactly 1 field");
        }

        @Test
        void missingSessionMessage() {
            String json = McpSseTransport.buildJsonErrorResponse("Unknown or closed session: abc-123");
            JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
            assertEquals("Unknown or closed session: abc-123", parsed.get("error").getAsString());
        }

        @Test
        void missingSessionIdParam() {
            String json = McpSseTransport.buildJsonErrorResponse("Missing sessionId parameter");
            JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
            assertEquals("Missing sessionId parameter", parsed.get("error").getAsString());
        }
    }
}
