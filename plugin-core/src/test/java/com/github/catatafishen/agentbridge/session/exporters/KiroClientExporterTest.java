package com.github.catatafishen.agentbridge.session.exporters;

import com.github.catatafishen.agentbridge.ui.EntryData;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

        assertEquals(4, kiroMessages.size(), "Prompt + AssistantMessage + ToolResults + placeholder AssistantMessage");

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
        assertFalse(results.isEmpty(), "results map should not be empty");
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

        assertEquals(4, kiroMessages.size(), "Prompt + AssistantMessage + ToolResults + placeholder AssistantMessage");

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
        assertEquals(4, kiroMessages.size(), "Prompt + AssistantMessage + ToolResults + placeholder AssistantMessage");

        JsonObject results = kiroMessages.get(2).getAsJsonObject("data").getAsJsonObject("results");
        // Get the first (and only) tool result entry — toolCallId is a random UUID now
        String toolCallId = results.keySet().iterator().next();
        JsonObject toolEntry = results.getAsJsonObject(toolCallId);

        JsonObject mcp = toolEntry.getAsJsonObject("tool").getAsJsonObject("kind").getAsJsonObject("Mcp");
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
    void largeToolResultIsReplacedWithPlaceholder() {
        // Tool results exceeding MAX_TOOL_RESULT_CHARS (50_000 chars) should be replaced with
        // an omission placeholder, not silently truncated mid-content.
        String largeResult = "x".repeat(60_000);
        List<JsonObject> kiroMessages = KiroClientExporter.toKiroMessages(
            List.of(
                userPrompt("read big file"),
                assistantText("Reading."),
                toolCall("read_file", "{}", largeResult)
            ));

        // The ToolResults at index 2 should have the result replaced
        JsonObject trMsg = kiroMessages.get(2);
        assertEquals("ToolResults", trMsg.get("kind").getAsString());

        JsonObject resultEntry = trMsg.getAsJsonObject("data")
            .getAsJsonArray("content")
            .get(0).getAsJsonObject()
            .getAsJsonObject("data")
            .getAsJsonArray("content")
            .get(0).getAsJsonObject()
            .getAsJsonObject("data")
            .getAsJsonArray("content")
            .get(0).getAsJsonObject();
        String resultText = resultEntry.get("text").getAsString();
        assertTrue(resultText.contains("result omitted"), "Omission placeholder should be present");
        assertTrue(resultText.contains("60000"), "Placeholder should include original size");
        assertTrue(resultText.length() < largeResult.length(), "Placeholder should be shorter than original");
    }

    @Test
    void historyExceedingBudgetIsTrimmedFromOldest() {
        // Build a conversation with many turns to exceed MAX_TOTAL_HISTORY_CHARS.
        // Each turn has a text + tool call with a 4000-char result (just at the truncation limit).
        // We create enough turns to exceed the 400K-char budget.
        List<EntryData> entries = new ArrayList<>();
        // Add 120 turns: each produces ~5KB after export → 120 × 5KB = ~600KB > 400KB budget
        String repeatedResult = "a".repeat(4_000);  // exactly at truncation limit
        for (int i = 0; i < 120; i++) {
            entries.add(userPrompt("Turn " + i));
            entries.add(assistantText("Answer " + i));
            entries.add(toolCall("read_file", "{}", repeatedResult));
        }

        List<JsonObject> kiroMessages = KiroClientExporter.toKiroMessages(entries, 400_000);

        // Must still start with a Prompt and be structurally valid
        assertFalse(kiroMessages.isEmpty(), "Messages should not be empty after trim");
        assertEquals("Prompt", kiroMessages.getFirst().get("kind").getAsString(),
            "Must start with a Prompt after trim");
        // Must be smaller than the 120-turn original
        assertTrue(kiroMessages.size() < entries.size(),
            "Trimmed list should be shorter than original — was: " + kiroMessages.size());
        // No consecutive same-kind messages
        for (int i = 0; i < kiroMessages.size() - 1; i++) {
            String a = kiroMessages.get(i).get("kind").getAsString();
            String b = kiroMessages.get(i + 1).get("kind").getAsString();
            assertNotEquals(a, b, "Consecutive same-kind messages at " + i + ": " + a);
        }
    }

    @Test
    void singleTurnHistoryExceedingBudgetIsTrimedByDroppingOldestToolRounds() {
        // Single user message followed by many tool-call rounds — no second Prompt to trim by.
        // The fallback must drop the oldest AssistantMessage+ToolResults pairs until within budget.
        List<EntryData> entries = new ArrayList<>();
        entries.add(userPrompt("Do lots of work"));
        // Add enough tool-call rounds to exceed the 400K-char budget.
        // Each round: assistantText + toolCall with ~5KB result → ~6KB per round.
        // 100 rounds ≈ 600KB > 400KB.
        String bigResult = "x".repeat(5_000);
        for (int i = 0; i < 100; i++) {
            entries.add(assistantText("Step " + i));
            entries.add(toolCall("read_file", "{}", bigResult));
        }
        entries.add(assistantText("Done!"));

        List<JsonObject> kiroMessages = KiroClientExporter.toKiroMessages(entries, 400_000);

        assertFalse(kiroMessages.isEmpty(), "Messages must not be empty after trim");
        assertEquals("Prompt", kiroMessages.getFirst().get("kind").getAsString(),
            "Must still start with the single Prompt");
        // Structural validity: no consecutive same-kind messages
        for (int i = 0; i < kiroMessages.size() - 1; i++) {
            String a = kiroMessages.get(i).get("kind").getAsString();
            String b = kiroMessages.get(i + 1).get("kind").getAsString();
            assertNotEquals(a, b, "Consecutive same-kind messages at " + i + ": " + a);
        }
        // Must be within the 400K budget
        int totalChars = kiroMessages.stream()
            .mapToInt(m -> new com.google.gson.GsonBuilder().create().toJson(m).length())
            .sum();
        assertTrue(totalChars <= 400_000,
            "Trimmed history must be within 400K chars budget, was: " + totalChars);
    }

    @Test
    void thinkingEntryIsSkippedInExport() {
        // Thinking blocks must NOT be included in Kiro exported sessions.
        // Anthropic's API rejects conversation history with thinking blocks unless extended
        // thinking is explicitly enabled in the session. Kiro doesn't enable extended thinking
        // when resuming from exported history, causing an immediate crash on session/prompt.
        List<JsonObject> kiroMessages = KiroClientExporter.toKiroMessages(
            List.of(
                userPrompt("Think about this"),
                new EntryData.Thinking("Let me consider...", ""),
                assistantText("Here is my answer.")
            ));

        assertEquals(2, kiroMessages.size(), "Prompt + AssistantMessage");
        assertEquals("AssistantMessage", kiroMessages.get(1).get("kind").getAsString());

        var assistantContent = kiroMessages.get(1).getAsJsonObject("data").getAsJsonArray("content");
        assertEquals(1, assistantContent.size(), "Only text block — thinking block must be excluded");
        assertEquals("text", assistantContent.get(0).getAsJsonObject().get("kind").getAsString());
    }

    // ── Helper methods ──────────────────────────────────────────────

    @Test
    void toolResultHasStatusField() {
        List<JsonObject> kiroMessages = KiroClientExporter.toKiroMessages(
            List.of(
                userPrompt("do it"),
                toolCall("read_file", "{}", "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}")
            ));

        JsonObject trData = kiroMessages.get(2).getAsJsonObject("data");
        JsonArray trContent = trData.getAsJsonArray("content");
        JsonObject toolResultData = trContent.get(0).getAsJsonObject().getAsJsonObject("data");

        assertTrue(toolResultData.has("status"), "toolResult data must have 'status' field");
        assertEquals("success", toolResultData.get("status").getAsString());
    }

    @Test
    void toolResultContentHasIsErrorField() {
        List<JsonObject> kiroMessages = KiroClientExporter.toKiroMessages(
            List.of(
                userPrompt("do it"),
                toolCall("read_file", "{}", "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}")
            ));

        JsonObject trData = kiroMessages.get(2).getAsJsonObject("data");
        JsonArray trContent = trData.getAsJsonArray("content");
        JsonObject jsonData = trContent.get(0).getAsJsonObject().getAsJsonObject("data")
            .getAsJsonArray("content").get(0).getAsJsonObject().getAsJsonObject("data");

        assertTrue(jsonData.has("isError"), "result content JSON must have 'isError' field");
        assertFalse(jsonData.get("isError").getAsBoolean());
    }

    @Test
    void errorToolCallProducesErrorStatusAndResultWrapper() {
        EntryData.ToolCall errorTool = new EntryData.ToolCall(
            "read_file", "{\"path\":\"/missing\"}", "other",
            "{\"content\":[{\"type\":\"text\",\"text\":\"File not found\"}],\"isError\":true}");
        errorTool.setStatus("error");

        List<JsonObject> kiroMessages = KiroClientExporter.toKiroMessages(
            List.of(userPrompt("read it"), errorTool));

        // Check toolResult status
        JsonObject trData = kiroMessages.get(2).getAsJsonObject("data");
        JsonObject toolResultData = trData.getAsJsonArray("content").get(0)
            .getAsJsonObject().getAsJsonObject("data");
        assertEquals("error", toolResultData.get("status").getAsString());

        // Check results map uses "Error" key instead of "Success"
        String toolCallId = trData.getAsJsonObject("results").keySet().iterator().next();
        JsonObject toolEntry = trData.getAsJsonObject("results").getAsJsonObject(toolCallId);
        assertTrue(toolEntry.getAsJsonObject("result").has("Error"),
            "Error tool calls should use 'Error' key in result wrapper");
        assertFalse(toolEntry.getAsJsonObject("result").has("Success"));
    }

    @Test
    void toolResultsMapHasKindWrapper() {
        List<JsonObject> kiroMessages = KiroClientExporter.toKiroMessages(
            List.of(
                userPrompt("go"),
                toolCall("search_text", "{\"query\":\"x\"}", "found")
            ));

        JsonObject results = kiroMessages.get(2).getAsJsonObject("data").getAsJsonObject("results");
        String id = results.keySet().iterator().next();
        JsonObject toolInfo = results.getAsJsonObject(id).getAsJsonObject("tool");

        assertTrue(toolInfo.has("kind"), "tool info must have 'kind' wrapper");
        assertTrue(toolInfo.getAsJsonObject("kind").has("Mcp"),
            "kind must contain 'Mcp' for agentbridge tools");
    }

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
