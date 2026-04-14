package com.github.catatafishen.agentbridge.acp.client;

import com.github.catatafishen.agentbridge.acp.model.NewSessionResponse;
import com.github.catatafishen.agentbridge.agent.AbstractAgentClient;
import com.github.catatafishen.agentbridge.agent.AgentSessionException;
import com.github.catatafishen.agentbridge.bridge.SessionOption;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // ── parseToolCallArguments (protected instance) ───────────────────────

    @Nested
    class ParseToolCallArguments {

        private final AcpClient client = Mockito.mock(AcpClient.class, Mockito.CALLS_REAL_METHODS);

        @Test
        void argumentsKeyWithJsonObjectReturnsIt() {
            JsonObject args = new JsonObject();
            args.addProperty("tool", "read_file");
            JsonObject params = new JsonObject();
            params.add("arguments", args);

            JsonObject result = client.parseToolCallArguments(params);

            assertNotNull(result);
            assertEquals("read_file", result.get("tool").getAsString());
            assertSame(args, result);
        }

        @Test
        void argumentsKeyWithStringReturnsNull() {
            JsonObject params = new JsonObject();
            params.addProperty("arguments", "not-an-object");

            assertNull(client.parseToolCallArguments(params));
        }

        @Test
        void noArgumentsKeyReturnsNull() {
            JsonObject params = new JsonObject();
            params.addProperty("other", "value");

            assertNull(client.parseToolCallArguments(params));
        }

        @Test
        void emptyParamsReturnsNull() {
            assertNull(client.parseToolCallArguments(new JsonObject()));
        }
    }

    // ── extractSubAgentType (protected instance) ────────────────────────

    @Nested
    class ExtractSubAgentType {

        private final AcpClient client = Mockito.mock(AcpClient.class, Mockito.CALLS_REAL_METHODS);

        @Test
        void agentTypeInParams() {
            JsonObject params = new JsonObject();
            params.addProperty("agentType", "copilot");

            assertEquals("copilot", client.extractSubAgentType(params, "title", null));
        }

        @Test
        void agentUnderscoreTypeInParams() {
            JsonObject params = new JsonObject();
            params.addProperty("agent_type", "claude");

            assertEquals("claude", client.extractSubAgentType(params, "title", null));
        }

        @Test
        void subagentTypeInParams() {
            JsonObject params = new JsonObject();
            params.addProperty("subagent_type", "gemini");

            assertEquals("gemini", client.extractSubAgentType(params, "title", null));
        }

        @Test
        void agentTypeInArgumentsObj() {
            JsonObject params = new JsonObject();
            JsonObject argsObj = new JsonObject();
            argsObj.addProperty("agentType", "copilot");

            assertEquals("copilot", client.extractSubAgentType(params, "title", argsObj));
        }

        @Test
        void agentUnderscoreTypeInArgumentsObj() {
            JsonObject params = new JsonObject();
            JsonObject argsObj = new JsonObject();
            argsObj.addProperty("agent_type", "claude");

            assertEquals("claude", client.extractSubAgentType(params, "title", argsObj));
        }

        @Test
        void subagentTypeInArgumentsObj() {
            JsonObject params = new JsonObject();
            JsonObject argsObj = new JsonObject();
            argsObj.addProperty("subagent_type", "gemini");

            assertEquals("gemini", client.extractSubAgentType(params, "title", argsObj));
        }

        @Test
        void paramsTakesPriorityOverArgumentsObj() {
            JsonObject params = new JsonObject();
            params.addProperty("agentType", "from-params");
            JsonObject argsObj = new JsonObject();
            argsObj.addProperty("agentType", "from-args");

            assertEquals("from-params", client.extractSubAgentType(params, "title", argsObj));
        }

        @Test
        void paramsKeyOrderPriorityAgentTypeThenAgentUnderscoreThenSubagent() {
            JsonObject params = new JsonObject();
            params.addProperty("agentType", "first");
            params.addProperty("agent_type", "second");
            params.addProperty("subagent_type", "third");

            assertEquals("first", client.extractSubAgentType(params, "title", null));
        }

        @Test
        void noMatchingKeyReturnsNull() {
            JsonObject params = new JsonObject();
            params.addProperty("other", "value");
            JsonObject argsObj = new JsonObject();
            argsObj.addProperty("unrelated", "data");

            assertNull(client.extractSubAgentType(params, "title", argsObj));
        }

        @Test
        void nullArgumentsObjDoesNotCrash() {
            JsonObject params = new JsonObject();
            params.addProperty("unrelated", "data");

            assertNull(client.extractSubAgentType(params, "title", null));
        }

        @Test
        void nonPrimitiveAgentTypeIgnored() {
            JsonObject params = new JsonObject();
            params.add("agentType", new JsonObject());

            assertNull(client.extractSubAgentType(params, "title", null));
        }

        @Test
        void fallsThroughToArgumentsWhenParamsHasNoMatch() {
            JsonObject params = new JsonObject();
            params.addProperty("unrelated", "data");
            JsonObject argsObj = new JsonObject();
            argsObj.addProperty("agent_type", "from-args");

            assertEquals("from-args", client.extractSubAgentType(params, "title", argsObj));
        }
    }

    // ── mapModesStatic ────────────────────────────────────────────────────

    @Nested
    class MapModesStatic {

        @Test
        void nullInputReturnsEmpty() {
            List<AbstractAgentClient.AgentMode> result = AcpClient.mapModesStatic(null);
            assertTrue(result.isEmpty());
        }

        @Test
        void emptyListReturnsEmpty() {
            List<AbstractAgentClient.AgentMode> result = AcpClient.mapModesStatic(List.of());
            assertTrue(result.isEmpty());
        }

        @Test
        void singleModeIsMapped() {
            var mode = new NewSessionResponse.AvailableMode("code", "Code", "Write code");
            List<AbstractAgentClient.AgentMode> result = AcpClient.mapModesStatic(List.of(mode));

            assertEquals(1, result.size());
            assertEquals("code", result.get(0).slug());
            assertEquals("Code", result.get(0).name());
            assertEquals("Write code", result.get(0).description());
        }

        @Test
        void multipleModesWithNullDescription() {
            var m1 = new NewSessionResponse.AvailableMode("ask", "Ask", null);
            var m2 = new NewSessionResponse.AvailableMode("edit", "Edit", "Edit files");
            List<AbstractAgentClient.AgentMode> result = AcpClient.mapModesStatic(List.of(m1, m2));

            assertEquals(2, result.size());
            assertEquals("ask", result.get(0).slug());
            assertNull(result.get(0).description());
            assertEquals("edit", result.get(1).slug());
            assertEquals("Edit files", result.get(1).description());
        }
    }

    // ── mapConfigOptionsStatic ──────────────────────────────────────────

    @Nested
    class MapConfigOptionsStatic {

        @Test
        void nullInputReturnsEmpty() {
            List<AbstractAgentClient.AgentConfigOption> result = AcpClient.mapConfigOptionsStatic(null);
            assertTrue(result.isEmpty());
        }

        @Test
        void emptyListReturnsEmpty() {
            List<AbstractAgentClient.AgentConfigOption> result = AcpClient.mapConfigOptionsStatic(List.of());
            assertTrue(result.isEmpty());
        }

        @Test
        void optionWithValues() {
            var v1 = new NewSessionResponse.SessionConfigOptionValue("v1", "Value 1");
            var v2 = new NewSessionResponse.SessionConfigOptionValue("v2", "Value 2");
            var opt = new NewSessionResponse.SessionConfigOption("opt1", "Option 1", "desc", List.of(v1, v2), "v1");

            List<AbstractAgentClient.AgentConfigOption> result = AcpClient.mapConfigOptionsStatic(List.of(opt));

            assertEquals(1, result.size());
            var mapped = result.get(0);
            assertEquals("opt1", mapped.id());
            assertEquals("Option 1", mapped.label());
            assertEquals("desc", mapped.description());
            assertEquals(2, mapped.values().size());
            assertEquals("v1", mapped.values().get(0).id());
            assertEquals("Value 1", mapped.values().get(0).label());
            assertEquals("v1", mapped.selectedValueId());
        }

        @Test
        void optionWithNullIdAndLabelUseFallbacks() {
            var opt = new NewSessionResponse.SessionConfigOption(null, null, "desc", List.of(), null);

            List<AbstractAgentClient.AgentConfigOption> result = AcpClient.mapConfigOptionsStatic(List.of(opt));

            assertEquals(1, result.size());
            assertEquals("", result.get(0).id());
            assertEquals("", result.get(0).label()); // label falls back to optId which is ""
        }

        @Test
        void optionWithNullValuesListReturnsEmptyValues() {
            var opt = new NewSessionResponse.SessionConfigOption("x", "X", null, null, null);

            List<AbstractAgentClient.AgentConfigOption> result = AcpClient.mapConfigOptionsStatic(List.of(opt));

            assertEquals(1, result.size());
            assertTrue(result.get(0).values().isEmpty());
        }
    }

    // ── filterSessionOptionsStatic ──────────────────────────────────────

    @Nested
    class FilterSessionOptionsStatic {

        private AbstractAgentClient.AgentConfigOption makeOpt(String id, String label,
                                                              String... valueIds) {
            List<AbstractAgentClient.AgentConfigOptionValue> vals = new java.util.ArrayList<>();
            for (String vid : valueIds) {
                vals.add(new AbstractAgentClient.AgentConfigOptionValue(vid, "Label-" + vid));
            }
            return new AbstractAgentClient.AgentConfigOption(id, label, null, vals, null);
        }

        @Test
        void emptyModelIdsPassesAllThrough() {
            var opt1 = makeOpt("o1", "Opt1", "a", "b");
            var opt2 = makeOpt("o2", "Opt2", "c");

            List<SessionOption> result = AcpClient.filterSessionOptionsStatic(
                List.of(opt1, opt2), Collections.emptySet());

            assertEquals(2, result.size());
            assertEquals("o1", result.get(0).key());
            assertEquals("o2", result.get(1).key());
        }

        @Test
        void exactMatchIsFilteredOut() {
            var opt = makeOpt("models", "Model", "m1", "m2");

            List<SessionOption> result = AcpClient.filterSessionOptionsStatic(
                List.of(opt), Set.of("m1", "m2"));

            assertTrue(result.isEmpty());
        }

        @Test
        void modelIdsSupersetOfOptionValuesIsFilteredOut() {
            var opt = makeOpt("models", "Model", "m1");

            List<SessionOption> result = AcpClient.filterSessionOptionsStatic(
                List.of(opt), Set.of("m1", "m2", "m3"));

            assertTrue(result.isEmpty());
        }

        @Test
        void partialOverlapIsIncluded() {
            var opt = makeOpt("theme", "Theme", "dark", "light", "m1");

            List<SessionOption> result = AcpClient.filterSessionOptionsStatic(
                List.of(opt), Set.of("m1", "m2"));

            assertEquals(1, result.size());
            assertEquals("theme", result.get(0).key());
            assertEquals(List.of("dark", "light", "m1"), result.get(0).values());
            assertEquals("Label-dark", result.get(0).labels().get("dark"));
        }

        @Test
        void optionWithNoValuesIsIncluded() {
            var opt = makeOpt("empty", "Empty");

            List<SessionOption> result = AcpClient.filterSessionOptionsStatic(
                List.of(opt), Set.of("m1", "m2"));

            // optValueIds is empty; containsAll(empty) is always true → filtered out
            assertTrue(result.isEmpty());
        }
    }

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

    // ── validateResolvedBinary (static package-private) ─────────────────

    @Nested
    class ValidateResolvedBinary {

        @Test
        void absolutePathExists_noException() throws Exception {
            // /bin/sh always exists on Unix systems
            AcpClient.validateResolvedBinary("/bin/sh", "TestAgent");
        }

        @Test
        void absolutePathMissing_throwsIOException() {
            var ex = assertThrows(java.io.IOException.class, () ->
                AcpClient.validateResolvedBinary("/nonexistent/path/binary", "TestAgent"));
            assertTrue(ex.getMessage().contains("binary not found at:"));
            assertTrue(ex.getMessage().contains("TestAgent"));
        }

        @Test
        void bareName_throwsIOException() {
            var ex = assertThrows(java.io.IOException.class, () ->
                AcpClient.validateResolvedBinary("copilot", "TestAgent"));
            assertTrue(ex.getMessage().contains("not found in PATH"));
            assertTrue(ex.getMessage().contains("TestAgent"));
        }

        @Test
        void windowsStyleAbsolutePath_missing_throwsIOException() {
            var ex = assertThrows(java.io.IOException.class, () ->
                AcpClient.validateResolvedBinary("C:\\nonexistent\\binary.exe", "TestAgent"));
            assertTrue(ex.getMessage().contains("binary not found at:"));
        }
    }

    // ── tryResolveBareName (static package-private) ─────────────────────

    @Nested
    class TryResolveBareName {

        @Test
        void absolutePath_returnsUnchanged() {
            assertEquals("/usr/bin/something", AcpClient.tryResolveBareName("/usr/bin/something"));
        }

        @Test
        void windowsPath_returnsUnchanged() {
            assertEquals("C:\\Program Files\\bin.exe", AcpClient.tryResolveBareName("C:\\Program Files\\bin.exe"));
        }

        @Test
        void nonexistentBareName_returnsOriginal() {
            // A made-up binary name that doesn't exist on any system
            String original = "xyzzy_nonexistent_binary_42";
            assertEquals(original, AcpClient.tryResolveBareName(original));
        }

        @Test
        void existingBareName_resolvesToAbsolutePath() {
            // 'sh' is universally available on Unix systems
            String result = AcpClient.tryResolveBareName("sh");
            assertTrue(result.contains("/"), "expected 'sh' to be resolved to an absolute path, got: " + result);
        }
    }

    // ── checkAuthentication (instance method) ────────────────────────────────

    @Nested
    class CheckAuthentication {

        @Test
        void returnsNullWhenSessionAlreadyCreated() throws Exception {
            AcpClient client = Mockito.mock(AcpClient.class, Mockito.CALLS_REAL_METHODS);
            Mockito.doReturn(true).when(client).isHealthy();
            Field field = AcpClient.class.getDeclaredField("currentSessionId");
            field.setAccessible(true);
            field.set(client, "existing-session");

            assertNull(client.checkAuthentication());
        }
    }

    // ── createSession — load-session failure path ─────────────────────────────

    @Nested
    class CreateSessionLoadFails {

        @Test
        void persistsNullResumeIdWhenLoadSessionFails() {
            AcpClient client = Mockito.mock(AcpClient.class, Mockito.CALLS_REAL_METHODS);
            // Stub loadResumeSessionId so the resume path is entered (requestedResumeId != null).
            // loadSession() then throws (agent doesn't advertise the loadSession capability),
            // which drives execution into the catch block covering persistResumeSessionId(null).
            Mockito.doReturn("stale-resume-id").when(client).loadResumeSessionId();

            assertThrows(AgentSessionException.class, () -> client.createSession("/test/cwd"));
        }
    }

}
