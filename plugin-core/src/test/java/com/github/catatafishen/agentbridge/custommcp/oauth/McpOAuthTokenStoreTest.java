package com.github.catatafishen.agentbridge.custommcp.oauth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class McpOAuthTokenStoreTest {

    @Test
    void normalizeUrl_stripsPathAndQuery() {
        assertEquals(
            "https://auth.example.com",
            McpOAuthTokenStore.normalizeUrl("https://auth.example.com/oauth/token?foo=bar")
        );
    }

    @Test
    void normalizeUrl_preservesExplicitPort() {
        assertEquals(
            "http://localhost:8080",
            McpOAuthTokenStore.normalizeUrl("http://localhost:8080/mcp")
        );
    }

    @Test
    void normalizeUrl_lowercasesHostAndScheme() {
        assertEquals(
            "https://my.server.com",
            McpOAuthTokenStore.normalizeUrl("HTTPS://My.Server.Com/path")
        );
    }

    @Test
    void normalizeUrl_sameOriginDifferentPaths_producesSameKey() {
        String a = McpOAuthTokenStore.normalizeUrl("https://example.com/mcp");
        String b = McpOAuthTokenStore.normalizeUrl("https://example.com/v1/mcp");
        assertEquals(a, b, "different paths on the same origin should produce the same key");
    }

    @Test
    void normalizeUrl_differentPorts_produceDifferentKeys() {
        String a = McpOAuthTokenStore.normalizeUrl("http://localhost:8080/mcp");
        String b = McpOAuthTokenStore.normalizeUrl("http://localhost:9090/mcp");
        org.junit.jupiter.api.Assertions.assertNotEquals(a, b);
    }
}
