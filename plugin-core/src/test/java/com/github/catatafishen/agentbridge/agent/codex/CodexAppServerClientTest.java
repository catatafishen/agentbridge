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
import java.util.List;

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

    // ── classifyMessageType (package-private static) ────────────────────

    @Nested
    class ClassifyMessageType {

        @Test
        void response_hasIdAndResult_noMethod() {
            JsonObject msg = new JsonObject();
            msg.addProperty("id", 1);
            msg.add("result", new JsonObject());
            assertEquals(CodexAppServerClient.MessageType.RESPONSE,
                CodexAppServerClient.classifyMessageType(msg));
        }

        @Test
        void response_hasIdAndError_noMethod() {
            JsonObject msg = new JsonObject();
            msg.addProperty("id", 2);
            msg.add("error", new JsonObject());
            assertEquals(CodexAppServerClient.MessageType.RESPONSE,
                CodexAppServerClient.classifyMessageType(msg));
        }

        @Test
        void serverRequest_hasIdAndMethod() {
            JsonObject msg = new JsonObject();
            msg.addProperty("id", 3);
            msg.addProperty("method", "item/tool/call");
            assertEquals(CodexAppServerClient.MessageType.SERVER_REQUEST,
                CodexAppServerClient.classifyMessageType(msg));
        }

        @Test
        void notification_hasMethod_noId() {
            JsonObject msg = new JsonObject();
            msg.addProperty("method", "turn/updated");
            assertEquals(CodexAppServerClient.MessageType.NOTIFICATION,
                CodexAppServerClient.classifyMessageType(msg));
        }

        @Test
        void notification_hasMethod_nullId() {
            JsonObject msg = new JsonObject();
            msg.add("id", JsonNull.INSTANCE);
            msg.addProperty("method", "turn/updated");
            assertEquals(CodexAppServerClient.MessageType.NOTIFICATION,
                CodexAppServerClient.classifyMessageType(msg));
        }

        @Test
        void unknown_emptyObject() {
            assertEquals(CodexAppServerClient.MessageType.UNKNOWN,
                CodexAppServerClient.classifyMessageType(new JsonObject()));
        }
    }

    // ── buildServerCommandStatic (package-private static) ───────────────

    @Nested
    class BuildServerCommandStatic {

        @Test
        void positiveMcpPort_includesMcpConfig() {
            List<String> cmd = CodexAppServerClient.buildServerCommandStatic("/usr/bin/codex", 8080);
            assertTrue(cmd.contains("mcp_servers.agentbridge.url=http://localhost:8080/mcp"),
                "Expected MCP config entry");
        }

        @Test
        void zeroMcpPort_excludesMcpConfig() {
            List<String> cmd = CodexAppServerClient.buildServerCommandStatic("/usr/bin/codex", 0);
            assertTrue(cmd.stream().noneMatch(s -> s.contains("mcp_servers")),
                "Should not contain MCP config when port is 0");
        }

        @Test
        void alwaysContainsBinaryPathAndAppServer() {
            List<String> cmd = CodexAppServerClient.buildServerCommandStatic("/path/to/codex", 0);
            assertEquals("/path/to/codex", cmd.get(0));
            assertEquals("app-server", cmd.get(1));
        }

        @Test
        void alwaysDisablesShellAndUnifiedExec() {
            List<String> cmd = CodexAppServerClient.buildServerCommandStatic("/bin/codex", 0);
            assertTrue(cmd.contains("features.shell_tool=false"),
                "Should disable shell_tool");
            assertTrue(cmd.contains("features.unified_exec=false"),
                "Should disable unified_exec");
        }
    }

    // ── buildCommandArgsJson (package-private static) ───────────────────

    @Nested
    class BuildCommandArgsJson {

        @Test
        void simpleCommand_noQuotes() {
            assertEquals("{\"command\":\"ls -la\"}", CodexAppServerClient.buildCommandArgsJson("ls -la"));
        }

        @Test
        void commandWithQuotes_properlyEscaped() {
            assertEquals("{\"command\":\"echo \\\"hello\\\"\"}",
                CodexAppServerClient.buildCommandArgsJson("echo \"hello\""));
        }

        @Test
        void emptyCommand() {
            assertEquals("{\"command\":\"\"}", CodexAppServerClient.buildCommandArgsJson(""));
        }
    }

    // ── extractJsonRpcErrorMessage (package-private static) ─────────────

    @Nested
    class ExtractJsonRpcErrorMessage {

        @Test
        void hasMessageField_returnsIt() {
            JsonObject err = new JsonObject();
            err.addProperty("message", "rate limit exceeded");
            assertEquals("rate limit exceeded", CodexAppServerClient.extractJsonRpcErrorMessage(err));
        }

        @Test
        void noMessageField_returnsToString() {
            JsonObject err = new JsonObject();
            err.addProperty("code", -32600);
            assertEquals(err.toString(), CodexAppServerClient.extractJsonRpcErrorMessage(err));
        }
    }

    // ── isCodexAuthError (package-private static) ───────────────────────

    @Nested
    class IsCodexAuthError {

        @Test
        void nullInput_returnsFalse() {
            assertFalse(CodexAppServerClient.isCodexAuthError(null));
        }

        @Test
        void emptyInput_returnsFalse() {
            assertFalse(CodexAppServerClient.isCodexAuthError(""));
        }

        @Test
        void notAuthenticated_returnsTrue() {
            assertTrue(CodexAppServerClient.isCodexAuthError("Not authenticated with Codex"));
        }

        @Test
        void notAuthenticated_caseInsensitive() {
            assertTrue(CodexAppServerClient.isCodexAuthError("NOT AUTHENTICATED"));
        }

        @Test
        void unauthorized_returnsTrue() {
            assertTrue(CodexAppServerClient.isCodexAuthError("Unauthorized request"));
        }

        @Test
        void authenticationRequired_returnsTrue() {
            assertTrue(CodexAppServerClient.isCodexAuthError("Authentication required"));
        }

        @Test
        void invalidApiKey_returnsTrue() {
            assertTrue(CodexAppServerClient.isCodexAuthError("Invalid API key supplied"));
        }

        @Test
        void pleaseRunCodexLogin_returnsTrue() {
            assertTrue(CodexAppServerClient.isCodexAuthError("Please run codex login to continue"));
        }

        @Test
        void pleaseLogIn_returnsTrue() {
            assertTrue(CodexAppServerClient.isCodexAuthError("Please log in via the CLI"));
        }

        @Test
        void http401_returnsTrue() {
            assertTrue(CodexAppServerClient.isCodexAuthError("HTTP 401 returned"));
        }

        @Test
        void unrelatedError_returnsFalse() {
            assertFalse(CodexAppServerClient.isCodexAuthError("Tool 'apply_patch' failed: bad diff"));
        }

        @Test
        void rateLimit_returnsFalse() {
            assertFalse(CodexAppServerClient.isCodexAuthError("Rate limit exceeded"));
        }

        @Test
        void networkError_returnsFalse() {
            assertFalse(CodexAppServerClient.isCodexAuthError("Connection refused"));
        }
    }
}
