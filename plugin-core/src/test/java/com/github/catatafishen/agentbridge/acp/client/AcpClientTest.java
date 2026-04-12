package com.github.catatafishen.agentbridge.acp.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.mockito.Mockito;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class AcpClientTest {

    // ── truncateForLog (private static) ─────────────────────────────────

    @Nested
    class TruncateForLog {

        @Test
        void nullReturnsNull() throws Exception {
            assertNull(invokeTruncateForLog(null));
        }

        @Test
        void shortStringUnchanged() throws Exception {
            assertEquals("hello", invokeTruncateForLog("hello"));
        }

        @Test
        void exactlyAtLimit() throws Exception {
            String atLimit = "x".repeat(2000);
            assertEquals(atLimit, invokeTruncateForLog(atLimit));
        }

        @Test
        void overLimitGetsTruncated() throws Exception {
            String over = "x".repeat(2500);
            String result = invokeTruncateForLog(over);
            assertTrue(result.startsWith("x".repeat(2000)));
            assertTrue(result.contains("... [truncated 500 chars]"));
        }

        private String invokeTruncateForLog(String s) throws Exception {
            Method m = AcpClient.class.getDeclaredMethod("truncateForLog", String.class);
            m.setAccessible(true);
            return (String) m.invoke(null, s);
        }
    }

    // ── extractRootCauseMessage (private static) ────────────────────────

    @Nested
    class ExtractRootCauseMessage {

        @Test
        void simpleMessage() throws Exception {
            assertEquals("connection refused",
                invokeExtractRootCauseMessage(new RuntimeException("connection refused")));
        }

        @Test
        void skipsPromptFailedPrefix() throws Exception {
            Exception root = new RuntimeException("real error");
            Exception wrapper = new RuntimeException("Prompt failed for copilot", root);
            assertEquals("real error", invokeExtractRootCauseMessage(wrapper));
        }

        @Test
        void skipsPromptInterruptedPrefix() throws Exception {
            Exception root = new RuntimeException("cancelled");
            Exception wrapper = new RuntimeException("Prompt interrupted for claude", root);
            assertEquals("cancelled", invokeExtractRootCauseMessage(wrapper));
        }

        @Test
        void nullMessage() throws Exception {
            assertNull(invokeExtractRootCauseMessage(new RuntimeException((String) null)));
        }

        @Test
        void blankMessage() throws Exception {
            assertNull(invokeExtractRootCauseMessage(new RuntimeException("   ")));
        }

        @Test
        void deepChain() throws Exception {
            Exception deepest = new RuntimeException("root cause");
            Exception mid = new RuntimeException("mid", deepest);
            Exception top = new RuntimeException("Prompt failed for agent", mid);
            assertEquals("root cause", invokeExtractRootCauseMessage(top));
        }

        private String invokeExtractRootCauseMessage(Throwable t) throws Exception {
            Method m = AcpClient.class.getDeclaredMethod("extractRootCauseMessage", Throwable.class);
            m.setAccessible(true);
            return (String) m.invoke(null, t);
        }
    }

    // ── getStringOrEmpty (private static) ───────────────────────────────

    @Nested
    class GetStringOrEmpty {

        @Test
        void existingKey() throws Exception {
            JsonObject obj = new JsonObject();
            obj.addProperty("name", "test");
            assertEquals("test", invokeGetStringOrEmpty(obj, "name"));
        }

        @Test
        void missingKey() throws Exception {
            assertEquals("", invokeGetStringOrEmpty(new JsonObject(), "name"));
        }

        @Test
        void nullValue() throws Exception {
            JsonObject obj = new JsonObject();
            obj.add("name", JsonNull.INSTANCE);
            assertEquals("", invokeGetStringOrEmpty(obj, "name"));
        }

        @Test
        void nonPrimitive() throws Exception {
            JsonObject obj = new JsonObject();
            obj.add("name", new JsonObject());
            assertEquals("", invokeGetStringOrEmpty(obj, "name"));
        }

        private String invokeGetStringOrEmpty(JsonObject obj, String key) throws Exception {
            Method m = AcpClient.class.getDeclaredMethod("getStringOrEmpty", JsonObject.class, String.class);
            m.setAccessible(true);
            return (String) m.invoke(null, obj, key);
        }
    }

    // ── isAllowedBuiltInTool (package-private static) ───────────────────

    @Nested
    class IsAllowedBuiltInTool {

        @Test
        void webFetchAllowed() {
            assertTrue(AcpClient.isAllowedBuiltInTool("web_fetch"));
        }

        @Test
        void webSearchAllowed() {
            assertTrue(AcpClient.isAllowedBuiltInTool("web_search"));
        }

        @Test
        void taskCompleteAllowed() {
            assertTrue(AcpClient.isAllowedBuiltInTool("task_complete"));
        }

        @Test
        void caseInsensitive() {
            assertTrue(AcpClient.isAllowedBuiltInTool("Web_Fetch"));
        }

        @Test
        void bashNotAllowed() {
            assertFalse(AcpClient.isAllowedBuiltInTool("bash"));
        }

        @Test
        void editNotAllowed() {
            assertFalse(AcpClient.isAllowedBuiltInTool("edit"));
        }
    }

    // ── shouldAutoDenyBuiltInTool (package-private static) ──────────────

    @Nested
    class ShouldAutoDenyBuiltInTool {

        @Test
        void mcpResourceToolNotDenied() {
            assertFalse(AcpClient.shouldAutoDenyBuiltInTool("read_mcp_resource"));
            assertFalse(AcpClient.shouldAutoDenyBuiltInTool("list_mcp_resources"));
        }

        @Test
        void agentbridgeDashNotDenied() {
            assertFalse(AcpClient.shouldAutoDenyBuiltInTool("agentbridge-read_file"));
        }

        @Test
        void agentbridgeUnderscoreNotDenied() {
            assertFalse(AcpClient.shouldAutoDenyBuiltInTool("agentbridge_read_file"));
        }

        @Test
        void agentbridgeToolProtocolNotDenied() {
            assertFalse(AcpClient.shouldAutoDenyBuiltInTool("Tool: agentbridge/read_file"));
        }

        @Test
        void agentbridgeRunningProtocolNotDenied() {
            assertFalse(AcpClient.shouldAutoDenyBuiltInTool("Running: @agentbridge/read_file"));
        }

        @Test
        void agentbridgeAtPrefixNotDenied() {
            assertFalse(AcpClient.shouldAutoDenyBuiltInTool("@agentbridge/read_file"));
        }

        @Test
        void allowedBuiltInNotDenied() {
            assertFalse(AcpClient.shouldAutoDenyBuiltInTool("web_fetch"));
        }

        @Test
        void bashIsDenied() {
            assertTrue(AcpClient.shouldAutoDenyBuiltInTool("bash"));
        }

        @Test
        void editIsDenied() {
            assertTrue(AcpClient.shouldAutoDenyBuiltInTool("edit"));
        }

        @Test
        void viewIsDenied() {
            assertTrue(AcpClient.shouldAutoDenyBuiltInTool("view"));
        }

        @Test
        void grepIsDenied() {
            assertTrue(AcpClient.shouldAutoDenyBuiltInTool("grep"));
        }

        @Test
        void toolWithSlashNotDenied() {
            assertFalse(AcpClient.shouldAutoDenyBuiltInTool("some/tool"));
        }

        @Test
        void toolWithAtNotDenied() {
            assertFalse(AcpClient.shouldAutoDenyBuiltInTool("@some_tool"));
        }
    }

    // ── findOptionByKind (private static) ───────────────────────────────

    @Nested
    class FindOptionByKind {

        @Test
        void nullParams() throws Exception {
            assertNull(invokeFindOptionByKind(null, "allow"));
        }

        @Test
        void noOptionsKey() throws Exception {
            assertNull(invokeFindOptionByKind(new JsonObject(), "allow"));
        }

        @Test
        void optionsNotArray() throws Exception {
            JsonObject params = new JsonObject();
            params.addProperty("options", "not an array");
            assertNull(invokeFindOptionByKind(params, "allow"));
        }

        @Test
        void matchesKind() throws Exception {
            JsonObject params = buildOptionsParams("allow", "deny_once");
            JsonObject result = invokeFindOptionByKind(params, "deny_once");
            assertNotNull(result);
            assertEquals("deny_once", result.get("kind").getAsString());
        }

        @Test
        void noMatch() throws Exception {
            JsonObject params = buildOptionsParams("allow");
            assertNull(invokeFindOptionByKind(params, "deny_once"));
        }

        @Test
        void emptyArray() throws Exception {
            JsonObject params = new JsonObject();
            params.add("options", new JsonArray());
            assertNull(invokeFindOptionByKind(params, "allow"));
        }

        private JsonObject invokeFindOptionByKind(JsonObject params, String kind) throws Exception {
            Method m = AcpClient.class.getDeclaredMethod("findOptionByKind", JsonObject.class, String.class);
            m.setAccessible(true);
            return (JsonObject) m.invoke(null, params, kind);
        }
    }

    // ── findFirstOption (private static) ────────────────────────────────

    @Nested
    class FindFirstOption {

        @Test
        void nullParams() throws Exception {
            assertNull(invokeFindFirstOption(null));
        }

        @Test
        void emptyArray() throws Exception {
            JsonObject params = new JsonObject();
            params.add("options", new JsonArray());
            assertNull(invokeFindFirstOption(params));
        }

        @Test
        void returnsFirstElement() throws Exception {
            JsonObject params = buildOptionsParams("allow", "deny_once");
            JsonObject result = invokeFindFirstOption(params);
            assertNotNull(result);
            assertEquals("allow", result.get("kind").getAsString());
        }

        private JsonObject invokeFindFirstOption(JsonObject params) throws Exception {
            Method m = AcpClient.class.getDeclaredMethod("findFirstOption", JsonObject.class);
            m.setAccessible(true);
            return (JsonObject) m.invoke(null, params);
        }
    }

    // ── findDenyOption (private static) ─────────────────────────────────

    @Nested
    class FindDenyOption {

        @Test
        void findsDenyOnce() throws Exception {
            JsonObject params = buildOptionsParams("allow", "deny_once");
            JsonObject result = invokeFindDenyOption(params);
            assertNotNull(result);
            assertEquals("deny_once", result.get("kind").getAsString());
        }

        @Test
        void findsRejectOnce() throws Exception {
            JsonObject params = buildOptionsParams("allow", "reject_once");
            JsonObject result = invokeFindDenyOption(params);
            assertNotNull(result);
            assertEquals("reject_once", result.get("kind").getAsString());
        }

        @Test
        void prefersDenyOverReject() throws Exception {
            JsonObject params = buildOptionsParams("deny_once", "reject_once");
            JsonObject result = invokeFindDenyOption(params);
            assertNotNull(result);
            assertEquals("deny_once", result.get("kind").getAsString());
        }

        @Test
        void noDenyOption() throws Exception {
            JsonObject params = buildOptionsParams("allow");
            assertNull(invokeFindDenyOption(params));
        }

        private JsonObject invokeFindDenyOption(JsonObject params) throws Exception {
            Method m = AcpClient.class.getDeclaredMethod("findDenyOption", JsonObject.class);
            m.setAccessible(true);
            return (JsonObject) m.invoke(null, params);
        }
    }

    // ── buildPermissionOutcome (protected instance) ────────────────────

    @Nested
    class BuildPermissionOutcome {

        private final AcpClient client = Mockito.mock(AcpClient.class, Mockito.CALLS_REAL_METHODS);

        @Test
        void normalOptionIdWithChosenOption() {
            JsonObject chosenOption = new JsonObject();
            chosenOption.addProperty("kind", "allow_once");
            chosenOption.addProperty("optionId", "opt-1");

            JsonObject result = client.buildPermissionOutcome("opt-1", chosenOption);

            assertEquals("selected", result.get("outcome").getAsString());
            assertEquals("opt-1", result.get("optionId").getAsString());
        }

        @Test
        void nullChosenOptionStillReturnsOutcome() {
            JsonObject result = client.buildPermissionOutcome("deny_once", null);

            assertEquals("selected", result.get("outcome").getAsString());
            assertEquals("deny_once", result.get("optionId").getAsString());
        }

        @Test
        void emptyOptionId() {
            JsonObject result = client.buildPermissionOutcome("", null);

            assertEquals("selected", result.get("outcome").getAsString());
            assertEquals("", result.get("optionId").getAsString());
        }
    }

    // ── normalizeSessionUpdateParams (protected instance) ───────────────

    @Nested
    class NormalizeSessionUpdateParams {

        private final AcpClient client = Mockito.mock(AcpClient.class, Mockito.CALLS_REAL_METHODS);

        @Test
        void withUpdateWrapperUnwrapsInnerObject() {
            JsonObject inner = new JsonObject();
            inner.addProperty("type", "content");
            inner.addProperty("text", "hello");
            JsonObject params = new JsonObject();
            params.add("update", inner);

            JsonObject result = client.normalizeSessionUpdateParams(params);

            assertEquals("content", result.get("type").getAsString());
            assertEquals("hello", result.get("text").getAsString());
            assertFalse(result.has("update"));
        }

        @Test
        void withoutUpdateKeyReturnsAsIs() {
            JsonObject params = new JsonObject();
            params.addProperty("type", "content");
            params.addProperty("text", "hello");

            JsonObject result = client.normalizeSessionUpdateParams(params);

            assertSame(params, result);
        }

        @Test
        void updateKeyAsNonObjectReturnsAsIs() {
            JsonObject params = new JsonObject();
            params.addProperty("update", "not-an-object");

            JsonObject result = client.normalizeSessionUpdateParams(params);

            assertSame(params, result);
        }
    }

    // ── getStartupStepFromException (private instance) ──────────────────

    @Nested
    class GetStartupStepFromException {

        private final AcpClient client = Mockito.mock(AcpClient.class, Mockito.CALLS_REAL_METHODS);

        @Test
        void launchMethod() throws Exception {
            assertEquals("process launch", invokeGetStartupStep(exceptionFromMethod("launchProcess")));
        }

        @Test
        void startMethod() throws Exception {
            assertEquals("transport start", invokeGetStartupStep(exceptionFromMethod("startTransport")));
        }

        @Test
        void initializeMethod() throws Exception {
            assertEquals("initialization", invokeGetStartupStep(exceptionFromMethod("initializeConnection")));
        }

        @Test
        void authenticateMethod() throws Exception {
            assertEquals("authentication", invokeGetStartupStep(exceptionFromMethod("authenticateUser")));
        }

        @Test
        void fetchModelsMethod() throws Exception {
            assertEquals("model fetch", invokeGetStartupStep(exceptionFromMethod("fetchModelsFromServer")));
        }

        @Test
        void emptyStackTrace() throws Exception {
            Exception e = new RuntimeException("test");
            e.setStackTrace(new StackTraceElement[0]);
            assertEquals("unknown step", invokeGetStartupStep(e));
        }

        private Exception exceptionFromMethod(String methodName) {
            Exception e = new RuntimeException("test");
            e.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("TestClass", methodName, "Test.java", 1)
            });
            return e;
        }

        private String invokeGetStartupStep(Exception ex) throws Exception {
            Method m = AcpClient.class.getDeclaredMethod("getStartupStepFromException", Exception.class);
            m.setAccessible(true);
            return (String) m.invoke(client, ex);
        }
    }

    // ── isMcpResourceTool (private static) ──────────────────────────────

    @Nested
    class IsMcpResourceTool {

        @Test
        void readMcpResource() throws Exception {
            assertTrue(invokeIsMcpResourceTool("read_mcp_resource"));
        }

        @Test
        void listMcpResources() throws Exception {
            assertTrue(invokeIsMcpResourceTool("list_mcp_resources"));
        }

        @Test
        void caseInsensitive() throws Exception {
            assertTrue(invokeIsMcpResourceTool("READ_MCP_RESOURCE"));
        }

        @Test
        void randomToolNotMcpResource() throws Exception {
            assertFalse(invokeIsMcpResourceTool("bash"));
        }

        private boolean invokeIsMcpResourceTool(String toolId) throws Exception {
            Method m = AcpClient.class.getDeclaredMethod("isMcpResourceTool", String.class);
            m.setAccessible(true);
            return (boolean) m.invoke(null, toolId);
        }
    }

    // ── extractSubAgentType (protected instance) ────────────────────────

    // extractSubAgentType is a protected instance method on abstract AcpClient.
    // Tested indirectly through concrete client tests.

    // ── Helpers ──────────────────────────────────────────────────────────

    private static JsonObject buildOptionsParams(String... kinds) {
        JsonArray options = new JsonArray();
        for (String kind : kinds) {
            JsonObject opt = new JsonObject();
            opt.addProperty("kind", kind);
            opt.addProperty("id", kind + "-id");
            options.add(opt);
        }
        JsonObject params = new JsonObject();
        params.add("options", options);
        return params;
    }

}
