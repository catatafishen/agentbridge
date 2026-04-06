package com.github.catatafishen.ideagentforcopilot.session.exporters;

import com.github.catatafishen.ideagentforcopilot.ui.EntryData;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link KiroClientExporter}.
 * Validates that v2 session entries are correctly converted to Kiro's native format
 * ({@code <uuid>.json} + {@code <uuid>.jsonl} in {@code ~/.kiro/sessions/cli/}).
 */
class KiroClientExporterTest {

    @TempDir
    Path tempDir;

    @Test
    void exportCreatesSessionJsonAndJsonl() {
        List<EntryData> entries = List.of(
            userPrompt("Hello"),
            assistantText("Hi there!")
        );

        String sessionId = KiroClientExporter.exportSession(entries, "/test/project", tempDir);

        assertNotNull(sessionId);
        assertTrue(Files.exists(tempDir.resolve(sessionId + ".json")));
        assertTrue(Files.exists(tempDir.resolve(sessionId + ".jsonl")));
    }

    @Test
    void sessionJsonHasCorrectFormat() throws Exception {
        String sessionId = KiroClientExporter.exportSession(
            List.of(userPrompt("Hi")), "/my/project", tempDir);

        String json = Files.readString(tempDir.resolve(sessionId + ".json"), StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        assertEquals(sessionId, root.get("session_id").getAsString());
        assertEquals("/my/project", root.get("cwd").getAsString());
        assertTrue(root.has("created_at"));
        assertTrue(root.has("updated_at"));

        JsonObject state = root.getAsJsonObject("session_state");
        assertEquals("v1", state.get("version").getAsString());
        assertTrue(state.has("conversation_metadata"));

        // rts_model_state is required by Kiro for session/load
        JsonObject rtsModelState = state.getAsJsonObject("rts_model_state");
        assertNotNull(rtsModelState, "rts_model_state is required by Kiro");
        assertEquals(sessionId, rtsModelState.get("conversation_id").getAsString());
        assertTrue(rtsModelState.get("model_info").isJsonNull());
        assertTrue(rtsModelState.get("context_usage_percentage").isJsonNull());

        // permissions are required by Kiro for session/load
        JsonObject permissions = state.getAsJsonObject("permissions");
        assertNotNull(permissions, "permissions is required by Kiro");

        JsonObject filesystem = permissions.getAsJsonObject("filesystem");
        assertNotNull(filesystem);
        var readPaths = filesystem.getAsJsonArray("allowed_read_paths");
        assertEquals(1, readPaths.size());
        assertEquals("/my/project", readPaths.get(0).getAsString());
        assertEquals(0, filesystem.getAsJsonArray("allowed_write_paths").size());

        assertEquals(0, permissions.getAsJsonArray("trusted_tools").size());
        assertEquals(0, permissions.getAsJsonArray("denied_tools").size());
    }

    @Test
    void exportEmptyEntriesReturnsNull() {
        String result = KiroClientExporter.exportSession(List.of(), "/test", tempDir);
        assertNull(result);
    }

    @Test
    void userMessageBecomesPrompt() {
        List<JsonObject> kiroMessages = KiroClientExporter.toKiroMessages(
            List.of(userPrompt("Hello world")));

        assertEquals(1, kiroMessages.size());
        JsonObject msg = kiroMessages.getFirst();

        assertEquals("v1", msg.get("version").getAsString());
        assertEquals("Prompt", msg.get("kind").getAsString());

        JsonObject data = msg.getAsJsonObject("data");
        assertNotNull(data.get("message_id"));

        var content = data.getAsJsonArray("content");
        assertEquals(1, content.size());

        JsonObject block = content.get(0).getAsJsonObject();
        assertEquals("text", block.get("kind").getAsString());
        assertEquals("Hello world", block.get("data").getAsString());
    }

    @Test
    void assistantTextBecomesAssistantMessage() {
        List<JsonObject> kiroMessages = KiroClientExporter.toKiroMessages(
            List.of(userPrompt("Hi"), assistantText("I can help")));

        assertEquals(2, kiroMessages.size());
        JsonObject msg = kiroMessages.get(1);

        assertEquals("AssistantMessage", msg.get("kind").getAsString());

        var content = msg.getAsJsonObject("data").getAsJsonArray("content");
        assertEquals(1, content.size());
        assertEquals("text", content.get(0).getAsJsonObject().get("kind").getAsString());
        assertEquals("I can help", content.get(0).getAsJsonObject().get("data").getAsString());
    }

    @Test
    void toolCallProducesAssistantMessageAndToolResults() {
        List<JsonObject> kiroMessages = KiroClientExporter.toKiroMessages(
            List.of(
                userPrompt("read it"),
                assistantText("Let me read that."),
                toolCall("read_file", "{\"path\":\"/test.txt\"}",
                    "{\"content\":[{\"type\":\"text\",\"text\":\"file data\"}]}")
            ));

        assertEquals(3, kiroMessages.size(), "Prompt + AssistantMessage + ToolResults");

        JsonObject assistantMsg = kiroMessages.get(1);
        assertEquals("AssistantMessage", assistantMsg.get("kind").getAsString());
        var assistantContent = assistantMsg.getAsJsonObject("data").getAsJsonArray("content");
        assertEquals(2, assistantContent.size());
        assertEquals("text", assistantContent.get(0).getAsJsonObject().get("kind").getAsString());
        assertEquals("toolUse", assistantContent.get(1).getAsJsonObject().get("kind").getAsString());

        JsonObject toolUseData = assistantContent.get(1).getAsJsonObject().getAsJsonObject("data");
        assertNotNull(toolUseData.get("toolUseId").getAsString());
        assertEquals("read_file", toolUseData.get("name").getAsString());

        JsonObject toolResultsMsg = kiroMessages.get(2);
        assertEquals("ToolResults", toolResultsMsg.get("kind").getAsString());

        JsonObject trData = toolResultsMsg.getAsJsonObject("data");
        var trContent = trData.getAsJsonArray("content");
        assertEquals(1, trContent.size());
        assertEquals("toolResult", trContent.get(0).getAsJsonObject().get("kind").getAsString());

        // Verify toolUseId matches between tool_use and tool_result
        String toolUseId = toolUseData.get("toolUseId").getAsString();
        String toolResultUseId = trContent.get(0).getAsJsonObject()
            .getAsJsonObject("data").get("toolUseId").getAsString();
        assertEquals(toolUseId, toolResultUseId, "toolUseId must match between use and result");

        JsonObject results = trData.getAsJsonObject("results");
        assertTrue(results.size() > 0, "results map should not be empty");
    }

    @Test
    void multipleToolCallsStayInSameTurn() {
        List<JsonObject> kiroMessages = KiroClientExporter.toKiroMessages(
            List.of(
                userPrompt("do it"),
                assistantText("Reading files..."),
                toolCall("read_file", "{}", "result1"),
                toolCall("search_text", "{}", "result2")
            ));

        assertEquals(3, kiroMessages.size(), "Prompt + AssistantMessage + ToolResults");

        var assistantContent = kiroMessages.get(1).getAsJsonObject("data").getAsJsonArray("content");
        assertEquals(3, assistantContent.size(), "text + 2 toolUse blocks");

        var trContent = kiroMessages.get(2).getAsJsonObject("data").getAsJsonArray("content");
        assertEquals(2, trContent.size(), "Two toolResult blocks");
    }

    @Test
    void textAfterToolsSplitsIntoNewTurn() {
        List<JsonObject> kiroMessages = KiroClientExporter.toKiroMessages(
            List.of(
                userPrompt("go"),
                assistantText("First."),
                toolCall("read_file", "{}", "data"),
                assistantText("After tool.")
            ));

        assertEquals(4, kiroMessages.size(),
            "Prompt, AssistantMessage(text+toolUse), ToolResults, AssistantMessage(text)");
        assertEquals("Prompt", kiroMessages.get(0).get("kind").getAsString());
        assertEquals("AssistantMessage", kiroMessages.get(1).get("kind").getAsString());
        assertEquals("ToolResults", kiroMessages.get(2).get("kind").getAsString());
        assertEquals("AssistantMessage", kiroMessages.get(3).get("kind").getAsString());

        var lastContent = kiroMessages.get(3).getAsJsonObject("data").getAsJsonArray("content");
        assertEquals("After tool.", lastContent.get(0).getAsJsonObject().get("data").getAsString());
    }

    @Test
    void fullConversationProducesCorrectKindSequence() {
        List<EntryData> conversation = List.of(
            userPrompt("Hello"),
            assistantText("Hi! How can I help?"),
            userPrompt("Read a file"),
            assistantText("Sure."),
            toolCall("read_file", "{}", "contents"),
            userPrompt("Thanks")
        );

        List<JsonObject> kiroMessages = KiroClientExporter.toKiroMessages(conversation);

        List<String> kinds = kiroMessages.stream()
            .map(m -> m.get("kind").getAsString())
            .toList();

        assertEquals(List.of(
            "Prompt", "AssistantMessage",
            "Prompt", "AssistantMessage", "ToolResults",
            "Prompt"
        ), kinds);
    }

    @Test
    void eachSplitTurnGetsUniqueMessageId() {
        List<JsonObject> kiroMessages = KiroClientExporter.toKiroMessages(
            List.of(
                userPrompt("go"),
                assistantText("First."),
                toolCall("read_file", "{}", "data"),
                assistantText("After tool.")
            ));

        // Prompt at 0, AssistantMessages at 1 and 3
        String id1 = kiroMessages.get(1).getAsJsonObject("data").get("message_id").getAsString();
        String id2 = kiroMessages.get(3).getAsJsonObject("data").get("message_id").getAsString();
        assertNotEquals(id1, id2, "Split turns should have unique message_ids");
    }

    @Test
    void sessionSeparatorsAreSkipped() {
        List<EntryData> entries = List.of(
            userPrompt("Hi"),
            new EntryData.SessionSeparator(""),
            assistantText("Hello")
        );

        List<JsonObject> kiroMessages = KiroClientExporter.toKiroMessages(entries);
        assertEquals(2, kiroMessages.size());
        assertEquals("Prompt", kiroMessages.get(0).get("kind").getAsString());
        assertEquals("AssistantMessage", kiroMessages.get(1).get("kind").getAsString());
    }

    @Test
    void prependsPlaceholderPromptWhenHistoryStartsWithAssistant() {
        // Simulates a scenario where the v2 session has no user message at the start.
        // Kiro panics with "invalid conversation history received" if the first message
        // is not a Prompt, so the exporter must defensively prepend one.
        List<JsonObject> kiroMessages = KiroClientExporter.toKiroMessages(
            List.of(assistantText("I can help with that.")));

        assertTrue(kiroMessages.size() >= 2, "Should have placeholder Prompt + AssistantMessage");
        assertEquals("Prompt", kiroMessages.getFirst().get("kind").getAsString());
        assertEquals("AssistantMessage", kiroMessages.get(1).get("kind").getAsString());

        // The placeholder should contain meaningful text
        String placeholderText = kiroMessages.getFirst()
            .getAsJsonObject("data").getAsJsonArray("content")
            .get(0).getAsJsonObject().get("data").getAsString();
        assertNotNull(placeholderText);
        assertTrue(placeholderText.contains("continued"));
    }

    @Test
    void allMessageIdsAreUnique() {
        // Regression test: previously all split AssistantMessage turns reused the same
        // message_id from the original v2 message, causing Kiro to reject the history.
        List<JsonObject> kiroMessages = KiroClientExporter.toKiroMessages(
            List.of(
                userPrompt("go"),
                assistantText("Part 1."),
                toolCall("read_file", "{}", "data1"),
                assistantText("Part 2."),
                toolCall("search_text", "{}", "data2"),
                assistantText("Part 3.")
            ));

        List<String> ids = kiroMessages.stream()
            .map(m -> m.getAsJsonObject("data").get("message_id").getAsString())
            .toList();

        assertEquals(ids.size(), ids.stream().distinct().count(),
            "All message_ids must be unique, got: " + ids);
    }

    @Test
    void toolResultsMapContainsMcpMetadata() {
        List<JsonObject> kiroMessages = KiroClientExporter.toKiroMessages(
            List.of(
                userPrompt("search"),
                toolCall("search_text", "{\"query\":\"hello\"}",
                    "{\"content\":[{\"type\":\"text\",\"text\":\"found it\"}]}")
            ));
        assertEquals(3, kiroMessages.size(), "Prompt + AssistantMessage + ToolResults");

        JsonObject results = kiroMessages.get(2).getAsJsonObject("data").getAsJsonObject("results");
        // Get the first (and only) tool result entry — toolCallId is a random UUID now
        String toolCallId = results.keySet().iterator().next();
        JsonObject toolEntry = results.getAsJsonObject(toolCallId);

        JsonObject mcp = toolEntry.getAsJsonObject("tool").getAsJsonObject("Mcp");
        assertEquals("search_text", mcp.get("toolName").getAsString());
        assertEquals("agentbridge", mcp.get("serverName").getAsString());
        assertEquals("hello", mcp.getAsJsonObject("params").get("query").getAsString());

        assertTrue(toolEntry.has("result"));
        assertTrue(toolEntry.getAsJsonObject("result").has("Success"));
    }

    @Test
    void consecutivePromptsDeduped() {
        // Regression test: duplicate user messages produce consecutive Prompts
        // that Kiro rejects as "invalid conversation history".
        List<JsonObject> kiroMessages = KiroClientExporter.toKiroMessages(
            List.of(
                userPrompt("Please continue"),
                userPrompt("Please continue"),
                assistantText("Here is the continuation.")
            ));

        // The earlier of the two consecutive Prompts should be removed, keeping the later one
        List<String> kinds = kiroMessages.stream()
            .map(m -> m.get("kind").getAsString())
            .toList();
        assertEquals(List.of("Prompt", "AssistantMessage"), kinds,
            "Consecutive Prompts should be deduplicated; got: " + kinds);

        // The remaining Prompt should be the last one
        JsonArray content = kiroMessages.getFirst().getAsJsonObject("data").getAsJsonArray("content");
        assertEquals(1, content.size(), "Deduplicated Prompt should have 1 content block");
    }

    @Test
    void threeConsecutivePromptsDeduped() {
        List<JsonObject> kiroMessages = KiroClientExporter.toKiroMessages(
            List.of(userPrompt("Msg A"), userPrompt("Msg B"), userPrompt("Msg C")));

        List<String> kinds = kiroMessages.stream()
            .map(m -> m.get("kind").getAsString())
            .toList();
        assertEquals(List.of("Prompt"), kinds,
            "Three consecutive Prompts should collapse to one");

        // Should keep the last Prompt (u3 = "Msg C")
        String text = kiroMessages.getFirst()
            .getAsJsonObject("data").getAsJsonArray("content")
            .get(0).getAsJsonObject().get("data").getAsString();
        assertEquals("Msg C", text, "Should keep the last Prompt's text");
    }

    @Test
    void consecutiveAssistantEntriesDoNotProduceConsecutiveAssistantMessages() {
        // Multiple assistant-side entries (text + tool) without an intervening Prompt
        // should be accumulated into a single AssistantMessage, never producing
        // consecutive AssistantMessages that Kiro would reject.
        List<JsonObject> kiroMessages = KiroClientExporter.toKiroMessages(
            List.of(
                userPrompt("Do something"),
                assistantText("Starting work..."),
                toolCall("read_file", "{}", "{\"result\":\"ok\"}")
            ));

        List<String> kinds = kiroMessages.stream()
            .map(m -> m.get("kind").getAsString())
            .toList();
        // No two consecutive AssistantMessages
        for (int i = 0; i < kinds.size() - 1; i++) {
            assertFalse(
                "AssistantMessage".equals(kinds.get(i)) && "AssistantMessage".equals(kinds.get(i + 1)),
                "Consecutive AssistantMessages at index " + i + ": " + kinds);
        }
    }

    @Test
    void thinkingEntryProducesThinkingBlock() {
        List<JsonObject> kiroMessages = KiroClientExporter.toKiroMessages(
            List.of(
                userPrompt("Think about this"),
                new EntryData.Thinking("Let me consider...", ""),
                assistantText("Here is my answer.")
            ));

        assertEquals(2, kiroMessages.size(), "Prompt + AssistantMessage");
        assertEquals("AssistantMessage", kiroMessages.get(1).get("kind").getAsString());

        var assistantContent = kiroMessages.get(1).getAsJsonObject("data").getAsJsonArray("content");
        assertEquals(2, assistantContent.size(), "thinking + text blocks");
        assertEquals("thinking", assistantContent.get(0).getAsJsonObject().get("kind").getAsString());
        assertEquals("Let me consider...", assistantContent.get(0).getAsJsonObject().get("data").getAsString());
        assertEquals("text", assistantContent.get(1).getAsJsonObject().get("kind").getAsString());
    }

    // ── Helper methods ──────────────────────────────────────────────

    private static EntryData.Prompt userPrompt(String text) {
        return new EntryData.Prompt(text);
    }

    private static EntryData.Text assistantText(String text) {
        return new EntryData.Text(text);
    }

    private static EntryData.ToolCall toolCall(String toolName, String args, String result) {
        return new EntryData.ToolCall(toolName, args, "other", result);
    }
}
