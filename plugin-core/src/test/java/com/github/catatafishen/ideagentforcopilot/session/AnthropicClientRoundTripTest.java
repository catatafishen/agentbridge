package com.github.catatafishen.ideagentforcopilot.session;

import com.github.catatafishen.ideagentforcopilot.session.exporters.AnthropicClientExporter;
import com.github.catatafishen.ideagentforcopilot.session.importers.AnthropicClientImporter;
import com.github.catatafishen.ideagentforcopilot.ui.EntryData;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AnthropicClientImporter} and {@link AnthropicClientExporter}.
 * Validates import, export, and round-trip conversion between the Anthropic Messages
 * API JSONL format (used by Claude CLI, Kiro, and Junie) and the {@link EntryData} model.
 */
class AnthropicClientRoundTripTest {

    @TempDir
    Path tempDir;

    // ── Import tests ────────────────────────────────────────────────

    @Test
    void importBasicConversation() throws IOException {
        String jsonl = """
            {"role":"user","content":[{"type":"text","text":"Hello"}]}
            {"role":"assistant","content":[{"type":"text","text":"Hi there!"}]}
            """;

        List<EntryData> entries = importJsonl(jsonl);
        assertEquals(2, entries.size());

        assertTrue(entries.get(0) instanceof EntryData.Prompt);
        assertEquals("Hello", ((EntryData.Prompt) entries.get(0)).getText());

        assertTrue(entries.get(1) instanceof EntryData.Text);
        assertEquals("Hi there!", ((EntryData.Text) entries.get(1)).getRaw().toString());
    }

    @Test
    void importWithToolUseAndResult() throws IOException {
        String jsonl = """
            {"role":"user","content":[{"type":"text","text":"Read a file"}]}
            {"role":"assistant","content":[{"type":"text","text":"I will read it."},{"type":"tool_use","id":"tu1","name":"read_file","input":{"path":"/test.txt"}}]}
            {"role":"user","content":[{"type":"tool_result","tool_use_id":"tu1","content":"file contents"}]}
            {"role":"assistant","content":[{"type":"text","text":"The file says: file contents"}]}
            """;

        List<EntryData> entries = importJsonl(jsonl);
        // user text → Prompt, assistant text → Text, tool_use → ToolCall, assistant text → Text
        // The tool_result-only user message is skipped
        assertEquals(4, entries.size());

        assertTrue(entries.get(0) instanceof EntryData.Prompt);
        assertEquals("Read a file", ((EntryData.Prompt) entries.get(0)).getText());

        assertTrue(entries.get(1) instanceof EntryData.Text);
        assertEquals("I will read it.", ((EntryData.Text) entries.get(1)).getRaw().toString());

        assertTrue(entries.get(2) instanceof EntryData.ToolCall);
        EntryData.ToolCall tc = (EntryData.ToolCall) entries.get(2);
        assertEquals("read_file", tc.getTitle());
        assertEquals("file contents", tc.getResult());

        assertTrue(entries.get(3) instanceof EntryData.Text);
        assertEquals("The file says: file contents", ((EntryData.Text) entries.get(3)).getRaw().toString());
    }

    @Test
    void importSkipsToolResultOnlyUserMessages() throws IOException {
        String jsonl = """
            {"role":"user","content":[{"type":"text","text":"Hi"}]}
            {"role":"assistant","content":[{"type":"tool_use","id":"tu1","name":"ls","input":{}}]}
            {"role":"user","content":[{"type":"tool_result","tool_use_id":"tu1","content":"dir listing"}]}
            {"role":"assistant","content":[{"type":"text","text":"Done."}]}
            """;

        List<EntryData> entries = importJsonl(jsonl);
        // Prompt("Hi"), ToolCall(ls), Text("Done.") — tool_result user message skipped
        assertEquals(3, entries.size());
        assertTrue(entries.get(0) instanceof EntryData.Prompt);
        assertTrue(entries.get(1) instanceof EntryData.ToolCall);
        assertTrue(entries.get(2) instanceof EntryData.Text);
    }

