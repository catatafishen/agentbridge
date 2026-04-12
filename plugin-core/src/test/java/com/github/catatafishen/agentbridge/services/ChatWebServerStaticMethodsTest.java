package com.github.catatafishen.agentbridge.services;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for pure static methods in {@link ChatWebServer}.
 */
class ChatWebServerStaticMethodsTest {

    private static final Method ESC_JS;
    private static final Method JSON_STRING;

    static {
        try {
            ESC_JS = ChatWebServer.class.getDeclaredMethod("escJs", String.class);
            ESC_JS.setAccessible(true);

            JSON_STRING = ChatWebServer.class.getDeclaredMethod("jsonString", String.class, String.class);
            JSON_STRING.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("Method not found — signature changed?", e);
        }
    }

    // ── escJs ───────────────────────────────────────────────

    @Test
    void escJs_wrapsInDoubleQuotes() throws Exception {
        String result = invokeEscJs("hello");
        assertTrue(result.startsWith("\""), "Should start with quote");
        assertTrue(result.endsWith("\""), "Should end with quote");
    }

    @Test
    void escJs_plainString() throws Exception {
        assertEquals("\"hello world\"", invokeEscJs("hello world"));
    }

    @Test
    void escJs_escapesBackslash() throws Exception {
        assertEquals("\"a\\\\b\"", invokeEscJs("a\\b"));
    }

    @Test
    void escJs_escapesDoubleQuotes() throws Exception {
        assertEquals("\"say \\\"hi\\\"\"", invokeEscJs("say \"hi\""));
    }

    @Test
    void escJs_escapesNewlines() throws Exception {
        assertEquals("\"line1\\nline2\"", invokeEscJs("line1\nline2"));
    }

    @Test
    void escJs_escapesCarriageReturn() throws Exception {
        assertEquals("\"a\\rb\"", invokeEscJs("a\rb"));
    }

    @Test
    void escJs_combinedEscapes() throws Exception {
        String result = invokeEscJs("a\\b\"c\nd\re");
        assertEquals("\"a\\\\b\\\"c\\nd\\re\"", result);
    }

    @Test
    void escJs_emptyString() throws Exception {
        assertEquals("\"\"", invokeEscJs(""));
    }

    // ── jsonString ──────────────────────────────────────────

    @Test
    void jsonString_extractsKeyValue() throws Exception {
        assertEquals("hello", invokeJsonString("{\"key\":\"hello\"}", "key"));
    }

    @Test
    void jsonString_returnsNullForMissingKey() throws Exception {
        assertNull(invokeJsonString("{\"other\":\"value\"}", "key"));
    }

    @Test
    void jsonString_returnsNullForInvalidJson() throws Exception {
        assertNull(invokeJsonString("not json at all", "key"));
    }

    @Test
    void jsonString_handlesNumericValues() throws Exception {
        // Gson deserializes numbers to Double by default
        String result = invokeJsonString("{\"port\":8080}", "port");
        assertNotNull(result);
        assertTrue(result.contains("8080"), "Should contain the number");
    }

    @Test
    void jsonString_handlesNestedJson() throws Exception {
        // Returns toString() of nested object
        String result = invokeJsonString("{\"nested\":{\"a\":1}}", "nested");
        assertNotNull(result);
        assertTrue(result.contains("a"), "Should contain the nested key");
    }

    // ── Reflection helpers ──────────────────────────────────

    private static String invokeEscJs(String s) throws Exception {
        return (String) ESC_JS.invoke(null, s);
    }

    private static String invokeJsonString(String body, String key) throws Exception {
        return (String) JSON_STRING.invoke(null, body, key);
    }
}
