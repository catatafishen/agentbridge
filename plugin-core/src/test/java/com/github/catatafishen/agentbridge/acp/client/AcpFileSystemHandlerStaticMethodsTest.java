package com.github.catatafishen.agentbridge.acp.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for pure static methods in {@link AcpFileSystemHandler}.
 */
class AcpFileSystemHandlerStaticMethodsTest {

    private static final Method GET_REQUIRED_STRING;
    private static final Method GET_OPTIONAL_INT;

    static {
        try {
            GET_REQUIRED_STRING = AcpFileSystemHandler.class
                .getDeclaredMethod("getRequiredString", JsonObject.class, String.class);
            GET_REQUIRED_STRING.setAccessible(true);

            GET_OPTIONAL_INT = AcpFileSystemHandler.class
                .getDeclaredMethod("getOptionalInt", JsonObject.class, String.class);
            GET_OPTIONAL_INT.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("Method not found — signature changed?", e);
        }
    }

    // ── getRequiredString ───────────────────────────────────

    @Test
    void getRequiredString_returnsValue() throws Exception {
        JsonObject obj = new JsonObject();
        obj.addProperty("path", "/tmp/file.txt");
        assertEquals("/tmp/file.txt", invokeGetRequiredString(obj, "path"));
    }

    @Test
    void getRequiredString_throwsForMissingKey() {
        JsonObject obj = new JsonObject();
        var ex = assertThrows(Exception.class, () -> invokeGetRequiredString(obj, "path"));
        assertTrue(ex.getCause().getMessage().contains("Missing required parameter"));
    }

    @Test
    void getRequiredString_throwsForNonPrimitive() {
        JsonObject obj = new JsonObject();
        obj.add("path", new JsonArray());
        var ex = assertThrows(Exception.class, () -> invokeGetRequiredString(obj, "path"));
        assertTrue(ex.getCause().getMessage().contains("Missing required parameter"));
    }

    @Test
    void getRequiredString_throwsForNullValue() {
        JsonObject obj = new JsonObject();
        obj.add("path", JsonNull.INSTANCE);
        var ex = assertThrows(Exception.class, () -> invokeGetRequiredString(obj, "path"));
        assertTrue(ex.getCause().getMessage().contains("Missing required parameter"));
    }

    // ── getOptionalInt ──────────────────────────────────────

    @Test
    void getOptionalInt_returnsValue() throws Exception {
        JsonObject obj = new JsonObject();
        obj.addProperty("line", 42);
        assertEquals(42, invokeGetOptionalInt(obj, "line"));
    }

    @Test
    void getOptionalInt_returnsNullForMissingKey() throws Exception {
        assertNull(invokeGetOptionalInt(new JsonObject(), "line"));
    }

    @Test
    void getOptionalInt_returnsNullForNonPrimitive() throws Exception {
        JsonObject obj = new JsonObject();
        obj.add("line", new JsonObject());
        assertNull(invokeGetOptionalInt(obj, "line"));
    }

    @Test
    void getOptionalInt_returnsNullForNull() throws Exception {
        JsonObject obj = new JsonObject();
        obj.add("line", JsonNull.INSTANCE);
        assertNull(invokeGetOptionalInt(obj, "line"));
    }

    // ── Reflection helpers ──────────────────────────────────

    private static String invokeGetRequiredString(JsonObject obj, String key) throws Exception {
        return (String) GET_REQUIRED_STRING.invoke(null, obj, key);
    }

    private static Integer invokeGetOptionalInt(JsonObject obj, String key) throws Exception {
        return (Integer) GET_OPTIONAL_INT.invoke(null, obj, key);
    }
}