    @Test
    void importToolResultWithArrayContent() throws IOException {
        String jsonl = """
            {"role":"user","content":[{"type":"text","text":"Read files"}]}
            {"role":"assistant","content":[{"type":"tool_use","id":"tu1","name":"read","input":{}}]}
            {"role":"user","content":[{"type":"tool_result","tool_use_id":"tu1","content":[{"text":"line1"},{"text":"line2"}]}]}
            """;

        List<EntryData> entries = importJsonl(jsonl);
        assertEquals(2, entries.size());

        assertTrue(entries.get(1) instanceof EntryData.ToolCall);
        assertEquals("line1\nline2", ((EntryData.ToolCall) entries.get(1)).getResult());
    }

    @Test
    void importEmptyFileReturnsEmptyList() throws IOException {
        assertEquals(0, importJsonl("").size());
    }

    // ── Export tests ────────────────────────────────────────────────

    @Test
    void exportProducesAnthropicMessagesFormat() throws IOException {
        List<EntryData> entries = List.of(
            userPrompt("Hello"),
            assistantText("Hi!")
        );

        Path target = tempDir.resolve("exported.jsonl");
        AnthropicClientExporter.exportToFile(entries, target);

        String content = Files.readString(target, StandardCharsets.UTF_8);
        assertTrue(content.contains("\"role\":\"user\""));
        assertTrue(content.contains("\"role\":\"assistant\""));
        assertTrue(content.contains("Hello"));
        assertTrue(content.contains("Hi!"));
    }

    @Test
    void exportToolInvocationsProduceToolUseAndToolResult() throws IOException {
        List<EntryData> entries = List.of(
            userPrompt("read"),
            toolCall("read_file", "{\"path\":\"/a\"}", "data")
        );

        Path target = tempDir.resolve("exported-tools.jsonl");
        AnthropicClientExporter.exportToFile(entries, target);

        String content = Files.readString(target, StandardCharsets.UTF_8);
        assertTrue(content.contains("\"type\":\"tool_use\""));
        assertTrue(content.contains("\"type\":\"tool_result\""));
        assertTrue(content.contains("\"tool_use_id\":"));
    }

    @Test
    void exportSanitizesLongToolNames() throws IOException {
        String longName = "git add " + "plugin-core/src/main/java/com/example/".repeat(10) + "Foo.java";
        assertTrue(longName.length() > 200, "Test precondition: raw name should exceed 200 chars");

        List<EntryData> entries = List.of(
            userPrompt("commit"),
            toolCall(longName, "{}", "ok")
        );

        Path target = tempDir.resolve("exported-long-tool-name.jsonl");
        AnthropicClientExporter.exportToFile(entries, target);

        String content = Files.readString(target, StandardCharsets.UTF_8);
        // Parse the assistant message line (first non-user line) and check tool_use name length
        for (String line : content.split("\n")) {
            JsonObject msg = JsonParser.parseString(line).getAsJsonObject();
            if (msg.has("content") && msg.get("content").isJsonArray()) {
                for (var block : msg.getAsJsonArray("content")) {
                    JsonObject b = block.getAsJsonObject();
                    if ("tool_use".equals(b.get("type").getAsString())) {
                        String exportedName = b.get("name").getAsString();
                        assertTrue(exportedName.length() <= 200,
                            "Exported tool_use name should be at most 200 chars, was " + exportedName.length());
                        assertFalse(exportedName.contains(" "), "Name should not contain spaces");
                        assertFalse(exportedName.contains("/"), "Name should not contain slashes");
                    }
                }
            }
        }
    }

