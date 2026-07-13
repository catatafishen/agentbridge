package com.github.catatafishen.agentbridge.services;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MCP transport session registry")
class McpSessionRegistryTest {

    @Test
    @DisplayName("creates unique active sessions and closes only the requested one")
    void managesSessionLifecycle() {
        McpSessionRegistry registry = new McpSessionRegistry();

        String first = registry.openSession();
        String second = registry.openSession();

        assertNotEquals(first, second);
        assertTrue(registry.touch(first));
        assertTrue(registry.touch(second));
        assertTrue(registry.closeSession(first));
        assertFalse(registry.touch(first));
        assertTrue(registry.touch(second));
        assertFalse(registry.closeSession("unknown"));
    }

    @Test
    @DisplayName("drain returns every live session and empties the registry")
    void drainsSessions() {
        McpSessionRegistry registry = new McpSessionRegistry();
        String first = registry.openSession();
        String second = registry.openSession();

        Set<String> drained = registry.drainSessions();

        assertEquals(Set.of(first, second), drained);
        assertFalse(registry.touch(first));
        assertFalse(registry.touch(second));
    }

    @Test
    @DisplayName("expires only idle sessions and refreshes activity on use")
    void expiresIdleSessions() {
        AtomicLong now = new AtomicLong();
        McpSessionRegistry registry = new McpSessionRegistry(now::get);
        String first = registry.openSession();
        now.set(10);
        String second = registry.openSession();
        now.set(20);
        assertTrue(registry.touch(first));
        assertFalse(registry.touch("unknown"));

        now.set(25);
        assertEquals(Set.of(second), registry.expireIdleSessions(10));
        assertTrue(registry.touch(first));
        assertFalse(registry.touch(second));

        now.set(35);
        assertEquals(Set.of(first), registry.expireIdleSessions(10));
        assertFalse(registry.touch(first));
    }

    @Test
    @DisplayName("classifies initialize separately from established requests")
    void classifiesRequests() {
        assertEquals(McpSessionRegistry.RequestKind.INITIALIZE,
            McpSessionRegistry.classifyRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"));
        assertEquals(McpSessionRegistry.RequestKind.ESTABLISHED,
            McpSessionRegistry.classifyRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"));
        assertEquals(McpSessionRegistry.RequestKind.ESTABLISHED,
            McpSessionRegistry.classifyRequest(
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));
    }

    @Test
    @DisplayName("malformed or methodless JSON is not treated as an established request")
    void classifiesInvalidRequests() {
        assertEquals(McpSessionRegistry.RequestKind.INVALID,
            McpSessionRegistry.classifyRequest("{broken"));
        assertEquals(McpSessionRegistry.RequestKind.INVALID,
            McpSessionRegistry.classifyRequest("{\"jsonrpc\":\"2.0\",\"id\":1}"));
    }

    @Test
    @DisplayName("owner keys keep HTTP and SSE namespaces separate")
    void namespacesOwnerKeys() {
        assertEquals("http:abc", McpSessionRegistry.ownerKey("http", "abc"));
        assertEquals("sse:abc", McpSessionRegistry.ownerKey("sse", "abc"));
        assertNotEquals(
            McpSessionRegistry.ownerKey("http", "abc"),
            McpSessionRegistry.ownerKey("sse", "abc"));
    }
}
