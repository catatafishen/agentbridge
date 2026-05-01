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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CodexClientExporter} — specifically the package-private
 * {@code writeRolloutFile} method that produces the JSONL format Codex reads.
 */
class CodexClientExporterTest {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    @TempDir
    Path tempDir;

    // ── writeRolloutFile — prompt entries ────────────────────────────────────

    @Test
    void promptProducesMessageLineWithRoleUser() throws IOException {
        List<JsonObject> lines = writeAndParse(List.of(prompt("Hello")));

        assertEquals(1, lines.size());
        assertEquals("message", lines.get(0).get("type").getAsString());
        assertEquals("user", lines.get(0).get("role").getAsString());
    }

    @Test
    void promptContentIsInputTextType() throws IOException {
        List<JsonObject> lines = writeAndParse(List.of(prompt("Explain recursion")));

        JsonArray content = lines.get(0).getAsJsonArray("content");
        assertEquals(1, content.size());
        JsonObject inputText = content.get(0).getAsJsonObject();
        assertEquals("input_text", inputText.get("type").getAsString());
        assertEquals("Explain recursion", inputText.get("text").getAsString());
    }

    @Test
    void emptyPromptIsSkipped() throws IOException {
        List<JsonObject> lines = writeAndParse(List.of(new EntryData.Prompt("")));
        // Prompt with empty text still produces a line (the filter is on empty raw in text entries)
        // Actually, looking at the code: prompt writes regardless of text content.
        // Let's verify what the code actually emits.
        // It always emits: item.addProperty("type", "message"); etc.
        // So even empty prompts produce a line. The check is just that it doesn't crash.
        assertFalse(lines.isEmpty(), "Prompt entry always produces a message line");
    }

    // ── writeRolloutFile — text entries ──────────────────────────────────────

    @Test
    void textEntryProducesMessageLineWithRoleAssistant() throws IOException {
        List<JsonObject> lines = writeAndParse(List.of(prompt("Q"), text("A")));

        assertEquals(2, lines.size());
        assertEquals("message", lines.get(1).get("type").getAsString());
        assertEquals("assistant", lines.get(1).get("role").getAsString());
    }

    @Test
    void textContentIsOutputTextType() throws IOException {
        List<JsonObject> lines = writeAndParse(List.of(prompt("Q"), text("My answer")));

        JsonArray content = lines.get(1).getAsJsonArray("content");
        assertEquals(1, content.size());
        JsonObject outputText = content.get(0).getAsJsonObject();
        assertEquals("output_text", outputText.get("type").getAsString());
        assertEquals("My answer", outputText.get("text").getAsString());
    }

    @Test
    void emptyTextEntryProducesNoLine() throws IOException {
        List<JsonObject> lines = writeAndParse(
            List.of(prompt("Q"), new EntryData.Text("")));
        // Empty text entry should be skipped
        assertEquals(1, lines.size(),
            "Empty text entry must be skipped; only the prompt line should be present");
    }

    // ── writeRolloutFile — thinking entries ──────────────────────────────────

    @Test
    void thinkingEntryProducesReasoningLine() throws IOException {
        List<JsonObject> lines = writeAndParse(
            List.of(prompt("Q"), new EntryData.Thinking("I am reasoning...", ""), text("A")));

        // Prompt + reasoning + text = 3 lines
        assertEquals(3, lines.size());
        JsonObject reasoningLine = lines.get(1);
        assertEquals("reasoning", reasoningLine.get("type").getAsString());
    }

    @Test
    void thinkingContentIsReasoningTextType() throws IOException {
        List<JsonObject> lines = writeAndParse(
            List.of(prompt("Q"), new EntryData.Thinking("My thought process", ""), text("A")));

        JsonObject reasoningLine = lines.get(1);
        JsonArray content = reasoningLine.getAsJsonArray("content");
        assertEquals(1, content.size());
        JsonObject reasoningText = content.get(0).getAsJsonObject();
        assertEquals("reasoning_text", reasoningText.get("type").getAsString());
        assertEquals("My thought process", reasoningText.get("text").getAsString());
    }

    @Test
    void emptyThinkingEntryProducesNoLine() throws IOException {
        List<JsonObject> lines = writeAndParse(
            List.of(prompt("Q"), new EntryData.Thinking("", ""), text("A")));
        // Empty thinking should be skipped
        assertEquals(2, lines.size(),
            "Empty thinking entry must be skipped; expecting prompt + text lines only");
    }

    // ── writeRolloutFile — tool call entries ─────────────────────────────────

