package com.github.catatafishen.agentbridge.session.exporters;

import com.github.catatafishen.agentbridge.ui.EntryData;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CopilotClientExporter}.
 * Validates that session entries are correctly serialised into Copilot CLI's
 * native {@code events.jsonl} event-chain format.
 */
class CopilotClientExporterTest {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    @TempDir
    Path tempDir;

    // ── File creation ────────────────────────────────────────────────────────

    @Test
    void exportCreatesTargetFile() throws IOException {
        Path target = tempDir.resolve("events.jsonl");
        CopilotClientExporter.exportToFile(List.of(prompt("Hi")), target);
        assertTrue(Files.exists(target), "exportToFile must create the target file");
    }

    @Test
    void exportCreatesParentDirectoriesIfMissing() throws IOException {
        Path target = tempDir.resolve("a").resolve("b").resolve("events.jsonl");
        CopilotClientExporter.exportToFile(List.of(prompt("Hi")), target);
        assertTrue(Files.exists(target),
            "exportToFile must create intermediate directories as needed");
    }

    @Test
    void exportProducesNonEmptyFile() throws IOException {
        Path target = tempDir.resolve("events.jsonl");
        CopilotClientExporter.exportToFile(List.of(prompt("Hi"), text("Hello!")), target);
        assertTrue(Files.size(target) > 0, "Exported file must not be empty");
    }

    // ── JSONL format ─────────────────────────────────────────────────────────

