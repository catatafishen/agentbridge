package com.github.catatafishen.agentbridge.acp.client;

import com.github.catatafishen.agentbridge.acp.model.SessionUpdate;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AcpMessageParser} verifying correct parsing of all ACP
 * {@code session/update} types defined in the spec.
 *
 * @see <a href="https://agentclientprotocol.com/protocol/prompt-turn">ACP Prompt Turn</a>
 * @see <a href="https://agentclientprotocol.com/protocol/session-setup">ACP Session Setup (session/load replay)</a>
 */
class AcpMessageParserTest {

    private final AcpMessageParser parser = new AcpMessageParser(
        new AcpMessageParser.Delegate() {
            @Override
            public String resolveToolId(String protocolTitle) {
                return protocolTitle;
            }

            @Override
            public @Nullable JsonObject parseToolCallArguments(@NotNull JsonObject params) {
                return params.has("arguments") && params.get("arguments").isJsonObject()
                    ? params.getAsJsonObject("arguments") : null;
            }

            @Override
            public @Nullable String extractSubAgentType(@NotNull JsonObject params, @NotNull String resolvedTitle,
                                                        @Nullable JsonObject argumentsObj) {
                return null;
            }
        },
        () -> "test-agent"
    );

    // ── agent_message_chunk — per spec: streamed text response ───────────────

    @Test
    void parsesAgentMessageChunk() {
        JsonObject params = updateParams("agent_message_chunk");
        JsonObject content = new JsonObject();
        content.addProperty("type", "text");
        content.addProperty("text", "Hello, world!");
        params.add("content", content);

        SessionUpdate update = parser.parse(params);

        assertInstanceOf(SessionUpdate.AgentMessageChunk.class, update);
        assertEquals("Hello, world!", ((SessionUpdate.AgentMessageChunk) update).text());
    }

    // ── agent_thought_chunk — per spec: reasoning/thinking output ────────────

    @Test
    void parsesAgentThoughtChunk() {
        JsonObject params = updateParams("agent_thought_chunk");
        JsonObject content = new JsonObject();
        content.addProperty("type", "text");
        content.addProperty("text", "Let me think...");
        params.add("content", content);

        SessionUpdate update = parser.parse(params);

        assertInstanceOf(SessionUpdate.AgentThoughtChunk.class, update);
        assertEquals("Let me think...", ((SessionUpdate.AgentThoughtChunk) update).text());
    }

    // ── user_message_chunk — per spec: replayed user messages during session/load

    @Test
    void parsesUserMessageChunk() {
        JsonObject params = updateParams("user_message_chunk");
        JsonObject content = new JsonObject();
        content.addProperty("type", "text");
        content.addProperty("text", "What's the capital of France?");
        params.add("content", content);

        SessionUpdate update = parser.parse(params);

        assertInstanceOf(SessionUpdate.UserMessageChunk.class, update,
            "Per spec: user_message_chunk is sent during session/load replay");
        assertEquals("What's the capital of France?", ((SessionUpdate.UserMessageChunk) update).text());
    }

    // ── tool_call — per spec: {toolCallId, title, kind, status} ─────────────

    @Test
    void parsesToolCall() {
        JsonObject params = updateParams("tool_call");
        params.addProperty("toolCallId", "call_001");
        params.addProperty("title", "Reading configuration file");
        params.addProperty("kind", "read");
        params.addProperty("status", "pending");

        SessionUpdate update = parser.parse(params);

        assertInstanceOf(SessionUpdate.ToolCall.class, update);
        SessionUpdate.ToolCall tc = (SessionUpdate.ToolCall) update;
        assertEquals("call_001", tc.toolCallId());
        assertEquals("Reading configuration file", tc.title());
        assertEquals(SessionUpdate.ToolKind.READ, tc.kind());
    }