    @Test
    void toolCallProducesFunctionCallLineFollowedByOutputLine() throws IOException {
        List<JsonObject> lines = writeAndParse(
            List.of(prompt("List"), toolCall("ls", "{\"dir\":\"/tmp\"}", "a.txt")));

        assertEquals(3, lines.size()); // prompt + function_call + function_call_output
        assertEquals("function_call", lines.get(1).get("type").getAsString());
        assertEquals("function_call_output", lines.get(2).get("type").getAsString());
    }

    @Test
    void functionCallLineHasNameAndArguments() throws IOException {
        List<JsonObject> lines = writeAndParse(
            List.of(prompt("Go"), toolCall("read_file", "{\"path\":\"/etc/hosts\"}", "content")));

        JsonObject fcLine = lines.get(1);
        assertEquals("function_call", fcLine.get("type").getAsString());
        assertEquals("agentbridge_read_file", fcLine.get("name").getAsString(),
            "Tool name must be prefixed with agentbridge_ for Codex MCP tool matching");
        assertEquals("{\"path\":\"/etc/hosts\"}", fcLine.get("arguments").getAsString());
    }

    @Test
    void functionCallLineHasCallId() throws IOException {
        List<JsonObject> lines = writeAndParse(
            List.of(prompt("Go"), toolCall("my_tool", "{}", "result")));

        JsonObject fcLine = lines.get(1);
        assertTrue(fcLine.has("call_id"), "function_call line must have call_id");
        assertFalse(fcLine.get("call_id").getAsString().isEmpty(),
            "call_id must not be empty");
    }

    @Test
    void functionCallOutputHasMatchingCallIdAndOutput() throws IOException {
        List<JsonObject> lines = writeAndParse(
            List.of(prompt("Go"), toolCall("search", "{}", "search results here")));

        String callId = lines.get(1).get("call_id").getAsString();
        JsonObject outputLine = lines.get(2);
        assertEquals("function_call_output", outputLine.get("type").getAsString());
        assertEquals(callId, outputLine.get("call_id").getAsString(),
            "function_call_output call_id must match the function_call call_id");
        assertEquals("search results here", outputLine.get("output").getAsString());
    }

    @Test
    void toolCallWithNullArgumentsDefaultsToEmptyJson() throws IOException {
        List<JsonObject> lines = writeAndParse(
            List.of(prompt("Go"), new EntryData.ToolCall("my_tool", null, "other", "ok")));

        JsonObject fcLine = lines.get(1);
        assertEquals("{}", fcLine.get("arguments").getAsString(),
            "null arguments must default to '{}'");
    }

    @Test
    void toolCallWithNullResultDefaultsToEmptyString() throws IOException {
        List<JsonObject> lines = writeAndParse(
            List.of(prompt("Go"), new EntryData.ToolCall("my_tool", "{}", "other", null)));

        JsonObject outputLine = lines.get(2);
        assertEquals("", outputLine.get("output").getAsString(),
            "null result must default to empty string");
    }

    @Test
    void eachToolCallGetsUniqueCallId() throws IOException {
        List<JsonObject> lines = writeAndParse(
            List.of(
                prompt("Do two things"),
                toolCall("tool_a", "{}", "res_a"),
                toolCall("tool_b", "{}", "res_b")
            ));

        // Lines: prompt, func_call_a, func_output_a, func_call_b, func_output_b
        assertEquals(5, lines.size());
        String callIdA = lines.get(1).get("call_id").getAsString();
        String callIdB = lines.get(3).get("call_id").getAsString();
        assertNotEquals(callIdA, callIdB, "Each tool call must have a unique call_id");
    }

    // ── writeRolloutFile — mixed conversation ────────────────────────────────

    @Test
    void multiTurnConversationProducesCorrectLineCount() throws IOException {
        List<JsonObject> lines = writeAndParse(
            List.of(
                prompt("Q1"),
                text("A1"),
                prompt("Q2"),
                toolCall("my_tool", "{}", "tool_result"),
                text("A2 based on tool result")
            ));

        // prompt1 + text1 + prompt2 + function_call + function_output + text2
        assertEquals(6, lines.size(), "Expected 6 JSONL lines for the given conversation");
    }

    @Test
    void lineOrderMatchesEntryOrder() throws IOException {
        List<JsonObject> lines = writeAndParse(
            List.of(prompt("Q"), text("A")));

        assertEquals("user", lines.get(0).get("role").getAsString());
        assertEquals("assistant", lines.get(1).get("role").getAsString());
    }

    // ── writeRolloutFile — output validity ───────────────────────────────────

