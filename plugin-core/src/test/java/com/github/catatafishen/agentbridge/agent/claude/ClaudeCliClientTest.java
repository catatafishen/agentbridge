package com.github.catatafishen.agentbridge.agent.claude;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class ClaudeCliClientTest {

    // ── extractErrorText (private static) ───────────────────────────────

    @Test
    void extractErrorText_string() throws Exception {
        assertEquals("something broke", invokeExtractErrorText(new JsonPrimitive("something broke")));
    }

    @Test
    void extractErrorText_objectWithMessage() throws Exception {
        JsonObject obj = new JsonObject();
        obj.addProperty("message", "rate limit");
        assertEquals("rate limit", invokeExtractErrorText(obj));
    }

    @Test
    void extractErrorText_objectWithoutMessage() throws Exception {
        JsonObject obj = new JsonObject();
        obj.addProperty("code", 500);
        assertEquals(obj.toString(), invokeExtractErrorText(obj));
    }

    @Test
    void extractErrorText_number() throws Exception {
        assertEquals("42", invokeExtractErrorText(new JsonPrimitive(42)));
    }

    @Test
    void extractErrorText_boolean() throws Exception {
        assertEquals("true", invokeExtractErrorText(new JsonPrimitive(true)));
    }

    // ── safeGetInt (private static) ─────────────────────────────────────

    @Test
    void safeGetInt_exists() throws Exception {
        JsonObject obj = new JsonObject();
        obj.addProperty("tokens", 100);
        assertEquals(100, invokeSafeGetInt(obj, "tokens"));
    }

    @Test
    void safeGetInt_missing() throws Exception {
        assertEquals(0, invokeSafeGetInt(new JsonObject(), "tokens"));
    }

    @Test
    void safeGetInt_null() throws Exception {
        JsonObject obj = new JsonObject();
        obj.add("tokens", JsonNull.INSTANCE);
        assertEquals(0, invokeSafeGetInt(obj, "tokens"));
    }

    @Test
    void safeGetInt_zero() throws Exception {
        JsonObject obj = new JsonObject();
        obj.addProperty("tokens", 0);
        assertEquals(0, invokeSafeGetInt(obj, "tokens"));
    }

    // ── safeGetDouble (private static) ──────────────────────────────────

    @Test
    void safeGetDouble_exists() throws Exception {
        JsonObject obj = new JsonObject();
        obj.addProperty("cost", 1.5);
        assertEquals(1.5, invokeSafeGetDouble(obj, "cost"), 0.001);
    }

    @Test
    void safeGetDouble_missing() throws Exception {
        assertEquals(0.0, invokeSafeGetDouble(new JsonObject(), "cost"), 0.001);
    }

    @Test
    void safeGetDouble_null() throws Exception {
        JsonObject obj = new JsonObject();
        obj.add("cost", JsonNull.INSTANCE);
        assertEquals(0.0, invokeSafeGetDouble(obj, "cost"), 0.001);
    }

    @Test
    void safeGetDouble_zero() throws Exception {
        JsonObject obj = new JsonObject();
        obj.addProperty("cost", 0.0);
        assertEquals(0.0, invokeSafeGetDouble(obj, "cost"), 0.001);
    }

    @Test
    void safeGetDouble_intValue() throws Exception {
        JsonObject obj = new JsonObject();
        obj.addProperty("cost", 5);
        assertEquals(5.0, invokeSafeGetDouble(obj, "cost"), 0.001);
    }

    // ── Reflection helpers ──────────────────────────────────────────────

    private static String invokeExtractErrorText(JsonElement el) throws Exception {
        Method m = ClaudeCliClient.class.getDeclaredMethod("extractErrorText", JsonElement.class);
        m.setAccessible(true);
        return (String) m.invoke(null, el);
    }

    private static int invokeSafeGetInt(JsonObject obj, String field) throws Exception {
        Method m = ClaudeCliClient.class.getDeclaredMethod("safeGetInt", JsonObject.class, String.class);
        m.setAccessible(true);
        return (int) m.invoke(null, obj, field);
    }

    private static double invokeSafeGetDouble(JsonObject obj, String field) throws Exception {
        Method m = ClaudeCliClient.class.getDeclaredMethod("safeGetDouble", JsonObject.class, String.class);
        m.setAccessible(true);
        return (double) m.invoke(null, obj, field);
    }
}