    @Test
    void parsesToolCallWithAllKinds() {
        // Per spec: read, edit, delete, move, search, execute, think, fetch, other
        String[] kinds = {"read", "edit", "delete", "move", "search", "execute", "think", "fetch", "other"};
        SessionUpdate.ToolKind[] expected = {
            SessionUpdate.ToolKind.READ, SessionUpdate.ToolKind.EDIT, SessionUpdate.ToolKind.DELETE,
            SessionUpdate.ToolKind.MOVE, SessionUpdate.ToolKind.SEARCH, SessionUpdate.ToolKind.EXECUTE,
            SessionUpdate.ToolKind.THINK, SessionUpdate.ToolKind.FETCH, SessionUpdate.ToolKind.OTHER
        };

        for (int i = 0; i < kinds.length; i++) {
            JsonObject params = updateParams("tool_call");
            params.addProperty("toolCallId", "call_" + i);
            params.addProperty("title", "test");
            params.addProperty("kind", kinds[i]);

            SessionUpdate update = parser.parse(params);
            assertInstanceOf(SessionUpdate.ToolCall.class, update);
            assertEquals(expected[i], ((SessionUpdate.ToolCall) update).kind(),
                "Kind '" + kinds[i] + "' not mapped correctly");
        }
    }

    // ── tool_call_update — per spec: {toolCallId, status, content?} ────────

    @Test
    void parsesToolCallUpdate() {
        JsonObject params = updateParams("tool_call_update");
        params.addProperty("toolCallId", "call_001");
        params.addProperty("status", "completed");

        SessionUpdate update = parser.parse(params);

        assertInstanceOf(SessionUpdate.ToolCallUpdate.class, update);
        SessionUpdate.ToolCallUpdate tcu = (SessionUpdate.ToolCallUpdate) update;
        assertEquals("call_001", tcu.toolCallId());
        assertEquals(SessionUpdate.ToolCallStatus.COMPLETED, tcu.status());
    }

    @Test
    void parsesAllToolCallStatuses() {
        // Per spec: pending, in_progress, completed, failed
        String[] statuses = {"pending", "in_progress", "completed", "failed"};
        for (String status : statuses) {
            JsonObject params = updateParams("tool_call_update");
            params.addProperty("toolCallId", "call_test");
            params.addProperty("status", status);

            SessionUpdate update = parser.parse(params);
            assertInstanceOf(SessionUpdate.ToolCallUpdate.class, update,
                "Status '" + status + "' should be parseable");
        }
    }

    // ── plan — per spec: {entries[]} ────────────────────────────────────────

    @Test
    void parsesPlan() {
        JsonObject params = updateParams("plan");
        com.google.gson.JsonArray entries = new com.google.gson.JsonArray();
        JsonObject entry = new JsonObject();
        entry.addProperty("content", "Check for syntax errors");
        entry.addProperty("priority", "high");
        entry.addProperty("status", "pending");
        entries.add(entry);
        params.add("entries", entries);

        SessionUpdate update = parser.parse(params);

        assertInstanceOf(SessionUpdate.Plan.class, update);
    }

    // ── turn_usage — per spec: {inputTokens, outputTokens} ──────────────────

    @Test
    void parsesTurnUsage() {
        JsonObject params = updateParams("turn_usage");
        params.addProperty("inputTokens", 150);
        params.addProperty("outputTokens", 200);

        SessionUpdate update = parser.parse(params);

        assertInstanceOf(SessionUpdate.TurnUsage.class, update);
        SessionUpdate.TurnUsage usage = (SessionUpdate.TurnUsage) update;
        assertEquals(150, usage.inputTokens());
        assertEquals(200, usage.outputTokens());
    }

    // ── banner — per spec: {message, level} ────────────────────────────────

    @Test
    void parsesBanner() {
        JsonObject params = updateParams("banner");
        params.addProperty("message", "Rate limit reached");
        params.addProperty("level", "warning");

        SessionUpdate update = parser.parse(params);

        assertInstanceOf(SessionUpdate.Banner.class, update);
        assertEquals("Rate limit reached", ((SessionUpdate.Banner) update).message());
    }

    // ── Unknown types — per spec: should return null without crashing ───────

    @Test
    void returnsNullForUnknownUpdateType() {
        JsonObject params = updateParams("future_update_type");

        SessionUpdate update = parser.parse(params);

        assertNull(update, "Unknown update types should return null");
    }

