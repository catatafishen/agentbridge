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
}
