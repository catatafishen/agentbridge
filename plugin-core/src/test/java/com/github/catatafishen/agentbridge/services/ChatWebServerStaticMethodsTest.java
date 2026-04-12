package com.github.catatafishen.agentbridge.services;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

    // ── buildIconSvg ────────────────────────────────────────

    @Test
    void buildIconSvg_returnsValidSvg() throws Exception {
        Method m = ChatWebServer.class.getDeclaredMethod("buildIconSvg");
        m.setAccessible(true);
        String svg = (String) m.invoke(null);
        assertNotNull(svg);
        assertTrue(svg.startsWith("<svg"), "Should start with <svg");
        assertTrue(svg.endsWith("</svg>"), "Should end with </svg>");
        assertTrue(svg.contains("xmlns=\"http://www.w3.org/2000/svg\""), "Should have SVG namespace");
    }

    @Test
    void buildIconSvg_containsLightningBolt() throws Exception {
        Method m = ChatWebServer.class.getDeclaredMethod("buildIconSvg");
        m.setAccessible(true);
        String svg = (String) m.invoke(null);
        assertTrue(svg.contains("<path"), "Should contain a path element (lightning bolt)");
    }

    @Test
    void buildIconSvg_contains256x256Viewport() throws Exception {
        Method m = ChatWebServer.class.getDeclaredMethod("buildIconSvg");
        m.setAccessible(true);
        String svg = (String) m.invoke(null);
        assertTrue(svg.contains("width=\"256\""), "Should have width=256");
        assertTrue(svg.contains("height=\"256\""), "Should have height=256");
    }

    // ── generateBadgePng ────────────────────────────────────

    @Test
    void generateBadgePng_returnsNonEmptyBytes() throws Exception {
        assumeGraphicsAvailable();
        Method m = ChatWebServer.class.getDeclaredMethod("generateBadgePng", int.class);
        m.setAccessible(true);
        byte[] png = (byte[]) m.invoke(null, 96);
        assertNotNull(png);
        assertTrue(png.length > 0, "PNG output should not be empty");
    }

    @Test
    void generateBadgePng_hasPngSignature() throws Exception {
        assumeGraphicsAvailable();
        Method m = ChatWebServer.class.getDeclaredMethod("generateBadgePng", int.class);
        m.setAccessible(true);
        byte[] png = (byte[]) m.invoke(null, 96);
        // PNG magic bytes: 0x89 'P' 'N' 'G'
        assertTrue(png.length >= 4);
        assertEquals((byte) 0x89, png[0]);
        assertEquals((byte) 'P', png[1]);
        assertEquals((byte) 'N', png[2]);
        assertEquals((byte) 'G', png[3]);
    }

    // ── generateIconPng ─────────────────────────────────────

    @Test
    void generateIconPng_returnsNonEmptyBytes() throws Exception {
        assumeGraphicsAvailable();
        Method m = ChatWebServer.class.getDeclaredMethod("generateIconPng", int.class);
        m.setAccessible(true);
        byte[] png = (byte[]) m.invoke(null, 192);
        assertNotNull(png);
        assertTrue(png.length > 0, "PNG output should not be empty");
    }

    @Test
    void generateIconPng_hasPngSignature() throws Exception {
        assumeGraphicsAvailable();
        Method m = ChatWebServer.class.getDeclaredMethod("generateIconPng", int.class);
        m.setAccessible(true);
        byte[] png = (byte[]) m.invoke(null, 192);
        assertTrue(png.length >= 4);
        assertEquals((byte) 0x89, png[0]);
        assertEquals((byte) 'P', png[1]);
        assertEquals((byte) 'N', png[2]);
        assertEquals((byte) 'G', png[3]);
    }

    @Test
    void generateIconPng_differentSizesProduceDifferentOutput() throws Exception {
        assumeGraphicsAvailable();
        Method m = ChatWebServer.class.getDeclaredMethod("generateIconPng", int.class);
        m.setAccessible(true);
        byte[] small = (byte[]) m.invoke(null, 64);
        byte[] large = (byte[]) m.invoke(null, 512);
        // Larger size should produce more bytes (more pixel data)
        assertTrue(large.length > small.length,
            "512px icon (" + large.length + " bytes) should be larger than 64px icon (" + small.length + " bytes)");
    }

    // ── Graphics availability check ─────────────────────────

    private static void assumeGraphicsAvailable() {
        try {
            new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        } catch (java.awt.HeadlessException e) {
            assumeTrue(false, "AWT graphics not available in headless environment");
        }
    }
}