    @Test
    void returnsNullForMissingSessionUpdateField() {
        JsonObject params = new JsonObject();
        // No sessionUpdate field

        SessionUpdate update = parser.parse(params);

        assertNull(update, "Missing sessionUpdate field should return null");
    }

    // ── usage_update — OpenCode usage tracking ───────────────────────────

    @Test
    void parsesUsageUpdate_withUsedAndCost() {
        JsonObject params = updateParams("usage_update");
        params.addProperty("used", 5000);
        JsonObject cost = new JsonObject();
        cost.addProperty("amount", 0.025);
        cost.addProperty("currency", "USD");
        params.add("cost", cost);

        SessionUpdate update = parser.parse(params);

        assertInstanceOf(SessionUpdate.TurnUsage.class, update);
        SessionUpdate.TurnUsage usage = (SessionUpdate.TurnUsage) update;
        assertEquals(5000, usage.inputTokens(), "used → inputTokens");
        assertEquals(0, usage.outputTokens(), "outputTokens always 0 for usage_update");
        assertEquals(0.025, usage.costUsd(), 0.001);
    }

    @Test
    void parsesUsageUpdate_withUsedOnly() {
        JsonObject params = updateParams("usage_update");
        params.addProperty("used", 3000);

        SessionUpdate update = parser.parse(params);

        assertInstanceOf(SessionUpdate.TurnUsage.class, update);
        SessionUpdate.TurnUsage usage = (SessionUpdate.TurnUsage) update;
        assertEquals(3000, usage.inputTokens());
        assertNull(usage.costUsd(), "No cost object → null costUsd");
    }

    @Test
    void parsesUsageUpdate_missingUsedDefaultsToZero() {
        JsonObject params = updateParams("usage_update");

        SessionUpdate update = parser.parse(params);

        assertInstanceOf(SessionUpdate.TurnUsage.class, update);
        assertEquals(0, ((SessionUpdate.TurnUsage) update).inputTokens());
    }

    @Test
    void parsesUsageUpdate_costNotAnObjectIgnored() {
        JsonObject params = updateParams("usage_update");
        params.addProperty("used", 100);
        params.addProperty("cost", "free");

        SessionUpdate update = parser.parse(params);

        assertInstanceOf(SessionUpdate.TurnUsage.class, update);
        assertNull(((SessionUpdate.TurnUsage) update).costUsd());
    }

    // ── Content block edge cases ────────────────────────────────────────

    @Test
    void parsesContentAsSingleObject() {
        JsonObject params = updateParams("agent_message_chunk");
        JsonObject content = new JsonObject();
        content.addProperty("type", "text");
        content.addProperty("text", "single object");
        params.add("content", content);

        var update = (SessionUpdate.AgentMessageChunk) parser.parse(params);
        assertEquals("single object", update.text());
    }

    @Test
    void parsesContentAsPrimitiveString() {
        JsonObject params = updateParams("agent_message_chunk");
        params.addProperty("content", "just a string");

        var update = (SessionUpdate.AgentMessageChunk) parser.parse(params);
        assertEquals("just a string", update.text());
    }

    @Test
    void parsesContentFromTextFieldFallback() {
        JsonObject params = updateParams("agent_message_chunk");
        params.addProperty("text", "fallback text");

        var update = (SessionUpdate.AgentMessageChunk) parser.parse(params);
        assertEquals("fallback text", update.text());
    }

    @Test
    void parsesEmptyContentBlocksWhenNoContentOrText() {
        JsonObject params = updateParams("agent_message_chunk");

        var update = (SessionUpdate.AgentMessageChunk) parser.parse(params);
        assertEquals("", update.text());
    }

    @Test
    void parsesThinkingContentBlock() {
        JsonObject params = updateParams("agent_thought_chunk");
        JsonArray arr = new JsonArray();
        JsonObject block = new JsonObject();
        block.addProperty("type", "thinking");
        block.addProperty("thinking", "reasoning step");
        arr.add(block);
        params.add("content", arr);

        var update = (SessionUpdate.AgentThoughtChunk) parser.parse(params);
        assertEquals("reasoning step", update.text());
    }

