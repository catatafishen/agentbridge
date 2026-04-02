package com.github.catatafishen.ideagentforcopilot.session.exporters;

import com.github.catatafishen.ideagentforcopilot.session.v2.SessionMessage;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link KiroClientExporter}.
 * Validates that v2 session messages are correctly converted to Kiro's native format
 * ({@code <uuid>.json} + {@code <uuid>.jsonl} in {@code ~/.kiro/sessions/cli/}).
 */
class KiroClientExporterTest {

    @TempDir
    Path tempDir;

    @Test
    void exportCreatesSessionJsonAndJsonl() {
        List<SessionMessage> messages = List.of(
            userMessage("Hello"),
            assistantMessage("Hi there!")
        );

        String sessionId = KiroClientExporter.exportSession(messages, "/test/project", tempDir);

        assertNotNull(sessionId);
        assertTrue(Files.exists(tempDir.resolve(sessionId + ".json")));
        assertTrue(Files.exists(tempDir.resolve(sessionId + ".jsonl")));
    }

    @Test
    void sessionJsonHasCorrectFormat() throws Exception {
        String sessionId = KiroClientExporter.exportSession(
            List.of(userMessage("Hi")), "/my/project", tempDir);

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
    void exportEmptyMessagesReturnsNull() {
        String result = KiroClientExporter.exportSession(List.of(), "/test", tempDir);
        assertNull(result);
    }

    @Test
    void userMessageBecomesPrompt() {
        List<JsonObject> kiroMessages = KiroClientExporter.toKiroMessages(
            List.of(userMessage("Hello world")));

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
            List.of(userMessage("Hi"), assistantMessage("I can help")));

        assertEquals(2, kiroMessages.size());
        JsonObject msg = kiroMessages.get(1);

        assertEquals("AssistantMessage", msg.get("kind").getAsString());

        var content = msg.getAsJsonObject("data").getAsJsonArray("content");
        assertEquals(1, content.size());
        assertEquals("text", content.get(0).getAsJsonObject().get("kind").getAsString());
        assertEquals("I can help", content.get(0).getAsJsonObject().get("data").getAsString());
    }

    @Test
    void toolInvocationProducesAssistantMessageAndToolResults() {
        JsonObject toolPart = toolInvocationPart(
            "tc1", "read_file",
            "{\"path\":\"/test.txt\"}",
            "{\"content\":[{\"type\":\"text\",\"text\":\"file data\"}]}");

        SessionMessage assistant = new SessionMessage(
            "a1", "assistant",
            List.of(textPart("Let me read that."), toolPart),
            System.currentTimeMillis(), null, null);

        List<JsonObject> kiroMessages = KiroClientExporter.toKiroMessages(
            List.of(userMessage("read it"), assistant));

        assertEquals(3, kiroMessages.size(), "Prompt + AssistantMessage + ToolResults");

        JsonObject assistantMsg = kiroMessages.get(1);
        assertEquals("AssistantMessage", assistantMsg.get("kind").getAsString());
        var assistantContent = assistantMsg.getAsJsonObject("data").getAsJsonArray("content");
        assertEquals(2, assistantContent.size());
        assertEquals("text", assistantContent.get(0).getAsJsonObject().get("kind").getAsString());
        assertEquals("toolUse", assistantContent.get(1).getAsJsonObject().get("kind").getAsString());

        JsonObject toolUseData = assistantContent.get(1).getAsJsonObject().getAsJsonObject("data");
        assertEquals("tc1", toolUseData.get("toolUseId").getAsString());
        assertEquals("read_file", toolUseData.get("name").getAsString());

        JsonObject toolResultsMsg = kiroMessages.get(2);
        assertEquals("ToolResults", toolResultsMsg.get("kind").getAsString());

        JsonObject trData = toolResultsMsg.getAsJsonObject("data");
        var trContent = trData.getAsJsonArray("content");
        assertEquals(1, trContent.size());
        assertEquals("toolResult", trContent.get(0).getAsJsonObject().get("kind").getAsString());

        JsonObject results = trData.getAsJsonObject("results");
        assertTrue(results.has("tc1"));
    }

