package com.github.catatafishen.agentbridge.services;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
        assertFalse(McpProtocolHandler.isSupportedProtocolVersion(version));
    }

    @Test
    void supportedVersionsIncludeAllKnownRevisions() {
        for (String v : new String[]{"2026-07-28", "2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05"}) {
            assertTrue(McpProtocolHandler.isSupportedProtocolVersion(v), v);
        }
        assertFalse(McpProtocolHandler.isSupportedProtocolVersion("2030-01-01"));
        assertFalse(McpProtocolHandler.isSupportedProtocolVersion(null));
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
        assertFalse(McpHttpServer.isAllowedOrigin("https://evil.example.com"));
        assertFalse(McpHttpServer.isAllowedOrigin("http://localhost.evil.com"));
        assertFalse(McpHttpServer.isAllowedOrigin("not a uri"));
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

        verify(exchange).sendResponseHeaders(status.capture(), org.mockito.ArgumentMatchers.anyLong());
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
        verify(call.handler()).handleMessage(TOOLS_CALL_BODY, "stateless:default");
    }

    @Test
    void missingProtocolVersionHeaderIsHeaderMismatch() throws Exception {
        Headers h = validHeaders();
        h.remove("MCP-Protocol-Version");
        Call call = post(TOOLS_CALL_BODY, h, "{}");

        assertEquals(400, call.status());
        assertEquals(-32020, json(call.body()).getAsJsonObject("error").get("code").getAsInt());
        verifyNoInteractions(call.handler());
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
        verifyNoInteractions(call.handler());
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
        verifyNoInteractions(call.handler());
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

    // ── rejections echo the request id so the client can correlate them ──

    @Test
    void headerMismatchEchoesTheRequestId() throws Exception {
        Headers h = validHeaders();
        h.set("Mcp-Method", "tools/list");
        Call call = post(TOOLS_CALL_BODY, h, "{}");

        assertEquals(400, call.status());
        assertEquals(1, json(call.body()).get("id").getAsInt());
    }

    @Test
    void missingProtocolVersionHeaderEchoesTheRequestId() throws Exception {
        Headers h = validHeaders();
        h.remove("MCP-Protocol-Version");
        Call call = post(TOOLS_CALL_BODY, h, "{}");

        assertEquals(1, json(call.body()).get("id").getAsInt());
    }

    @Test
    void nameMismatchEchoesTheRequestId() throws Exception {
        Headers h = validHeaders();
        h.set("Mcp-Name", "write_file");
        Call call = post(TOOLS_CALL_BODY, h, "{}");

        assertEquals(1, json(call.body()).get("id").getAsInt());
    }

    @Test
    void forbiddenOriginEchoesTheRequestId() throws Exception {
        Headers h = validHeaders();
        h.set("Origin", "https://evil.example.com");
        Call call = post(TOOLS_CALL_BODY, h, "{}");

        assertEquals(403, call.status());
        assertEquals(1, json(call.body()).get("id").getAsInt());
    }

    @Test
    void unsupportedVersionEchoesTheRequestId() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":\"req-7\",\"method\":\"tools/list\",\"params\":"
            + "{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2030-01-01\"}}}";
        Call call = post(body, new Headers(), "{}");

        assertEquals(400, call.status());
        assertEquals("req-7", json(call.body()).get("id").getAsString(), "string ids must round-trip too");
    }

    @Test
    void rejectionOfARequestWithoutAnIdHasNoId() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/x\",\"params\":{" + META + "}}";
        Call call = post(body, new Headers(), "{}");

        assertEquals(400, call.status());
        assertFalse(json(call.body()).has("id"), "a notification has no id to echo");
    }

    // ── terminals created under the shared stateless owner must not outlive the server ──

    private static HttpExchange emptyExchange(Headers requestHeaders) {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestHeaders()).thenReturn(requestHeaders);
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getResponseBody()).thenReturn(new java.io.ByteArrayOutputStream());
        return exchange;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field f = McpHttpServer.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void aCallFinishingAfterTheServerStoppedReleasesTheTerminalsItCreated() throws Exception {
        com.intellij.openapi.project.Project project = mock(com.intellij.openapi.project.Project.class);
        AgentTabTracker tracker = mock(AgentTabTracker.class);
        when(project.getServiceIfCreated(AgentTabTracker.class)).thenReturn(tracker);
        McpHttpServer server = new McpHttpServer(project);
        McpProtocolHandler handler = mock(McpProtocolHandler.class);
        // The request outlives the server: stop() has already run by the time the handler returns.
        when(handler.handleMessage(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}");
        setField(server, "protocolHandler", handler);
        setField(server, "running", false);

        server.handleStatelessPost(emptyExchange(validHeaders()), TOOLS_CALL_BODY, "2026-07-28", false);

        verify(tracker).closeOwnedTerminalTabs("stateless:default");
    }

    @Test
    void aCallOnARunningServerLeavesItsTerminalsAlone() throws Exception {
        com.intellij.openapi.project.Project project = mock(com.intellij.openapi.project.Project.class);
        AgentTabTracker tracker = mock(AgentTabTracker.class);
        when(project.getServiceIfCreated(AgentTabTracker.class)).thenReturn(tracker);
        McpHttpServer server = new McpHttpServer(project);
        McpProtocolHandler handler = mock(McpProtocolHandler.class);
        when(handler.handleMessage(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}");
        setField(server, "protocolHandler", handler);
        setField(server, "running", true);

        server.handleStatelessPost(emptyExchange(validHeaders()), TOOLS_CALL_BODY, "2026-07-28", false);

        verifyNoInteractions(tracker);
    }

    @Test
    void aDisposedProjectIsNotAskedForTheTracker() throws Exception {
        com.intellij.openapi.project.Project project = mock(com.intellij.openapi.project.Project.class);
        when(project.isDisposed()).thenReturn(true);
        McpHttpServer server = new McpHttpServer(project);
        McpProtocolHandler handler = mock(McpProtocolHandler.class);
        when(handler.handleMessage(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}");
        setField(server, "protocolHandler", handler);
        setField(server, "running", false);

        server.handleStatelessPost(emptyExchange(validHeaders()), TOOLS_CALL_BODY, "2026-07-28", false);

        // Asking a disposed project for a service throws; the cleanup must not even try.
        verify(project, never()).getServiceIfCreated(AgentTabTracker.class);
    }
}
