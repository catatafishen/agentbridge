package com.github.catatafishen.agentbridge.services;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for hash computation and matching in {@link ToolChipRegistry}.
 * The chip registry correlates ACP tool calls with MCP tool invocations
 * using deterministic hashing — correctness here prevents UI mismatches.
 */
class ToolChipRegistryTest {

    // ── computeBaseHash ──────────────────────────────────────────────────────

    @Test
    void computeBaseHash_producesConsistentHashes() {
        JsonObject args = new JsonObject();
        args.addProperty("path", "/src/Main.java");
        args.addProperty("content", "hello world");

        String hash1 = ToolChipRegistry.computeBaseHash(args);
        String hash2 = ToolChipRegistry.computeBaseHash(args);

        assertNotNull(hash1);
        assertEquals(8, hash1.length()); // 8-character hex hash
        assertEquals(hash1, hash2);
    }

    @Test
    void computeBaseHash_isOrderIndependent() {
        JsonObject args1 = new JsonObject();
        args1.addProperty("path", "a.txt");
        args1.addProperty("content", "hello");

        JsonObject args2 = new JsonObject();
        args2.addProperty("content", "hello");
        args2.addProperty("path", "a.txt");

        assertEquals(ToolChipRegistry.computeBaseHash(args1),
            ToolChipRegistry.computeBaseHash(args2));
    }

    @Test
    void computeBaseHash_excludesToolUsePurpose() {
        JsonObject with = new JsonObject();
        with.addProperty("path", "a.txt");
        with.addProperty("__tool_use_purpose", "reading");

        JsonObject without = new JsonObject();
        without.addProperty("path", "a.txt");

        assertEquals(ToolChipRegistry.computeBaseHash(with),
            ToolChipRegistry.computeBaseHash(without));
    }

    @Test
    void computeBaseHash_differentArgsDifferentHashes() {
        JsonObject args1 = new JsonObject();
        args1.addProperty("path", "a.txt");

        JsonObject args2 = new JsonObject();
        args2.addProperty("path", "b.txt");

        assertNotEquals(ToolChipRegistry.computeBaseHash(args1),
            ToolChipRegistry.computeBaseHash(args2));
    }

    @Test
    void computeBaseHash_handlesEmptyArgs() {
        String hash = ToolChipRegistry.computeBaseHash(new JsonObject());
        assertNotNull(hash);
        assertEquals(8, hash.length());
    }

    @Test
    void computeBaseHash_handlesNestedObjects() {
        JsonObject nested = new JsonObject();
        nested.addProperty("inner", "value");

        JsonObject args = new JsonObject();
        args.add("obj", nested);
        args.addProperty("simple", "text");

        String hash = ToolChipRegistry.computeBaseHash(args);
        assertNotNull(hash);
        assertEquals(8, hash.length());
    }

    @Test
    void computeBaseHash_handlesArrayArgs() {
        JsonArray arr = new JsonArray();
        arr.add("one");
        arr.add("two");

        JsonObject args = new JsonObject();
        args.add("items", arr);

        String hash = ToolChipRegistry.computeBaseHash(args);
        assertNotNull(hash);
        assertEquals(8, hash.length());
    }

    // ── computeStableValue ───────────────────────────────────────────────────

    @Test
    void computeStableValue_nullReturnsLiteral() throws Exception {
        assertEquals("null", invokeComputeStableValue(null));
        assertEquals("null", invokeComputeStableValue(JsonNull.INSTANCE));
    }

    @Test
    void computeStableValue_stringRetainsQuotes() throws Exception {
        String result = invokeComputeStableValue(new JsonPrimitive("hello"));
        assertEquals("\"hello\"", result);
    }

    @Test
    void computeStableValue_integerNormalized() throws Exception {
        // 42.0 should be normalized to "42" (not "42.0")
        String result = invokeComputeStableValue(new JsonPrimitive(42));
        assertEquals("42", result);
    }

    @Test
    void computeStableValue_nonIntegerDoublePreserved() throws Exception {
        String result = invokeComputeStableValue(new JsonPrimitive(3.14));
        assertEquals("3.14", result);
    }

    @Test
    void computeStableValue_objectSortedByKeys() throws Exception {
        JsonObject obj = new JsonObject();
        obj.addProperty("z", "last");
        obj.addProperty("a", "first");

        String result = invokeComputeStableValue(obj);
        // TreeMap.toString() produces {a=first, z=last}
        assertTrue(result.startsWith("{a="));
        assertTrue(result.contains("z="));
    }

    @Test
    void computeStableValue_arrayPreservesOrder() throws Exception {
        JsonArray arr = new JsonArray();
        arr.add("first");
        arr.add("second");

        String result = invokeComputeStableValue(arr);
        assertTrue(result.contains("first"));
        assertTrue(result.contains("second"));
        assertTrue(result.indexOf("first") < result.indexOf("second"));
    }

    @Test
    void computeStableValue_booleanRendered() throws Exception {
        assertEquals("true", invokeComputeStableValue(new JsonPrimitive(true)));
        assertEquals("false", invokeComputeStableValue(new JsonPrimitive(false)));
    }

    // ── isMatchingHash ───────────────────────────────────────────────────────

    @Test
    void isMatchingHash_exactMatch() throws Exception {
        assertTrue(invokeIsMatchingHash("abc12345", "abc12345"));
    }

    @Test
    void isMatchingHash_prefixWithDash() throws Exception {
        assertTrue(invokeIsMatchingHash("abc12345-2", "abc12345"));
    }

    @Test
    void isMatchingHash_noMatchDifferentHash() throws Exception {
        assertFalse(invokeIsMatchingHash("xyz99999", "abc12345"));
    }

    @Test
    void isMatchingHash_prefixWithoutDashDoesNotMatch() throws Exception {
        assertFalse(invokeIsMatchingHash("abc12345extra", "abc12345"));
    }

    // ── Reflection helpers ───────────────────────────────────────────────────

    private static String invokeComputeStableValue(com.google.gson.JsonElement value) throws Exception {
        Method m = ToolChipRegistry.class.getDeclaredMethod("computeStableValue", com.google.gson.JsonElement.class);
        m.setAccessible(true);
        return (String) m.invoke(null, value);
    }

    private static boolean invokeIsMatchingHash(String chipId, String baseHash) throws Exception {
        Method m = ToolChipRegistry.class.getDeclaredMethod("isMatchingHash", String.class, String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, chipId, baseHash);
    }
}