    @Test
    void exportSkipsReasoningParts() throws IOException {
        List<EntryData> entries = List.of(
            userPrompt("Q"),
            new EntryData.Thinking(new StringBuilder("Thinking..."), Instant.now().toString(), "", "", ""),
            assistantText("Answer")
        );

        Path target = tempDir.resolve("exported-reasoning.jsonl");
        AnthropicClientExporter.exportToFile(entries, target);

        String content = Files.readString(target, StandardCharsets.UTF_8);
        assertFalse(content.contains("Thinking..."), "Reasoning should not appear in Anthropic export");
        assertTrue(content.contains("Answer"));
    }

    // ── Round-trip tests ────────────────────────────────────────────

    @Test
    void roundTripPreservesTextConversation() throws IOException {
        List<EntryData> original = List.of(
            userPrompt("What is Rust?"),
            assistantText("A systems programming language.")
        );

        Path file = tempDir.resolve("roundtrip.jsonl");
        AnthropicClientExporter.exportToFile(original, file);
        List<EntryData> imported = AnthropicClientImporter.importFile(file);

        assertEquals(2, imported.size());
        assertEquals("What is Rust?", ((EntryData.Prompt) imported.get(0)).getText());
        assertEquals("A systems programming language.", ((EntryData.Text) imported.get(1)).getRaw().toString());
    }

    @Test
    void roundTripPreservesToolInvocations() throws IOException {
        List<EntryData> original = List.of(
            userPrompt("Read /a.txt"),
            assistantText("Reading file"),
            toolCall("read_file", "{\"path\":\"/a.txt\"}", "hello world")
        );

        Path file = tempDir.resolve("roundtrip-tools.jsonl");
        AnthropicClientExporter.exportToFile(original, file);
        List<EntryData> imported = AnthropicClientImporter.importFile(file);

        assertEquals(3, imported.size());

        assertTrue(imported.get(0) instanceof EntryData.Prompt);
        assertTrue(imported.get(1) instanceof EntryData.Text);
        assertEquals("Reading file", ((EntryData.Text) imported.get(1)).getRaw().toString());

        assertTrue(imported.get(2) instanceof EntryData.ToolCall);
        EntryData.ToolCall tc = (EntryData.ToolCall) imported.get(2);
        assertEquals("read_file", tc.getTitle());
        assertEquals("hello world", tc.getResult());
    }

    @Test
    void roundTripMultipleTurns() throws IOException {
        List<EntryData> original = List.of(
            userPrompt("Question 1"),
            assistantText("Answer 1"),
            userPrompt("Question 2"),
            assistantText("Answer 2")
        );

        Path file = tempDir.resolve("roundtrip-multi.jsonl");
        AnthropicClientExporter.exportToFile(original, file);
        List<EntryData> imported = AnthropicClientImporter.importFile(file);

        assertEquals(4, imported.size());
        assertEquals("Question 1", ((EntryData.Prompt) imported.get(0)).getText());
        assertEquals("Answer 1", ((EntryData.Text) imported.get(1)).getRaw().toString());
        assertEquals("Question 2", ((EntryData.Prompt) imported.get(2)).getText());
        assertEquals("Answer 2", ((EntryData.Text) imported.get(3)).getRaw().toString());
    }

    // ── Helper methods ──────────────────────────────────────────────

    // ── Format compliance tests ────────────────────────────────────