    @Test
    void parsesNestedContentTypeBlock() {
        // Spec: tool_call_update content items wrap blocks as {type:"content", content:{type,text}}
        JsonObject params = updateParams("agent_message_chunk");
        JsonArray arr = new JsonArray();
        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("type", "content");
        JsonObject inner = new JsonObject();
        inner.addProperty("type", "text");
        inner.addProperty("text", "nested text");
        wrapper.add("content", inner);
        arr.add(wrapper);
        params.add("content", arr);

        var update = (SessionUpdate.AgentMessageChunk) parser.parse(params);
        assertEquals("nested text", update.text());
    }

    @Test
    void parsesArrayWithPrimitiveStrings() {
        JsonObject params = updateParams("agent_message_chunk");
        JsonArray arr = new JsonArray();
        arr.add("hello ");
        arr.add("world");
        params.add("content", arr);

        var update = (SessionUpdate.AgentMessageChunk) parser.parse(params);
        assertEquals("hello world", update.text());
    }

    @Test
    void parsesUnknownBlockTypeAsEmptyText() {
        JsonObject params = updateParams("agent_message_chunk");
        JsonArray arr = new JsonArray();
        JsonObject block = new JsonObject();
        block.addProperty("type", "image");
        block.addProperty("data", "base64...");
        arr.add(block);
        params.add("content", arr);

        var update = (SessionUpdate.AgentMessageChunk) parser.parse(params);
        assertEquals("", update.text(), "Unknown block type should produce empty text");
    }

    // ── tool_call with locations and sub-agent ──────────────────────────

    @Test
    void parsesToolCall_withLocations() {
        JsonObject params = updateParams("tool_call");
        params.addProperty("toolCallId", "call_loc");
        params.addProperty("title", "edit_file");

        JsonArray locations = new JsonArray();
        JsonObject loc1 = new JsonObject();
        loc1.addProperty("uri", "file:///src/main/App.java");
        locations.add(loc1);
        JsonObject loc2 = new JsonObject();
        loc2.addProperty("path", "/src/test/AppTest.java");
        locations.add(loc2);
        params.add("locations", locations);

        var tc = (SessionUpdate.ToolCall) parser.parse(params);
        assertNotNull(tc.locations());
        assertEquals(2, tc.locations().size());
        assertEquals("file:///src/main/App.java", tc.locations().get(0).uri());
        assertEquals("/src/test/AppTest.java", tc.locations().get(1).uri(),
            "Should fall back to 'path' when 'uri' is missing");
    }

    @Test
    void parsesToolCall_withArguments() {
        JsonObject params = updateParams("tool_call");
        params.addProperty("toolCallId", "call_args");
        params.addProperty("title", "read_file");
        JsonObject args = new JsonObject();
        args.addProperty("path", "/etc/hosts");
        params.add("arguments", args);

        var tc = (SessionUpdate.ToolCall) parser.parse(params);
        assertNotNull(tc.arguments());
        assertTrue(tc.arguments().contains("/etc/hosts"));
    }

    @Test
    void parsesToolCall_withSubAgent() {
        // Use a parser with a delegate that detects sub-agents
        var subAgentParser = new AcpMessageParser(
            new AcpMessageParser.Delegate() {
                @Override
                public String resolveToolId(String protocolTitle) {
                    return protocolTitle;
                }

                @Override
                public @Nullable JsonObject parseToolCallArguments(@NotNull JsonObject params) {
                    return params.has("arguments") && params.get("arguments").isJsonObject()
                        ? params.getAsJsonObject("arguments") : null;
                }

                @Override
                public @Nullable String extractSubAgentType(@NotNull JsonObject params,
                                                            @NotNull String resolvedTitle,
                                                            @Nullable JsonObject argumentsObj) {
                    return "explore"; // Always detect as sub-agent for testing
                }
            },
            () -> "test-agent"
        );

        JsonObject params = updateParams("tool_call");
        params.addProperty("toolCallId", "call_sub");
        params.addProperty("title", "task");
        JsonObject args = new JsonObject();
        args.addProperty("description", "Explore the codebase");
        args.addProperty("prompt", "Find all test files");
        params.add("arguments", args);

        var tc = (SessionUpdate.ToolCall) subAgentParser.parse(params);
        assertEquals("explore", tc.agentType());
        assertEquals("Explore the codebase", tc.subAgentDescription());
        assertEquals("Find all test files", tc.subAgentPrompt());
    }