    @Test
    void multipleToolCallsStayInSameTurn() {
        JsonObject tool1 = toolInvocationPart("tc1", "read_file", "{}", "result1");
        JsonObject tool2 = toolInvocationPart("tc2", "search_text", "{}", "result2");

        SessionMessage assistant = new SessionMessage(
            "a1", "assistant",
            List.of(textPart("Reading files..."), tool1, tool2),
            System.currentTimeMillis(), null, null);

        List<JsonObject> kiroMessages = KiroClientExporter.toKiroMessages(
            List.of(userMessage("do it"), assistant));

        assertEquals(3, kiroMessages.size(), "Prompt + AssistantMessage + ToolResults");

        var assistantContent = kiroMessages.get(1).getAsJsonObject("data").getAsJsonArray("content");
        assertEquals(3, assistantContent.size(), "text + 2 toolUse blocks");

        var trContent = kiroMessages.get(2).getAsJsonObject("data").getAsJsonArray("content");
        assertEquals(2, trContent.size(), "Two toolResult blocks");
    }

    @Test
    void textAfterToolsSplitsIntoNewTurn() {
        JsonObject tool1 = toolInvocationPart("tc1", "read_file", "{}", "data");

        SessionMessage assistant = new SessionMessage(
            "a1", "assistant",
            List.of(textPart("First."), tool1, textPart("After tool.")),
            System.currentTimeMillis(), null, null);

        List<JsonObject> kiroMessages = KiroClientExporter.toKiroMessages(
            List.of(userMessage("go"), assistant));

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
        List<SessionMessage> conversation = List.of(
            userMessage("Hello"),
            assistantMessage("Hi! How can I help?"),
            userMessage("Read a file"),
            new SessionMessage("a2", "assistant",
                List.of(textPart("Sure."),
                    toolInvocationPart("tc1", "read_file", "{}", "contents")),
                System.currentTimeMillis(), null, null),
            userMessage("Thanks")
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
        JsonObject tool1 = toolInvocationPart("tc1", "read_file", "{}", "data");

        SessionMessage assistant = new SessionMessage(
            "a1", "assistant",
            List.of(textPart("First."), tool1, textPart("After tool.")),
            System.currentTimeMillis(), null, null);

        List<JsonObject> kiroMessages = KiroClientExporter.toKiroMessages(
            List.of(userMessage("go"), assistant));

        // Prompt at 0, AssistantMessages at 1 and 3
        String id1 = kiroMessages.get(1).getAsJsonObject("data").get("message_id").getAsString();
        String id2 = kiroMessages.get(3).getAsJsonObject("data").get("message_id").getAsString();
        assertNotEquals(id1, id2, "Split turns should have unique message_ids");
    }

    @Test
    void separatorMessagesAreSkipped() {
        List<SessionMessage> messages = List.of(
            userMessage("Hi"),
            new SessionMessage("sep1", "separator", List.of(),
                System.currentTimeMillis(), null, null),
            assistantMessage("Hello")
        );

        List<JsonObject> kiroMessages = KiroClientExporter.toKiroMessages(messages);
        assertEquals(2, kiroMessages.size());
        assertEquals("Prompt", kiroMessages.get(0).get("kind").getAsString());
        assertEquals("AssistantMessage", kiroMessages.get(1).get("kind").getAsString());
    }

    @Test
    void prependsPlaceholderPromptWhenHistoryStartsWithAssistant() {
        // Simulates a scenario where the v2 session has no user message at the start
        // (e.g., budget trimming dropped it, or import from another agent was incomplete).
        // Kiro panics with "invalid conversation history received" if the first message
        // is not a Prompt, so the exporter must defensively prepend one.
        List<JsonObject> kiroMessages = KiroClientExporter.toKiroMessages(
            List.of(assistantMessage("I can help with that.")));

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
        JsonObject tool1 = toolInvocationPart("tc1", "read_file", "{}", "data1");
        JsonObject tool2 = toolInvocationPart("tc2", "search_text", "{}", "data2");

        SessionMessage bigAssistant = new SessionMessage(
            "a1", "assistant",
            List.of(textPart("Part 1."), tool1, textPart("Part 2."), tool2, textPart("Part 3.")),
            System.currentTimeMillis(), null, null);

        List<JsonObject> kiroMessages = KiroClientExporter.toKiroMessages(
            List.of(userMessage("go"), bigAssistant));

        List<String> ids = kiroMessages.stream()
            .map(m -> m.getAsJsonObject("data").get("message_id").getAsString())
            .toList();

        assertEquals(ids.size(), ids.stream().distinct().count(),
            "All message_ids must be unique, got: " + ids);
    }

    @Test
    void toolResultsMapContainsMcpMetadata() {
        JsonObject toolPart = toolInvocationPart(
            "tc1", "search_text",
            "{\"query\":\"hello\"}",
            "{\"content\":[{\"type\":\"text\",\"text\":\"found it\"}]}");

        SessionMessage assistant = new SessionMessage(
            "a1", "assistant", List.of(toolPart),
            System.currentTimeMillis(), null, null);

        List<JsonObject> kiroMessages = KiroClientExporter.toKiroMessages(
            List.of(userMessage("search"), assistant));
        assertEquals(3, kiroMessages.size(), "Prompt + AssistantMessage + ToolResults");

        JsonObject results = kiroMessages.get(2).getAsJsonObject("data").getAsJsonObject("results");
        JsonObject tc1 = results.getAsJsonObject("tc1");

        JsonObject mcp = tc1.getAsJsonObject("tool").getAsJsonObject("Mcp");
        assertEquals("search_text", mcp.get("toolName").getAsString());
        assertEquals("agentbridge", mcp.get("serverName").getAsString());
        assertEquals("hello", mcp.getAsJsonObject("params").get("query").getAsString());

        assertTrue(tc1.has("result"));
        assertTrue(tc1.getAsJsonObject("result").has("Success"));
    }

    @Test
    void consecutivePromptsDeduped() {
        // Regression test: duplicate user messages (e.g. user clicked Send twice, or
        // a rate-limit error was followed by a retry prompt) produce consecutive Prompts
        // that Kiro rejects as "invalid conversation history".
        SessionMessage user1 = userMessage("Please continue");
        SessionMessage user2 = userMessage("Please continue");
        SessionMessage assistant = new SessionMessage(
            "a1", "assistant", List.of(textPart("Here is the continuation.")),
            System.currentTimeMillis(), null, null);

        List<JsonObject> kiroMessages = KiroClientExporter.toKiroMessages(
            List.of(user1, user2, assistant));

        // The earlier of the two consecutive Prompts should be removed, keeping the later one
        List<String> kinds = kiroMessages.stream()
            .map(m -> m.get("kind").getAsString())
            .toList();
        assertEquals(List.of("Prompt", "AssistantMessage"), kinds,
            "Consecutive Prompts should be deduplicated; got: " + kinds);

        // The remaining Prompt should be the last one (user2)
        JsonArray content = kiroMessages.getFirst().getAsJsonObject("data").getAsJsonArray("content");
        assertEquals(1, content.size(), "Deduplicated Prompt should have 1 content block");
    }

    @Test
    void threeConsecutivePromptsDeduped() {
        SessionMessage u1 = userMessage("Msg A");
        SessionMessage u2 = userMessage("Msg B");
        SessionMessage u3 = userMessage("Msg C");

        List<JsonObject> kiroMessages = KiroClientExporter.toKiroMessages(List.of(u1, u2, u3));

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

    // ── Helper methods ──────────────────────────────────────────────

    private static SessionMessage userMessage(String text) {
        return new SessionMessage("u-" + text.hashCode(), "user", List.of(textPart(text)),
            System.currentTimeMillis(), null, null);
    }

    private static SessionMessage assistantMessage(String text) {
        return new SessionMessage("a-" + text.hashCode(), "assistant", List.of(textPart(text)),
            System.currentTimeMillis(), null, null);
    }

    private static JsonObject textPart(String text) {
        JsonObject part = new JsonObject();
        part.addProperty("type", "text");
        part.addProperty("text", text);
        return part;
    }

    private static JsonObject toolInvocationPart(
        String callId, String toolName, String args, String result) {
        JsonObject invocation = new JsonObject();
        invocation.addProperty("state", "result");
        invocation.addProperty("toolCallId", callId);
        invocation.addProperty("toolName", toolName);
        invocation.addProperty("args", args);
        invocation.addProperty("result", result);

        JsonObject part = new JsonObject();
        part.addProperty("type", "tool-invocation");
        part.add("toolInvocation", invocation);
        return part;
    }
}
