package com.github.catatafishen.agentbridge.services;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the stateless (2026-07-28) request detection and header validation, and that
 * legacy session-based requests are never classified as stateless.
 */
class McpHttpServerStatelessTest {

    private static final String META = "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\"}";

    private static HttpExchange exchangeWith(Headers requestHeaders) {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestHeaders()).thenReturn(requestHeaders);
        return exchange;
    }

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    // ── legacy requests must stay on the session path ──

    @Test
    void legacyInitializeIsNotStateless() {
        assertNull(McpSessionRegistry.statelessVersion(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}"));
    }

    @Test
    void legacyToolsCallIsNotStateless() {
        assertNull(McpSessionRegistry.statelessVersion(
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"x\",\"_meta\":{\"progressToken\":\"t\"}}}"));
    }

    @Test
    void requestWithoutParamsIsNotStateless() {
        assertNull(McpSessionRegistry.statelessVersion("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}"));
    }

    @Test
    void malformedBodyIsNotStateless() {
        assertNull(McpSessionRegistry.statelessVersion("{broken"));
        assertNull(McpSessionRegistry.statelessVersion("[]"));
    }

    // ── stateless detection ──

    @Test
    void metaProtocolVersionOptsIn() {
        assertEquals("2026-07-28", McpSessionRegistry.statelessVersion(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{" + META + "}}"));
    }

    @Test
    void malformedVersionValueIsReturnedAsEmptyAndRejected() {
        String version = McpSessionRegistry.statelessVersion(
            "{\"method\":\"tools/list\",\"params\":{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":{}}}}");
        assertEquals("", version);
        assertTrue(!McpProtocolHandler.isSupportedProtocolVersion(version));
    }

    @Test
    void supportedVersionsIncludeAllKnownRevisions() {
        for (String v : new String[]{"2026-07-28", "2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05"}) {
            assertTrue(McpProtocolHandler.isSupportedProtocolVersion(v), v);
        }
        assertTrue(!McpProtocolHandler.isSupportedProtocolVersion("2030-01-01"));
        assertTrue(!McpProtocolHandler.isSupportedProtocolVersion(null));
    }

    // ── Mcp-Name validation ──

    @Test
    void toolsCallRequiresMatchingMcpName() {
        JsonObject req = json("{\"method\":\"tools/call\",\"params\":{\"name\":\"read_file\"}}");
        Headers headers = new Headers();

        assertNotNull(McpHttpServer.validateNameHeader(exchangeWith(headers), req, "tools/call"));

        headers.set("Mcp-Name", "other");
        assertNotNull(McpHttpServer.validateNameHeader(exchangeWith(headers), req, "tools/call"));

        headers.set("Mcp-Name", "read_file");
        assertNull(McpHttpServer.validateNameHeader(exchangeWith(headers), req, "tools/call"));
    }

    @Test
    void resourcesReadUsesUri() {
        JsonObject req = json("{\"method\":\"resources/read\",\"params\":{\"uri\":\"file:///a.txt\"}}");
        Headers headers = new Headers();
        headers.set("Mcp-Name", "file:///a.txt");
        assertNull(McpHttpServer.validateNameHeader(exchangeWith(headers), req, "resources/read"));
    }

    @Test
    void otherMethodsDoNotNeedMcpName() {
        JsonObject req = json("{\"method\":\"tools/list\"}");
        assertNull(McpHttpServer.validateNameHeader(exchangeWith(new Headers()), req, "tools/list"));
    }

    @Test
    void base64EncodedMcpNameIsDecodedBeforeComparison() {
        String name = "héllo wörld";
        String encoded = "=?base64?" + Base64.getEncoder().encodeToString(name.getBytes(StandardCharsets.UTF_8)) + "?=";
        JsonObject req = new JsonObject();
        req.addProperty("method", "tools/call");
        JsonObject params = new JsonObject();
        params.addProperty("name", name);
        req.add("params", params);
        Headers headers = new Headers();
        headers.set("Mcp-Name", encoded);

        assertNull(McpHttpServer.validateNameHeader(exchangeWith(headers), req, "tools/call"));
    }

    @Test
    void malformedBase64IsRejected() {
        JsonObject req = json("{\"method\":\"tools/call\",\"params\":{\"name\":\"x\"}}");
        Headers headers = new Headers();
        headers.set("Mcp-Name", "=?base64?!!!not-base64!!!?=");
        assertNotNull(McpHttpServer.validateNameHeader(exchangeWith(headers), req, "tools/call"));
    }

    @Test
    void decodeHeaderValueLeavesPlainValuesUntouched() {
        assertEquals("plain", McpHttpServer.decodeHeaderValue("plain"));
    }

    // ── Origin validation ──

    @Test
    void originCheckAllowsAbsentAndLoopbackOnly() {
        assertTrue(McpHttpServer.isAllowedOrigin(null));
        assertTrue(McpHttpServer.isAllowedOrigin(""));
        assertTrue(McpHttpServer.isAllowedOrigin("http://localhost:3000"));
        assertTrue(McpHttpServer.isAllowedOrigin("http://127.0.0.1:8642"));
        assertTrue(McpHttpServer.isAllowedOrigin("http://[::1]:8642"));
        assertTrue(!McpHttpServer.isAllowedOrigin("https://evil.example.com"));
        assertTrue(!McpHttpServer.isAllowedOrigin("http://localhost.evil.com"));
        assertTrue(!McpHttpServer.isAllowedOrigin("not a uri"));
    }

    // ── handleStatelessPost end to end (mock exchange, mock protocol handler) ──

    private static final String TOOLS_CALL_BODY =
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"read_file\"," + META + "}}";

    private record Call(int status, String body, Headers responseHeaders, McpProtocolHandler handler) {
    }

    private Call post(String body, Headers requestHeaders, String handlerReply) throws Exception {
        McpHttpServer server = new McpHttpServer(mock(com.intellij.openapi.project.Project.class));
        McpProtocolHandler handler = mock(McpProtocolHandler.class);
        when(handler.handleMessage(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(handlerReply);
        java.lang.reflect.Field f = McpHttpServer.class.getDeclaredField("protocolHandler");
        f.setAccessible(true);
        f.set(server, handler);

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        Headers responseHeaders = new Headers();
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestHeaders()).thenReturn(requestHeaders);
        when(exchange.getResponseHeaders()).thenReturn(responseHeaders);
        when(exchange.getResponseBody()).thenReturn(out);
        org.mockito.ArgumentCaptor<Integer> status = org.mockito.ArgumentCaptor.forClass(Integer.class);

        String version = McpSessionRegistry.statelessVersion(body);
        server.handleStatelessPost(exchange, body, version, false);

        org.mockito.Mockito.verify(exchange).sendResponseHeaders(status.capture(), org.mockito.ArgumentMatchers.anyLong());
        return new Call(status.getValue(), out.toString(StandardCharsets.UTF_8), responseHeaders, handler);
    }

    private static Headers validHeaders() {
        Headers h = new Headers();
        h.set("MCP-Protocol-Version", "2026-07-28");
        h.set("Mcp-Method", "tools/call");
        h.set("Mcp-Name", "read_file");
        return h;
    }

    @Test
    void validStatelessRequestIsHandledWithFixedOwnerAndNoSessionHeader() throws Exception {
        Headers h = validHeaders();
        h.set("Mcp-Session-Id", "ignored-legacy-session");
        Call call = post(TOOLS_CALL_BODY, h, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}");

        assertEquals(200, call.status());
        assertNull(call.responseHeaders().getFirst("Mcp-Session-Id"));
        org.mockito.Mockito.verify(call.handler()).handleMessage(TOOLS_CALL_BODY, "stateless:default");
    }

    @Test
    void missingProtocolVersionHeaderIsHeaderMismatch() throws Exception {
        Headers h = validHeaders();
        h.remove("MCP-Protocol-Version");
        Call call = post(TOOLS_CALL_BODY, h, "{}");

        assertEquals(400, call.status());
        assertEquals(-32020, json(call.body()).getAsJsonObject("error").get("code").getAsInt());
        org.mockito.Mockito.verifyNoInteractions(call.handler());
    }

    @Test
    void mismatchedMethodHeaderIsRejected() throws Exception {
        Headers h = validHeaders();
        h.set("Mcp-Method", "tools/list");
        Call call = post(TOOLS_CALL_BODY, h, "{}");

        assertEquals(400, call.status());
        assertEquals(-32020, json(call.body()).getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void mismatchedNameHeaderIsRejected() throws Exception {
        Headers h = validHeaders();
        h.set("Mcp-Name", "write_file");
        Call call = post(TOOLS_CALL_BODY, h, "{}");

        assertEquals(400, call.status());
        org.mockito.Mockito.verifyNoInteractions(call.handler());
    }

    @Test
    void unsupportedVersionReturns400WithSupportedList() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":"
            + "{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2030-01-01\"}}}";
        Call call = post(body, new Headers(), "{}");

        assertEquals(400, call.status());
        JsonObject error = json(call.body()).getAsJsonObject("error");
        assertEquals(-32022, error.get("code").getAsInt());
        assertTrue(error.getAsJsonObject("data").getAsJsonArray("supported").toString().contains("2026-07-28"));
    }

    @Test
    void foreignOriginIsForbidden() throws Exception {
        Headers h = validHeaders();
        h.set("Origin", "https://evil.example.com");
        Call call = post(TOOLS_CALL_BODY, h, "{}");

        assertEquals(403, call.status());
        org.mockito.Mockito.verifyNoInteractions(call.handler());
    }

    @Test
    void notificationGets202() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/x\",\"params\":{" + META + "}}";
        Headers h = new Headers();
        h.set("MCP-Protocol-Version", "2026-07-28");
        h.set("Mcp-Method", "notifications/x");
        Call call = post(body, h, null);

        assertEquals(202, call.status());
    }
}