    // ── tool_call_update with result/error/description ──────────────────

    @Test
    void parsesToolCallUpdate_withPrimitiveResult() {
        JsonObject params = updateParams("tool_call_update");
        params.addProperty("toolCallId", "call_r1");
        params.addProperty("status", "completed");
        params.addProperty("result", "File contents here");

        var tcu = (SessionUpdate.ToolCallUpdate) parser.parse(params);
        assertEquals("File contents here", tcu.result());
    }

    @Test
    void parsesToolCallUpdate_withJsonObjectResult() {
        JsonObject params = updateParams("tool_call_update");
        params.addProperty("toolCallId", "call_r2");
        params.addProperty("status", "completed");
        JsonObject result = new JsonObject();
        result.addProperty("files", 3);
        params.add("result", result);

        var tcu = (SessionUpdate.ToolCallUpdate) parser.parse(params);
        assertNotNull(tcu.result());
        assertTrue(tcu.result().contains("\"files\":3") || tcu.result().contains("\"files\": 3"));
    }

    @Test
    void parsesToolCallUpdate_withContentBlocksAsResult() {
        JsonObject params = updateParams("tool_call_update");
        params.addProperty("toolCallId", "call_r3");
        params.addProperty("status", "completed");
        JsonArray content = new JsonArray();
        JsonObject block = new JsonObject();
        block.addProperty("type", "text");
        block.addProperty("text", "result from content");
        content.add(block);
        params.add("content", content);

        var tcu = (SessionUpdate.ToolCallUpdate) parser.parse(params);
        assertEquals("result from content", tcu.result());
    }

    @Test
    void parsesToolCallUpdate_withError() {
        JsonObject params = updateParams("tool_call_update");
        params.addProperty("toolCallId", "call_err");
        params.addProperty("status", "failed");
        params.addProperty("error", "Permission denied");

        var tcu = (SessionUpdate.ToolCallUpdate) parser.parse(params);
        assertEquals(SessionUpdate.ToolCallStatus.FAILED, tcu.status());
        assertEquals("Permission denied", tcu.error());
    }

    @Test
    void failedToolCallUpdate_errorUsedAsResultFallback() {
        // Verifies the pattern used by PromptOrchestrator: result ?: error
        // When a tool fails, the ACP stream typically puts the error text in the "error" field
        // and leaves "result" absent. The orchestrator must use error as fallback.
        JsonObject params = updateParams("tool_call_update");
        params.addProperty("toolCallId", "call_fail");
        params.addProperty("status", "failed");
        params.addProperty("error", "Error: binary not found at /usr/bin/copilot");
        // Note: no "result" field — this is the typical failed tool pattern

        var tcu = (SessionUpdate.ToolCallUpdate) parser.parse(params);
        assertNull(tcu.result());
        assertEquals("Error: binary not found at /usr/bin/copilot", tcu.error());
        // The orchestrator applies: result ?: error
        String displayText = tcu.result() != null ? tcu.result() : tcu.error();
        assertEquals("Error: binary not found at /usr/bin/copilot", displayText);
    }

    @Test
    void failedToolCallUpdate_resultTakesPriorityOverError() {
        // When both result and error are present, result should take priority
        JsonObject params = updateParams("tool_call_update");
        params.addProperty("toolCallId", "call_both");
        params.addProperty("status", "failed");
        params.addProperty("result", "Detailed error output from tool");
        params.addProperty("error", "Short error summary");

        var tcu = (SessionUpdate.ToolCallUpdate) parser.parse(params);
        assertEquals("Detailed error output from tool", tcu.result());
        assertEquals("Short error summary", tcu.error());
        // The orchestrator applies: result ?: error — result wins
        String displayText = tcu.result() != null ? tcu.result() : tcu.error();
        assertEquals("Detailed error output from tool", displayText);
    }

