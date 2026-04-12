package com.github.catatafishen.agentbridge.services;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for pure static methods in {@link McpSseTransport}.
 * Uses reflection since the methods are package-private/private.
 */
class McpSseTransportTest {

    // ── parseSessionId ─────────────────────────────────────

    @Test
    void parseSessionId_returnsValueForMatchingKey() throws Exception {
        assertEquals("abc123", invokeParseSessionId("sessionId=abc123"));
    }

    @Test
    void parseSessionId_findsAmongMultipleParams() throws Exception {
        assertEquals("xyz", invokeParseSessionId("foo=bar&sessionId=xyz&baz=qux"));
    }

    @Test
    void parseSessionId_returnsNullWhenKeyMissing() throws Exception {
        assertNull(invokeParseSessionId("foo=bar&other=value"));
    }

    @Test
    void parseSessionId_returnsNullForNullQuery() throws Exception {
        assertNull(invokeParseSessionId(null));
    }

    @Test
    void parseSessionId_returnsEmptyStringForEmptyValue() throws Exception {
        assertEquals("", invokeParseSessionId("sessionId="));
    }

    @Test
    void parseSessionId_handlesNoEqualsSign() throws Exception {
        assertNull(invokeParseSessionId("sessionId"));
    }

    @Test
    void parseSessionId_handlesValueWithEqualsSign() throws Exception {
        assertEquals("a=b", invokeParseSessionId("sessionId=a=b"));
    }

    // ── Reflection helper ──────────────────────────────────

    private static String invokeParseSessionId(String query) throws Exception {
        Method m = McpSseTransport.class.getDeclaredMethod("parseSessionId", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, query);
    }
}