    @Test
    void everyLineIsValidJson() throws IOException {
        Path target = tempDir.resolve("events.jsonl");
        CopilotClientExporter.exportToFile(
            List.of(prompt("Question"), text("Answer"), toolCall("ls", "{}", "file.txt")), target);

        for (String line : Files.readAllLines(target, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            assertDoesNotThrow(() -> GSON.fromJson(line, JsonObject.class),
                "Every line must be valid JSON; got: " + line);
        }
    }

    @Test
    void everyEventHasTypeDataIdTimestampAndParentId() throws IOException {
        List<JsonObject> events = exportAndParse(List.of(prompt("Hi"), text("Ho")));
        for (JsonObject event : events) {
            assertTrue(event.has("type"), "event must have 'type': " + event);
            assertTrue(event.has("data"), "event must have 'data': " + event);
            assertTrue(event.has("id"), "event must have 'id': " + event);
            assertTrue(event.has("timestamp"), "event must have 'timestamp': " + event);
            assertTrue(event.has("parentId"), "event must have 'parentId': " + event);
        }
    }

    @Test
    void firstEventIsSessionStart() throws IOException {
        List<JsonObject> events = exportAndParse(List.of(prompt("Hello")));
        JsonObject first = events.get(0);
        assertEquals("session.start", first.get("type").getAsString());
    }

    @Test
    void sessionStartDataHasRequiredFields() throws IOException {
        List<JsonObject> events = exportAndParse(List.of(prompt("Hello")));
        JsonObject data = events.get(0).getAsJsonObject("data");
        assertTrue(data.has("sessionId"), "session.start data must have sessionId");
        assertTrue(data.has("version"), "session.start data must have version");
        assertTrue(data.has("producer"), "session.start data must have producer");
        assertTrue(data.has("startTime"), "session.start data must have startTime");
        assertTrue(data.has("context"), "session.start data must have context");
        assertTrue(data.has("alreadyInUse"), "session.start data must have alreadyInUse");
        assertEquals("copilot-agent", data.get("producer").getAsString());
        assertFalse(data.get("alreadyInUse").getAsBoolean());
    }

    @Test
    void sessionStartDataIncludesCwdFromBasePath() throws IOException {
        Path target = tempDir.resolve("events.jsonl");
        CopilotClientExporter.exportToFile(
            List.of(prompt("Hi")), target, null, "/my/project");
        List<JsonObject> events = parseFile(target);

        JsonObject context = events.get(0).getAsJsonObject("data").getAsJsonObject("context");
        assertEquals("/my/project", context.get("cwd").getAsString());
        assertEquals("/my/project", context.get("gitRoot").getAsString());
    }

    @Test
    void sessionStartUsesProvidedSessionId() throws IOException {
        Path target = tempDir.resolve("events.jsonl");
        CopilotClientExporter.exportToFile(
            List.of(prompt("Hi")), target, "my-custom-sid", null);
        List<JsonObject> events = parseFile(target);

        String sessionId = events.get(0).getAsJsonObject("data").get("sessionId").getAsString();
        assertEquals("my-custom-sid", sessionId);
    }

    @Test
    void modelChangeEventEmittedWhenModelDetected() throws IOException {
        // Use a text entry with a model set
        EntryData.Text textWithModel = new EntryData.Text(
            "Answer", Instant.now().toString(), "", "gpt-4o", "");
        List<JsonObject> events = exportAndParse(List.of(prompt("Question"), textWithModel));

        // session.model_change should appear before per-entry events
        boolean found = events.stream()
            .anyMatch(e -> "session.model_change".equals(safeType(e)));
        assertTrue(found, "session.model_change event expected when model is detected in entries");
    }

    @Test
    void noModelChangeEventWhenNoModelInEntries() throws IOException {
        // Entries without model (simple 1-arg constructors)
        List<JsonObject> events = exportAndParse(List.of(prompt("Hello"), text("Hi")));

        boolean found = events.stream()
            .anyMatch(e -> "session.model_change".equals(safeType(e)));
        assertFalse(found,
            "session.model_change must NOT be emitted when no model is detected in entries");
    }

    // ── User prompt handling ─────────────────────────────────────────────────

    @Test
    void promptProducesUserMessageEvent() throws IOException {
        List<JsonObject> events = exportAndParse(List.of(prompt("Please help me.")));

        boolean found = events.stream()
            .anyMatch(e -> "user.message".equals(safeType(e)));
        assertTrue(found, "prompt entry must produce a user.message event");
    }

    @Test
    void userMessageDataHasContentAndInteractionId() throws IOException {
        List<JsonObject> events = exportAndParse(List.of(prompt("Help")));

        JsonObject userMsg = events.stream()
            .filter(e -> "user.message".equals(safeType(e)))
            .findFirst()
            .orElseThrow(() -> new AssertionError("user.message event not found"));

        JsonObject data = userMsg.getAsJsonObject("data");
        assertEquals("Help", data.get("content").getAsString());
        assertTrue(data.has("interactionId"), "user.message data must have interactionId");
        assertTrue(data.has("attachments"), "user.message data must have attachments");
    }

    @Test
    void emptyPromptTextProducesNoUserMessageEvent() throws IOException {
        List<JsonObject> events = exportAndParse(List.of(new EntryData.Prompt("")));

        boolean found = events.stream()
            .anyMatch(e -> "user.message".equals(safeType(e)));
        assertFalse(found, "An empty prompt must not produce a user.message event");
    }

    // ── Assistant text handling ───────────────────────────────────────────────

    @Test
    void textEntryProducesTurnStartAndAssistantMessageEvents() throws IOException {
        List<JsonObject> events = exportAndParse(List.of(prompt("Q"), text("A")));

        boolean hasTurnStart = events.stream()
            .anyMatch(e -> "assistant.turn_start".equals(safeType(e)));
        boolean hasAssistantMessage = events.stream()
            .anyMatch(e -> "assistant.message".equals(safeType(e)));
        assertTrue(hasTurnStart, "text entry must produce an assistant.turn_start event");
        assertTrue(hasAssistantMessage, "text entry must produce an assistant.message event");
    }

    @Test
    void assistantMessageDataHasContentAndMessageId() throws IOException {
        List<JsonObject> events = exportAndParse(List.of(prompt("Q"), text("My response")));

        JsonObject aMsg = events.stream()
            .filter(e -> "assistant.message".equals(safeType(e)))
            .findFirst()
            .orElseThrow(() -> new AssertionError("assistant.message not found"));

        JsonObject data = aMsg.getAsJsonObject("data");
        assertEquals("My response", data.get("content").getAsString());
        assertTrue(data.has("messageId"), "assistant.message data must have messageId");
    }

    @Test
    void turnEndEventEmittedAfterAssistantTurn() throws IOException {
        List<JsonObject> events = exportAndParse(List.of(prompt("Q"), text("A")));

        boolean hasTurnEnd = events.stream()
            .anyMatch(e -> "assistant.turn_end".equals(safeType(e)));
        assertTrue(hasTurnEnd, "An open assistant turn must be closed with assistant.turn_end");
    }

    @Test
    void secondPromptAfterAssistantTurnEmitsTurnEndBeforeUserMessage() throws IOException {
        List<JsonObject> events = exportAndParse(
            List.of(prompt("Q1"), text("A1"), prompt("Q2")));

        List<String> types = events.stream()
            .map(CopilotClientExporterTest::safeType)
            .toList();

        int turnEndIdx = types.lastIndexOf("assistant.turn_end");
        int userMsg2Idx = -1;
        // Find the second user.message
        int count = 0;
        for (int i = 0; i < types.size(); i++) {
            if ("user.message".equals(types.get(i))) {
                count++;
                if (count == 2) {
                    userMsg2Idx = i;
                    break;
                }
            }
        }

        assertTrue(turnEndIdx >= 0, "assistant.turn_end must be emitted");
        assertTrue(userMsg2Idx >= 0, "second user.message must be emitted");
        assertTrue(turnEndIdx < userMsg2Idx,
            "assistant.turn_end must come before the second user.message");
    }

    // ── Thinking entry handling ──────────────────────────────────────────────

    @Test
    void thinkingEntryProducesReasoningEvent() throws IOException {
        List<JsonObject> events = exportAndParse(
            List.of(prompt("Q"), new EntryData.Thinking("Let me think...", ""), text("A")));

        boolean found = events.stream()
            .anyMatch(e -> "assistant.reasoning".equals(safeType(e)));
        assertTrue(found, "Thinking entry must produce an assistant.reasoning event");
    }

    @Test
    void thinkingReasoningDataHasContent() throws IOException {
        List<JsonObject> events = exportAndParse(
            List.of(prompt("Q"), new EntryData.Thinking("My reasoning process", ""), text("Done")));

        JsonObject reasoningEvent = events.stream()
            .filter(e -> "assistant.reasoning".equals(safeType(e)))
            .findFirst()
            .orElseThrow(() -> new AssertionError("assistant.reasoning not found"));

        assertEquals("My reasoning process",
            reasoningEvent.getAsJsonObject("data").get("content").getAsString());
    }

    // ── Tool call handling ───────────────────────────────────────────────────

    @Test
    void toolCallProducesAssistantMessageWithToolRequestsAndExecutionCompleteEvent()
        throws IOException {
        List<JsonObject> events = exportAndParse(
            List.of(prompt("List files"), toolCall("ls", "{\"dir\":\"/tmp\"}", "a.txt\nb.txt")));

        boolean hasAssistantWithTool = events.stream()
            .filter(e -> "assistant.message".equals(safeType(e)))
            .anyMatch(e -> {
                JsonObject data = e.getAsJsonObject("data");
                return data.has("toolRequests")
                    && data.get("toolRequests").getAsJsonArray().size() > 0;
            });
        assertTrue(hasAssistantWithTool,
            "tool call must produce an assistant.message with toolRequests");

        boolean hasExecComplete = events.stream()
            .anyMatch(e -> "tool.execution_complete".equals(safeType(e)));
        assertTrue(hasExecComplete, "tool call must produce a tool.execution_complete event");
    }

    @Test
    void toolRequestHasToolCallIdNameAndArguments() throws IOException {
        List<JsonObject> events = exportAndParse(
            List.of(prompt("Go"), toolCall("read_file", "{\"path\":\"/f\"}", "data")));

        JsonObject assistantMsg = events.stream()
            .filter(e -> "assistant.message".equals(safeType(e)))
            .filter(e -> e.getAsJsonObject("data").has("toolRequests"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("assistant.message with toolRequests not found"));

        JsonArray toolRequests = assistantMsg.getAsJsonObject("data").getAsJsonArray("toolRequests");
        assertEquals(1, toolRequests.size());
        JsonObject toolReq = toolRequests.get(0).getAsJsonObject();
        assertTrue(toolReq.has("toolCallId"), "toolRequest must have toolCallId");
        assertEquals("read_file", toolReq.get("name").getAsString());
        assertTrue(toolReq.has("arguments"), "toolRequest must have arguments");
    }

    @Test
    void executionCompleteHasMatchingToolCallId() throws IOException {
        List<JsonObject> events = exportAndParse(
            List.of(prompt("Go"), toolCall("my_tool", "{}", "result")));

        JsonObject assistantMsg = events.stream()
            .filter(e -> "assistant.message".equals(safeType(e)))
            .filter(e -> e.getAsJsonObject("data").has("toolRequests"))
            .findFirst()
            .orElseThrow();

        String toolCallId = assistantMsg
            .getAsJsonObject("data")
            .getAsJsonArray("toolRequests").get(0).getAsJsonObject()
            .get("toolCallId").getAsString();

        JsonObject execComplete = events.stream()
            .filter(e -> "tool.execution_complete".equals(safeType(e)))
            .findFirst()
            .orElseThrow(() -> new AssertionError("tool.execution_complete not found"));

        assertEquals(toolCallId, execComplete.getAsJsonObject("data").get("toolCallId").getAsString(),
            "tool.execution_complete toolCallId must match the toolRequest toolCallId");
    }

    @Test
    void executionCompleteCarriesToolResult() throws IOException {
        List<JsonObject> events = exportAndParse(
            List.of(prompt("Go"), toolCall("search", "{}", "found 3 results")));

        JsonObject execComplete = events.stream()
            .filter(e -> "tool.execution_complete".equals(safeType(e)))
            .findFirst()
            .orElseThrow();

        JsonObject result = execComplete.getAsJsonObject("data").getAsJsonObject("result");
        assertNotNull(result, "execution_complete must have a result object");
        assertEquals("found 3 results", result.get("content").getAsString());
    }

    // ── Event chaining ───────────────────────────────────────────────────────

    @Test
    void eventsFormALinkedChainViaParentId() throws IOException {
        List<JsonObject> events = exportAndParse(
            List.of(prompt("Hi"), text("Hello"), toolCall("ls", "{}", "files")));

        // Each event's parentId must equal the previous event's id
        for (int i = 1; i < events.size(); i++) {
            String prevId = events.get(i - 1).get("id").getAsString();
            String parentId = events.get(i).get("parentId").getAsString();
            assertEquals(prevId, parentId,
                "Event " + i + " parentId must equal event " + (i - 1) + " id; "
                    + "prev.id=" + prevId + " cur.parentId=" + parentId);
        }
    }

    @Test
    void allEventIdsAreDistinct() throws IOException {
        List<JsonObject> events = exportAndParse(
            List.of(prompt("Q1"), text("A1"), prompt("Q2"), text("A2"),
                toolCall("tool", "{}", "res")));

        List<String> ids = events.stream()
            .map(e -> e.get("id").getAsString())
            .toList();
        long distinctCount = ids.stream().distinct().count();
        assertEquals(ids.size(), distinctCount,
            "All event ids must be unique; found duplicates among: " + ids);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private List<JsonObject> exportAndParse(List<EntryData> entries) throws IOException {
        Path target = tempDir.resolve("events-" + System.nanoTime() + ".jsonl");
        CopilotClientExporter.exportToFile(entries, target);
        return parseFile(target);
    }

    private static List<JsonObject> parseFile(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
            .filter(l -> !l.isBlank())
            .map(l -> GSON.fromJson(l, JsonObject.class))
            .toList();
    }

    private static String safeType(JsonObject obj) {
        return obj != null && obj.has("type") ? obj.get("type").getAsString() : "";
    }

    private static EntryData.Prompt prompt(String text) {
        return new EntryData.Prompt(text);
    }

    private static EntryData.Text text(String content) {
        // 1-arg constructor: no model, so no session.model_change event
        return new EntryData.Text(content);
    }

    private static EntryData.ToolCall toolCall(String name, String args, String result) {
        return new EntryData.ToolCall(name, args, "other", result);
    }
}
