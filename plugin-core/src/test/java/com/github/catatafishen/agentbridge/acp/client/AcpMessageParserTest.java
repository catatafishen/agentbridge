package com.github.catatafishen.agentbridge.acp.client;

import com.github.catatafishen.agentbridge.acp.model.SessionUpdate;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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

    // ── Helpers ──────────────────────────────────────────────────────────────

    private JsonObject updateParams(String sessionUpdateType) {
        JsonObject params = new JsonObject();
        params.addProperty("sessionUpdate", sessionUpdateType);
        return params;
    }
}