    /**
     * Validates that multiple tool results from one assistant turn are
     * consolidated into a single user message (Anthropic Messages API spec).
     */
    @Test
    void exportConsolidatesToolResultsIntoSingleUserMessage() throws IOException {
        List<EntryData> entries = List.of(
            userPrompt("Do both"),
            assistantText("I'll read the file and search."),
            toolCall("read_file", "{\"path\":\"/a\"}", "contents A"),
            toolCall("search", "{\"q\":\"test\"}", "found it")
        );

        Path target = tempDir.resolve("consolidated.jsonl");
        AnthropicClientExporter.exportToFile(entries, target);

        List<String> lines = Files.readAllLines(target, StandardCharsets.UTF_8).stream()
            .filter(l -> !l.isBlank()).toList();

        // Should be exactly 3 lines: user, assistant, tool-results
        assertEquals(3, lines.size(), "Expected user + assistant + one consolidated tool-result user message");

        // Verify the third line is a single user message with both tool_results
        JsonObject toolResultMsg = JsonParser.parseString(lines.get(2)).getAsJsonObject();
        assertEquals("user", toolResultMsg.get("role").getAsString());
        var contentArray = toolResultMsg.getAsJsonArray("content");
        assertEquals(2, contentArray.size(), "Both tool results should be in one user message");

        var block1 = contentArray.get(0).getAsJsonObject();
        var block2 = contentArray.get(1).getAsJsonObject();
        assertEquals("tool_result", block1.get("type").getAsString());
        assertEquals("tool_result", block2.get("type").getAsString());
        assertFalse(block1.get("tool_use_id").getAsString().isEmpty());
        assertFalse(block2.get("tool_use_id").getAsString().isEmpty());
    }