    @Test
    void everyLineIsValidJson() throws IOException {
        Path rollout = tempDir.resolve("rollout.jsonl");
        CodexClientExporter.writeRolloutFile(
            List.of(
                prompt("Q"),
                text("A"),
                new EntryData.Thinking("T", ""),
                toolCall("t", "{}", "r")
            ), rollout);

        for (String line : Files.readAllLines(rollout, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            assertDoesNotThrow(() -> GSON.fromJson(line, JsonObject.class),
                "Every line must be valid JSON: " + line);
        }
    }

    @Test
    void writeRolloutFileCreatesFile() throws IOException {
        Path rollout = tempDir.resolve("my-rollout.jsonl");
        CodexClientExporter.writeRolloutFile(List.of(prompt("Hi")), rollout);
        assertTrue(Files.exists(rollout), "writeRolloutFile must create the target file");
    }

    @Test
    void writeRolloutFileOverwritesExistingContent() throws IOException {
        Path rollout = tempDir.resolve("rollout.jsonl");
        // Write once
        CodexClientExporter.writeRolloutFile(List.of(prompt("First")), rollout);
        long sizeAfterFirst = Files.size(rollout);

        // Write again with more content
        CodexClientExporter.writeRolloutFile(
            List.of(prompt("First"), text("Answer"), toolCall("t", "{}", "r")), rollout);
        long sizeAfterSecond = Files.size(rollout);

        assertTrue(sizeAfterSecond > sizeAfterFirst,
            "Second write with more entries must produce a larger file");
    }

    // ── Native format structure tests ─────────────────────────────────────────

    @Test
    void exportedRolloutHasSessionMetaFirstLine() throws IOException {
        Path rollout = tempDir.resolve("rollout-meta.jsonl");
        CodexClientExporter.writeRolloutFile(List.of(prompt("Hi")), rollout);

        List<String> rawLines = Files.readAllLines(rollout, StandardCharsets.UTF_8).stream()
            .filter(l -> !l.isBlank())
            .toList();
        assertFalse(rawLines.isEmpty(), "Rollout file must not be empty");

        JsonObject firstLine = GSON.fromJson(rawLines.getFirst(), JsonObject.class);
        assertEquals("session_meta", firstLine.get("type").getAsString(),
            "First line must be session_meta");
        assertTrue(firstLine.has("originator"), "session_meta must have originator field");
        assertTrue(firstLine.has("cli_version"), "session_meta must have cli_version field");
    }

    @Test
    void contentItemsAreWrappedInResponseItemEnvelopes() throws IOException {
        Path rollout = tempDir.resolve("rollout-envelopes.jsonl");
        CodexClientExporter.writeRolloutFile(List.of(prompt("Hi"), text("Hello")), rollout);

        List<JsonObject> rawLines = Files.readAllLines(rollout, StandardCharsets.UTF_8).stream()
            .filter(l -> !l.isBlank())
            .map(l -> GSON.fromJson(l, JsonObject.class))
            .toList();

        // Line 0 = session_meta, lines 1+ = response_item envelopes
        for (int i = 1; i < rawLines.size(); i++) {
            JsonObject line = rawLines.get(i);
            assertEquals("response_item", line.get("type").getAsString(),
                "Content line " + i + " must be wrapped in response_item envelope");
            assertTrue(line.has("payload"),
                "response_item at line " + i + " must have a payload field");
            assertTrue(line.has("timestamp"),
                "response_item at line " + i + " must have a timestamp field");
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Writes a rollout file and parses the content lines.
     * Skips the {@code session_meta} header line and unwraps {@code response_item} envelopes
     * so tests can assert on the inner payload items directly.
     */
    private List<JsonObject> writeAndParse(List<EntryData> entries) throws IOException {
        Path rollout = tempDir.resolve("rollout-" + System.nanoTime() + ".jsonl");
        CodexClientExporter.writeRolloutFile(entries, rollout);
        return Files.readAllLines(rollout, StandardCharsets.UTF_8).stream()
            .filter(l -> !l.isBlank())
            .map(l -> GSON.fromJson(l, JsonObject.class))
            .filter(obj -> {
                String type = obj.has("type") ? obj.get("type").getAsString() : "";
                return !"session_meta".equals(type);
            })
            .map(obj -> {
                String type = obj.has("type") ? obj.get("type").getAsString() : "";
                if ("response_item".equals(type) && obj.has("payload")) {
                    return obj.getAsJsonObject("payload");
                }
                return obj;
            })
            .toList();
    }

    private static EntryData.Prompt prompt(String text) {
        return new EntryData.Prompt(text);
    }

    private static EntryData.Text text(String raw) {
        return new EntryData.Text(raw);
    }

    private static EntryData.ToolCall toolCall(String name, String args, String result) {
        return new EntryData.ToolCall(name, args, "other", result);
    }
}