    @Test
    void parsesToolCallUpdate_withDescription() {
        JsonObject params = updateParams("tool_call_update");
        params.addProperty("toolCallId", "call_d");
        params.addProperty("status", "completed");
        params.addProperty("description", "Edited 3 files");

        var tcu = (SessionUpdate.ToolCallUpdate) parser.parse(params);
        assertEquals("Edited 3 files", tcu.description());
    }

    @Test
    void parsesToolCallUpdate_withRawInputArguments() {
        JsonObject params = updateParams("tool_call_update");
        params.addProperty("toolCallId", "call_raw");
        params.addProperty("status", "completed");
        params.addProperty("result", "ok");
        JsonObject rawInput = new JsonObject();
        rawInput.addProperty("path", "/src/main.java");
        params.add("rawInput", rawInput);

        var tcu = (SessionUpdate.ToolCallUpdate) parser.parse(params);
        assertNotNull(tcu.arguments());
        assertTrue(tcu.arguments().contains("/src/main.java"));
    }

    @Test
    void parsesToolCallUpdate_noResultOrContentReturnsNull() {
        JsonObject params = updateParams("tool_call_update");
        params.addProperty("toolCallId", "call_empty");
        params.addProperty("status", "completed");

        var tcu = (SessionUpdate.ToolCallUpdate) parser.parse(params);
        assertNull(tcu.result());
    }

    // ── turn_usage edge cases ──────────────────────────────────────────

    @Test
    void parsesTurnUsage_missingFieldsDefaultToZero() {
        JsonObject params = updateParams("turn_usage");

        var usage = (SessionUpdate.TurnUsage) parser.parse(params);
        assertEquals(0, usage.inputTokens());
        assertEquals(0, usage.outputTokens());
        assertEquals(0.0, usage.costUsd());
    }

    @Test
    void parsesTurnUsage_withCostUsd() {
        JsonObject params = updateParams("turn_usage");
        params.addProperty("inputTokens", 100);
        params.addProperty("outputTokens", 50);
        params.addProperty("costUsd", 0.003);

        var usage = (SessionUpdate.TurnUsage) parser.parse(params);
        assertEquals(0.003, usage.costUsd(), 0.0001);
    }

    // ── getStringOrEmpty utility ────────────────────────────────────────

    @Test
    void getStringOrEmpty_returnsValueWhenPresent() {
        JsonObject obj = new JsonObject();
        obj.addProperty("key", "value");
        assertEquals("value", AcpMessageParser.getStringOrEmpty(obj, "key"));
    }

    @Test
    void getStringOrEmpty_returnsEmptyWhenMissing() {
        assertEquals("", AcpMessageParser.getStringOrEmpty(new JsonObject(), "missing"));
    }

    @Test
    void getStringOrEmpty_returnsEmptyWhenNotPrimitive() {
        JsonObject obj = new JsonObject();
        obj.add("key", new JsonArray());
        assertEquals("", AcpMessageParser.getStringOrEmpty(obj, "key"));
    }

    // ── FAILED with no result or error → no synthesised fallback ────────────────

    @Test
    void failedToolCallUpdate_noResultOrError_errorIsNull() {
        JsonObject params = updateParams("tool_call_update");
        params.addProperty("toolCallId", "call_denied");
        params.addProperty("status", "failed");
        // No result, no error — simulates a CLI-level tool denial; UI handles the null case.

        var tcu = (SessionUpdate.ToolCallUpdate) parser.parse(params);
        assertNull(tcu.result());
        assertNull(tcu.error());
    }

    @Test
    void failedToolCallUpdate_withError_doesNotOverride() {
        // When an error is already present, the new branch must NOT overwrite it.
        JsonObject params = updateParams("tool_call_update");
        params.addProperty("toolCallId", "call_err");
        params.addProperty("status", "failed");
        params.addProperty("error", "binary not found");

        var tcu = (SessionUpdate.ToolCallUpdate) parser.parse(params);
        assertEquals("binary not found", tcu.error());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private JsonObject updateParams(String sessionUpdateType) {
        JsonObject params = new JsonObject();
        params.addProperty("sessionUpdate", sessionUpdateType);
        return params;
    }
}