    @Test
    void exportMatchesClaudeCliNativeStructure() throws IOException {
        List<EntryData> entries = List.of(
            userPrompt("Read a.txt and search for foo"),
            assistantText("I'll do both."),
            toolCall("read_file", "{\"path\":\"/a.txt\"}", "file data"),
            toolCall("search_text", "{\"query\":\"foo\"}", "3 matches"),
            userPrompt("Now commit"),
            assistantText("Done.")
        );

        Path target = tempDir.resolve("native-check.jsonl");
        AnthropicClientExporter.exportToFile(entries, target);

        List<JsonObject> exported = new ArrayList<>();
        for (String line : Files.readAllLines(target, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) exported.add(JsonParser.parseString(line).getAsJsonObject());
        }

        // mergeConsecutiveSameRole merges the tool_result user message and "Now commit" user message
        // into a single user message: user, assistant(text+tool_use+tool_use),
        // user(tool_result+tool_result+text), assistant(text)
        assertEquals(4, exported.size());

        assertEquals("user", exported.get(0).get("role").getAsString());
        assertEquals("assistant", exported.get(1).get("role").getAsString());
        assertEquals("user", exported.get(2).get("role").getAsString());
        assertEquals("assistant", exported.get(3).get("role").getAsString());

        // Verify assistant message has text + 2 tool_use blocks
        var assistantContent = exported.get(1).getAsJsonArray("content");
        assertEquals(3, assistantContent.size());
        assertEquals("text", assistantContent.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("tool_use", assistantContent.get(1).getAsJsonObject().get("type").getAsString());
        assertEquals("tool_use", assistantContent.get(2).getAsJsonObject().get("type").getAsString());

        // Verify tool_use blocks have correct fields: id, name, input
        var toolUse1 = assistantContent.get(1).getAsJsonObject();
        assertFalse(toolUse1.get("id").getAsString().isEmpty());
        assertEquals("read_file", toolUse1.get("name").getAsString());
        assertTrue(toolUse1.has("input"), "tool_use must have 'input' field");

        // Verify merged user message has tool_result blocks + text
        var mergedUserContent = exported.get(2).getAsJsonArray("content");
        assertEquals(3, mergedUserContent.size());
        assertEquals("tool_result", mergedUserContent.get(0).getAsJsonObject().get("type").getAsString());
        assertFalse(mergedUserContent.get(0).getAsJsonObject().get("tool_use_id").getAsString().isEmpty());
        assertEquals("tool_result", mergedUserContent.get(1).getAsJsonObject().get("type").getAsString());
        assertFalse(mergedUserContent.get(1).getAsJsonObject().get("tool_use_id").getAsString().isEmpty());
        assertEquals("text", mergedUserContent.get(2).getAsJsonObject().get("type").getAsString());
        assertEquals("Now commit", mergedUserContent.get(2).getAsJsonObject().get("text").getAsString());
    }

    /**
     * Verifies that importing a native Claude CLI session with split tool_result
     * user messages (one per tool) correctly merges them into the assistant entries.
     */
    @Test
    void importNativeClaudeSessionWithSplitToolResults() throws IOException {
        // Native Claude CLI format: separate user messages per tool_result
        String jsonl = """
            {"role":"assistant","content":[{"type":"text","text":"I'll read both."},{"type":"tool_use","id":"tu1","name":"read_file","input":{"path":"/a"}},{"type":"tool_use","id":"tu2","name":"read_file","input":{"path":"/b"}}]}
            {"role":"user","content":[{"type":"tool_result","tool_use_id":"tu1","content":"content A"}]}
            {"role":"user","content":[{"type":"tool_result","tool_use_id":"tu2","content":"content B"}]}
            {"role":"assistant","content":[{"type":"text","text":"Both files read."}]}
            """;

        List<EntryData> entries = importJsonl(jsonl);
        // Text("I'll read both."), ToolCall(tu1), ToolCall(tu2), Text("Both files read.")
        assertEquals(4, entries.size());

        assertTrue(entries.get(0) instanceof EntryData.Text);
        assertEquals("I'll read both.", ((EntryData.Text) entries.get(0)).getRaw().toString());

        assertTrue(entries.get(1) instanceof EntryData.ToolCall);
        assertEquals("content A", ((EntryData.ToolCall) entries.get(1)).getResult());

        assertTrue(entries.get(2) instanceof EntryData.ToolCall);
        assertEquals("content B", ((EntryData.ToolCall) entries.get(2)).getResult());

        assertTrue(entries.get(3) instanceof EntryData.Text);
        assertEquals("Both files read.", ((EntryData.Text) entries.get(3)).getRaw().toString());
    }

    /**
     * Round-trip with multiple tool calls preserves all tool results.
     */
    @Test
    void roundTripMultipleToolCalls() throws IOException {
        List<EntryData> original = List.of(
            userPrompt("Do 3 things"),
            assistantText("Doing all three."),
            toolCall("read", "{}", "data1"),
            toolCall("write", "{}", "ok"),
            toolCall("run", "{}", "output"),
            userPrompt("Thanks"),
            assistantText("You're welcome.")
        );

        Path file = tempDir.resolve("roundtrip-multi-tools.jsonl");
        AnthropicClientExporter.exportToFile(original, file);
        List<EntryData> imported = AnthropicClientImporter.importFile(file);

        // Prompt, Text, ToolCall, ToolCall, ToolCall, Prompt, Text
        assertEquals(7, imported.size());

        // Verify all 3 tool invocations with results round-tripped
        int toolCount = 0;
        for (EntryData entry : imported) {
            if (entry instanceof EntryData.ToolCall) {
                toolCount++;
                assertTrue(((EntryData.ToolCall) entry).getResult() != null,
                    "Each tool call should have a result after round-trip");
            }
        }
        assertEquals(3, toolCount, "All 3 tool results should round-trip");
    }

    /**
     * Validates that sequential tool use turns (tool → result → text → tool → result → text)
     * within a single v2 assistant message are split into separate Anthropic API turns.
     * The Anthropic API requires each tool_use turn to be a separate assistant message
     * followed by a user message with tool_results.
     */
    @Test
    void exportSplitsSequentialToolUseTurns() throws IOException {
        // Simulates sequential tool calls: tool A → text → tool B → text
        List<EntryData> entries = List.of(
            userPrompt("Do both"),
            toolCall("read_file", "{\"path\":\"/a\"}", "data A"),
            assistantText("Found the file."),
            toolCall("search", "{\"q\":\"test\"}", "found it"),
            assistantText("All done.")
        );

        Path target = tempDir.resolve("sequential-tools.jsonl");
        AnthropicClientExporter.exportToFile(entries, target);

        List<JsonObject> exported = new ArrayList<>();
        for (String line : Files.readAllLines(target, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) exported.add(JsonParser.parseString(line).getAsJsonObject());
        }

        // Should be: user, assistant(tool_use), user(tool_result),
        //            assistant(text+tool_use), user(tool_result),
        //            assistant(text)
        assertEquals(6, exported.size());

        // Turn 1: assistant with tool_use(tc1)
        var turn1Content = exported.get(1).getAsJsonArray("content");
        assertEquals(1, turn1Content.size());
        assertEquals("tool_use", turn1Content.get(0).getAsJsonObject().get("type").getAsString());
        assertFalse(turn1Content.get(0).getAsJsonObject().get("id").getAsString().isEmpty());

        // Turn 1: user with tool_result(tc1)
        var turn1Results = exported.get(2).getAsJsonArray("content");
        assertEquals(1, turn1Results.size());
        assertEquals("tool_result", turn1Results.get(0).getAsJsonObject().get("type").getAsString());

        // Turn 2: assistant with text + tool_use(tc2)
        var turn2Content = exported.get(3).getAsJsonArray("content");
        assertEquals(2, turn2Content.size());
        assertEquals("text", turn2Content.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("tool_use", turn2Content.get(1).getAsJsonObject().get("type").getAsString());

        // Turn 2: user with tool_result(tc2)
        var turn2Results = exported.get(4).getAsJsonArray("content");
        assertEquals(1, turn2Results.size());
        assertEquals("tool_result", turn2Results.get(0).getAsJsonObject().get("type").getAsString());

        // Final: assistant with text only
        var finalContent = exported.get(5).getAsJsonArray("content");
        assertEquals(1, finalContent.size());
        assertEquals("text", finalContent.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("All done.", finalContent.get(0).getAsJsonObject().get("text").getAsString());
    }

    /**
     * Verifies that parallel tool calls (multiple tool_uses without text between them)
     * stay in a single assistant message — only text between tool_uses triggers a split.
     */
    @Test
    void exportKeepsParallelToolCallsTogether() throws IOException {
        List<EntryData> entries = List.of(
            userPrompt("Read both"),
            assistantText("Reading files."),
            toolCall("read_file", "{\"path\":\"/a\"}", "data A"),
            toolCall("read_file", "{\"path\":\"/b\"}", "data B"),
            toolCall("search", "{\"q\":\"test\"}", "found")
        );

        Path target = tempDir.resolve("parallel-tools.jsonl");
        AnthropicClientExporter.exportToFile(entries, target);

        List<JsonObject> exported = new ArrayList<>();
        for (String line : Files.readAllLines(target, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) exported.add(JsonParser.parseString(line).getAsJsonObject());
        }

        // No text between tools → single turn: user, assistant(text+3*tool_use), user(3*tool_result)
        assertEquals(3, exported.size());

        var assistantContent = exported.get(1).getAsJsonArray("content");
        assertEquals(4, assistantContent.size());
        assertEquals("text", assistantContent.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("tool_use", assistantContent.get(1).getAsJsonObject().get("type").getAsString());
        assertEquals("tool_use", assistantContent.get(2).getAsJsonObject().get("type").getAsString());
        assertEquals("tool_use", assistantContent.get(3).getAsJsonObject().get("type").getAsString());

        var toolResults = exported.get(2).getAsJsonArray("content");
        assertEquals(3, toolResults.size());
    }

    @Test
    void exportFiltersEmptyTextBlocksFromAssistantMessages() throws IOException {
        List<EntryData> entries = List.of(
            userPrompt("Q"),
            assistantText(""),          // empty — exporter skips this
            assistantText("Real content"),
            toolCall("read_file", "{\"path\":\"/a\"}", "data")
        );

        Path target = tempDir.resolve("empty-text.jsonl");
        AnthropicClientExporter.exportToFile(entries, target);

        String content = Files.readString(target, StandardCharsets.UTF_8);
        // The empty text block must NOT appear — Anthropic API rejects it
        assertFalse(content.contains("\"text\":\"\""),
            "Empty text blocks must be filtered out to avoid Anthropic API 400 errors");
        assertTrue(content.contains("Real content"));
        assertTrue(content.contains("\"type\":\"tool_use\""));
    }

    @Test
    void exportFiltersEmptyTextBlocksFromUserMessages() throws IOException {
        List<EntryData> entries = List.of(
            new EntryData.Prompt("", Instant.now().toString(), null, "", ""),
            userPrompt("User question"),
            assistantText("Answer")
        );

        Path target = tempDir.resolve("empty-user-text.jsonl");
        AnthropicClientExporter.exportToFile(entries, target);

        String content = Files.readString(target, StandardCharsets.UTF_8);
        assertFalse(content.contains("\"text\":\"\""),
            "Empty text blocks must be filtered out from user messages too");
        assertTrue(content.contains("User question"));
    }

    @Test
    void exportToolOnlyAssistantMessageOmitsEmptyText() throws IOException {
        // Simulates a tool-only assistant turn preceded by an empty text entry
        List<EntryData> entries = List.of(
            userPrompt("search"),
            assistantText(""),  // empty — exporter skips
            toolCall("search_text", "{\"query\":\"foo\"}", "found")
        );

        Path target = tempDir.resolve("tool-only.jsonl");
        AnthropicClientExporter.exportToFile(entries, target);

        String content = Files.readString(target, StandardCharsets.UTF_8);
        assertFalse(content.contains("\"text\":\"\""),
            "Tool-only assistant messages must not have empty text blocks");
        assertTrue(content.contains("\"type\":\"tool_use\""));
    }

    private List<EntryData> importJsonl(String jsonl) throws IOException {
        Path file = tempDir.resolve("test.jsonl");
        Files.writeString(file, jsonl, StandardCharsets.UTF_8);
        return AnthropicClientImporter.importFile(file);
    }

    private static EntryData.Prompt userPrompt(String text) {
        return new EntryData.Prompt(text, Instant.now().toString(), null, "", "");
    }

    private static EntryData.Text assistantText(String text) {
        return new EntryData.Text(new StringBuilder(text), Instant.now().toString(), "", "", "");
    }

    private static EntryData.ToolCall toolCall(String toolName, String args, String result) {
        return new EntryData.ToolCall(
            toolName, args, "other", result,
            null, null, null, false, null, false,
            Instant.now().toString(), "", "", "");
    }

    @Test
    void sanitizeToolNameTruncatesLongNames() {
        String longName = "git add " + "a/".repeat(200) + "Foo.java";
        String sanitized = AnthropicClientExporter.sanitizeToolName(longName);
        assertTrue(sanitized.length() <= 200,
            "Sanitized name should be at most 200 chars, was " + sanitized.length());
        assertFalse(sanitized.contains(" "), "Sanitized name should not contain spaces");
    }

    @Test
    void sanitizeToolNamePreservesValidNames() {
        assertEquals("read_file", AnthropicClientExporter.sanitizeToolName("read_file"));
        assertEquals("mcp__agentbridge__read_file",
            AnthropicClientExporter.sanitizeToolName("mcp__agentbridge__read_file"));
    }

    @Test
    void sanitizeToolNameReplacesInvalidChars() {
        assertEquals("git_add_src_Foo-java",
            AnthropicClientExporter.sanitizeToolName("git add src/Foo-java"));
        assertEquals("Viewing__ChatConsolePanel_kt",
            AnthropicClientExporter.sanitizeToolName("Viewing .../ChatConsolePanel.kt"));
    }

    @Test
    void sanitizeToolNameHandlesEdgeCases() {
        assertEquals("unknown_tool", AnthropicClientExporter.sanitizeToolName(""));
        assertEquals("unknown_tool", AnthropicClientExporter.sanitizeToolName("..."));
        assertEquals("a", AnthropicClientExporter.sanitizeToolName("a"));
    }
}
