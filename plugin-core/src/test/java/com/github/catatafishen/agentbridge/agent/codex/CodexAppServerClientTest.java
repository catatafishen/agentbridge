package com.github.catatafishen.agentbridge.agent.codex;

import com.github.catatafishen.agentbridge.bridge.TransportType;
import com.github.catatafishen.agentbridge.services.AgentProfile;
import com.github.catatafishen.agentbridge.services.McpInjectionMethod;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexAppServerClientTest {

    // ── extractTurnErrorMessage (private static) ────────────────────────

    @Test
    void extractTurnErrorMessage_noError() throws Exception {
        assertEquals("Codex turn failed", invokeExtractTurnErrorMessage(new JsonObject()));
    }

    @Test
    void extractTurnErrorMessage_nullError() throws Exception {
        JsonObject turn = new JsonObject();
        turn.add("error", JsonNull.INSTANCE);
        assertEquals("Codex turn failed", invokeExtractTurnErrorMessage(turn));
    }

    @Test
    void extractTurnErrorMessage_stringError() throws Exception {
        JsonObject turn = new JsonObject();
        turn.addProperty("error", "something broke");
        assertEquals("something broke", invokeExtractTurnErrorMessage(turn));
    }

    @Test
    void extractTurnErrorMessage_objectWithMessage() throws Exception {
        JsonObject turn = new JsonObject();
        JsonObject err = new JsonObject();
        err.addProperty("message", "rate limit exceeded");
        turn.add("error", err);
        assertEquals("rate limit exceeded", invokeExtractTurnErrorMessage(turn));
    }

    @Test
    void extractTurnErrorMessage_objectWithoutMessage() throws Exception {
        JsonObject turn = new JsonObject();
        JsonObject err = new JsonObject();
        err.addProperty("code", 500);
        turn.add("error", err);
        // Falls back to err.toString()
        assertEquals(err.toString(), invokeExtractTurnErrorMessage(turn));
    }

    @Test
    void extractTurnErrorMessage_nestedJsonMessage() throws Exception {
        JsonObject inner = new JsonObject();
        JsonObject innerError = new JsonObject();
        innerError.addProperty("message", "actual root cause");
        inner.add("error", innerError);

        JsonObject turn = new JsonObject();
        JsonObject err = new JsonObject();
        err.addProperty("message", inner.toString());
        turn.add("error", err);

        assertEquals("actual root cause", invokeExtractTurnErrorMessage(turn));
    }

    @Test
    void extractTurnErrorMessage_nestedJsonButNoInnerMessage() throws Exception {
        JsonObject inner = new JsonObject();
        inner.addProperty("code", 500);

        JsonObject turn = new JsonObject();
        JsonObject err = new JsonObject();
        err.addProperty("message", inner.toString());
        turn.add("error", err);

        // Falls through nested unwrap (no error.message inside) → returns raw string
        assertEquals(inner.toString(), invokeExtractTurnErrorMessage(turn));
    }

    // ── parseModelEntry (private static) ────────────────────────────────

    @Test
    void parseModelEntry_validModel() throws Exception {
        JsonObject m = new JsonObject();
        m.addProperty("id", "gpt-4");
        m.addProperty("name", "GPT-4");
        assertNotNull(invokeParseModelEntry(m));
    }

    @Test
    void parseModelEntry_idWithoutName() throws Exception {
        JsonObject m = new JsonObject();
        m.addProperty("id", "custom-model");
        assertNotNull(invokeParseModelEntry(m));
    }

    @Test
    void parseModelEntry_missingId() throws Exception {
        JsonObject m = new JsonObject();
        m.addProperty("name", "No ID");
        assertNull(invokeParseModelEntry(m));
    }

    @Test
    void parseModelEntry_emptyId() throws Exception {
        JsonObject m = new JsonObject();
        m.addProperty("id", "");
        assertNull(invokeParseModelEntry(m));
    }

    @Test
    void parseModelEntry_notJsonObject() throws Exception {
        assertNull(invokeParseModelEntry(new JsonPrimitive("not an object")));
    }

    // ── safeGetInt (private static) ─────────────────────────────────────

    @Test
    void safeGetInt_exists() throws Exception {
        JsonObject obj = new JsonObject();
        obj.addProperty("count", 42);
        assertEquals(42, invokeSafeGetInt(obj, "count"));
    }

    @Test
    void safeGetInt_missing() throws Exception {
        assertEquals(0, invokeSafeGetInt(new JsonObject(), "count"));
    }

    @Test
    void safeGetInt_null() throws Exception {
        JsonObject obj = new JsonObject();
        obj.add("count", JsonNull.INSTANCE);
        assertEquals(0, invokeSafeGetInt(obj, "count"));
    }

    @Test
    void safeGetInt_zero() throws Exception {
        JsonObject obj = new JsonObject();
        obj.addProperty("count", 0);
        assertEquals(0, invokeSafeGetInt(obj, "count"));
    }

    // ── Reflection helpers ──────────────────────────────────────────────

    private static String invokeExtractTurnErrorMessage(JsonObject turn) throws Exception {
        Method m = CodexAppServerClient.class.getDeclaredMethod("extractTurnErrorMessage", JsonObject.class);
        m.setAccessible(true);
        return (String) m.invoke(null, turn);
    }

    private static Object invokeParseModelEntry(JsonElement el) throws Exception {
        Method m = CodexAppServerClient.class.getDeclaredMethod("parseModelEntry", JsonElement.class);
        m.setAccessible(true);
        return m.invoke(null, el);
    }

    private static int invokeSafeGetInt(JsonObject obj, String field) throws Exception {
        Method m = CodexAppServerClient.class.getDeclaredMethod("safeGetInt", JsonObject.class, String.class);
        m.setAccessible(true);
        return (int) m.invoke(null, obj, field);
    }

    private static boolean invokeIsMcpToolApprovalQuestion(String questionId) throws Exception {
        Method m = CodexAppServerClient.class.getDeclaredMethod("isMcpToolApprovalQuestion", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, questionId);
    }

    // ── createDefaultProfile (public static) ────────────────────────────

    @Nested
    class CreateDefaultProfile {

        private final AgentProfile profile = CodexAppServerClient.createDefaultProfile();

        @Test
        void returnsNonNullProfile() {
            assertNotNull(profile);
        }

        @Test
        void profileIdIsCodex() {
            assertEquals("codex", profile.getId());
        }

        @Test
        void displayNameIsSet() {
            assertEquals("Codex", profile.getDisplayName());
        }

        @Test
        void transportTypeIsCodexAppServer() {
            assertEquals(TransportType.CODEX_APP_SERVER, profile.getTransportType());
        }

        @Test
        void binaryNameIsSet() {
            assertEquals("codex", profile.getBinaryName());
        }

        @Test
        void mcpMethodIsNone() {
            assertEquals(McpInjectionMethod.NONE, profile.getMcpMethod());
        }

        @Test
        void allRequiredFieldsAreNonNull() {
            assertNotNull(profile.getId(), "id");
            assertNotNull(profile.getDisplayName(), "displayName");
            assertNotNull(profile.getTransportType(), "transportType");
            assertNotNull(profile.getBinaryName(), "binaryName");
            assertNotNull(profile.getMcpMethod(), "mcpMethod");
            assertNotNull(profile.getDescription(), "description");
            assertNotNull(profile.getAlternateNames(), "alternateNames");
            assertNotNull(profile.getInstallHint(), "installHint");
            assertNotNull(profile.getInstallUrl(), "installUrl");
            assertNotNull(profile.getAcpArgs(), "acpArgs");
            assertNotNull(profile.getPermissionInjectionMethod(), "permissionInjectionMethod");
        }
    }

    // ── isMcpToolApprovalQuestion (private static) ──────────────────────

    @Nested
    class IsMcpToolApprovalQuestion {

        @Test
        void matchingPrefix_returnsTrue() throws Exception {
            assertTrue(invokeIsMcpToolApprovalQuestion("mcp_tool_call_approval_abc123"));
        }

        @Test
        void nonMatchingString_returnsFalse() throws Exception {
            assertFalse(invokeIsMcpToolApprovalQuestion("some_other_question_id"));
        }

        @Test
        void emptyString_returnsFalse() throws Exception {
            assertFalse(invokeIsMcpToolApprovalQuestion(""));
        }
    }
}
