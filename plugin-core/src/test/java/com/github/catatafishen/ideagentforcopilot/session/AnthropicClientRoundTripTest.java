package com.github.catatafishen.ideagentforcopilot.session;

import com.github.catatafishen.ideagentforcopilot.session.exporters.AnthropicClientExporter;
import com.github.catatafishen.ideagentforcopilot.session.importers.AnthropicClientImporter;
import com.github.catatafishen.ideagentforcopilot.session.v2.SessionMessage;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AnthropicClientImporter} and {@link AnthropicClientExporter}.
 * Validates import, export, and round-trip conversion between the Anthropic Messages
 * API JSONL format (used by Claude CLI, Kiro, and Junie) and the v2 {@link SessionMessage} model.
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

        List<SessionMessage> messages = importJsonl(jsonl);
        assertEquals(2, messages.size());

        assertEquals("user", messages.get(0).role);
        assertEquals("Hello", extractText(messages.get(0)));

        assertEquals("assistant", messages.get(1).role);
        assertEquals("Hi there!", extractText(messages.get(1)));
    }

    @Test
    void importWithToolUseAndResult() throws IOException {
        String jsonl = """
            {"role":"user","content":[{"type":"text","text":"Read a file"}]}
            {"role":"assistant","content":[{"type":"text","text":"I will read it."},{"type":"tool_use","id":"tu1","name":"read_file","input":{"path":"/test.txt"}}]}
            {"role":"user","content":[{"type":"tool_result","tool_use_id":"tu1","content":"file contents"}]}
            {"role":"assistant","content":[{"type":"text","text":"The file says: file contents"}]}
            """;

        List<SessionMessage> messages = importJsonl(jsonl);
        // The tool_result-only user message should be skipped
        assertEquals(3, messages.size());

        assertEquals("user", messages.get(0).role);
        assertEquals("Read a file", extractText(messages.get(0)));

        SessionMessage assistant1 = messages.get(1);
        assertEquals("assistant", assistant1.role);
        assertTrue(assistant1.parts.size() >= 2, "Should have text + tool parts");

        boolean foundToolResult = false;
        for (JsonObject part : assistant1.parts) {
            if ("tool-invocation".equals(part.get("type").getAsString())) {
                JsonObject inv = part.getAsJsonObject("toolInvocation");
                assertEquals("result", inv.get("state").getAsString());
                assertEquals("tu1", inv.get("toolCallId").getAsString());
                assertEquals("read_file", inv.get("toolName").getAsString());
                assertEquals("file contents", inv.get("result").getAsString());
                foundToolResult = true;
            }
        }
        assertTrue(foundToolResult, "First assistant message should have resolved tool result");
    }

    @Test
    void importSkipsToolResultOnlyUserMessages() throws IOException {
        String jsonl = """
            {"role":"user","content":[{"type":"text","text":"Hi"}]}
            {"role":"assistant","content":[{"type":"tool_use","id":"tu1","name":"ls","input":{}}]}
            {"role":"user","content":[{"type":"tool_result","tool_use_id":"tu1","content":"dir listing"}]}
            {"role":"assistant","content":[{"type":"text","text":"Done."}]}
            """;

        List<SessionMessage> messages = importJsonl(jsonl);
        // user("Hi"), assistant(tool_use), assistant("Done.") — tool_result user message skipped
        assertEquals(3, messages.size());
        assertEquals("user", messages.get(0).role);
        assertEquals("assistant", messages.get(1).role);
        assertEquals("assistant", messages.get(2).role);
    }

    @Test
    void importToolResultWithArrayContent() throws IOException {
        String jsonl = """
            {"role":"user","content":[{"type":"text","text":"Read files"}]}
            {"role":"assistant","content":[{"type":"tool_use","id":"tu1","name":"read","input":{}}]}
            {"role":"user","content":[{"type":"tool_result","tool_use_id":"tu1","content":[{"text":"line1"},{"text":"line2"}]}]}
            """;

        List<SessionMessage> messages = importJsonl(jsonl);
        assertEquals(2, messages.size());

        SessionMessage assistant = messages.get(1);
        for (JsonObject part : assistant.parts) {
            if ("tool-invocation".equals(part.get("type").getAsString())) {
                JsonObject inv = part.getAsJsonObject("toolInvocation");
                assertEquals("line1\nline2", inv.get("result").getAsString());
            }
        }
    }

    @Test
    void importEmptyFileReturnsEmptyList() throws IOException {
        assertEquals(0, importJsonl("").size());
    }

    // ── Export tests ────────────────────────────────────────────────

    @Test
    void exportProducesAnthropicMessagesFormat() throws IOException {
        List<SessionMessage> messages = List.of(
            userMessage("Hello"),
            assistantMessage("Hi!")
        );

        Path target = tempDir.resolve("exported.jsonl");
        AnthropicClientExporter.exportToFile(messages, target);

        String content = Files.readString(target, StandardCharsets.UTF_8);
        assertTrue(content.contains("\"role\":\"user\""));
        assertTrue(content.contains("\"role\":\"assistant\""));
        assertTrue(content.contains("Hello"));
        assertTrue(content.contains("Hi!"));
    }

    @Test
    void exportToolInvocationsProduceToolUseAndToolResult() throws IOException {
        JsonObject toolPart = toolInvocationPart("tc1", "read_file", "{\"path\":\"/a\"}", "data");
        SessionMessage assistant = new SessionMessage(
            "a1", "assistant", List.of(toolPart), System.currentTimeMillis(), null, null);

        Path target = tempDir.resolve("exported-tools.jsonl");
        AnthropicClientExporter.exportToFile(List.of(userMessage("read"), assistant), target);

        String content = Files.readString(target, StandardCharsets.UTF_8);
        assertTrue(content.contains("\"type\":\"tool_use\""));
        assertTrue(content.contains("\"type\":\"tool_result\""));
        assertTrue(content.contains("\"tool_use_id\":\"tc1\""));
    }

    @Test
    void exportSanitizesLongToolNames() throws IOException {
        String longName = "git add " + "plugin-core/src/main/java/com/example/".repeat(10) + "Foo.java";
        assertTrue(longName.length() > 200, "Test precondition: raw name should exceed 200 chars");

        JsonObject toolPart = toolInvocationPart("tc1", longName, "{}", "ok");
        SessionMessage assistant = new SessionMessage(
            "a1", "assistant", List.of(toolPart), System.currentTimeMillis(), null, null);

        Path target = tempDir.resolve("exported-long-tool-name.jsonl");
        AnthropicClientExporter.exportToFile(List.of(userMessage("commit"), assistant), target);

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
        JsonObject reasoningPart = new JsonObject();
        reasoningPart.addProperty("type", "reasoning");
        reasoningPart.addProperty("text", "Thinking...");

        JsonObject textPart = textPart("Answer");

        SessionMessage assistant = new SessionMessage(
            "a1", "assistant", List.of(reasoningPart, textPart),
            System.currentTimeMillis(), null, null);

        Path target = tempDir.resolve("exported-reasoning.jsonl");
        AnthropicClientExporter.exportToFile(List.of(userMessage("Q"), assistant), target);

        String content = Files.readString(target, StandardCharsets.UTF_8);
        assertFalse(content.contains("Thinking..."), "Reasoning should not appear in Anthropic export");
        assertTrue(content.contains("Answer"));
    }

    // ── Round-trip tests ────────────────────────────────────────────

    @Test
    void roundTripPreservesTextConversation() throws IOException {
        List<SessionMessage> original = List.of(
            userMessage("What is Rust?"),
            assistantMessage("A systems programming language.")
        );

        Path file = tempDir.resolve("roundtrip.jsonl");
        AnthropicClientExporter.exportToFile(original, file);
        List<SessionMessage> imported = AnthropicClientImporter.importFile(file);

        assertEquals(2, imported.size());
        assertEquals("What is Rust?", extractText(imported.get(0)));
        assertEquals("A systems programming language.", extractText(imported.get(1)));
    }

    @Test
    void roundTripPreservesToolInvocations() throws IOException {
        JsonObject textPart = textPart("Reading file");
        JsonObject toolPart = toolInvocationPart("tc1", "read_file", "{\"path\":\"/a.txt\"}", "hello world");

        SessionMessage assistant = new SessionMessage(
            "a1", "assistant", List.of(textPart, toolPart),
            System.currentTimeMillis(), null, null);

        List<SessionMessage> original = List.of(userMessage("Read /a.txt"), assistant);

        Path file = tempDir.resolve("roundtrip-tools.jsonl");
        AnthropicClientExporter.exportToFile(original, file);
        List<SessionMessage> imported = AnthropicClientImporter.importFile(file);

        assertEquals(2, imported.size());
        SessionMessage importedAssistant = imported.get(1);

        boolean foundTool = false;
        for (JsonObject part : importedAssistant.parts) {
            if ("tool-invocation".equals(part.get("type").getAsString())) {
                foundTool = true;
                JsonObject inv = part.getAsJsonObject("toolInvocation");
                assertEquals("result", inv.get("state").getAsString());
                assertEquals("read_file", inv.get("toolName").getAsString());
                assertEquals("hello world", inv.get("result").getAsString());
            }
        }
        assertTrue(foundTool);
    }

    @Test
    void roundTripMultipleTurns() throws IOException {
        List<SessionMessage> original = List.of(
            userMessage("Question 1"),
            assistantMessage("Answer 1"),
            userMessage("Question 2"),
            assistantMessage("Answer 2")
        );

        Path file = tempDir.resolve("roundtrip-multi.jsonl");
        AnthropicClientExporter.exportToFile(original, file);
        List<SessionMessage> imported = AnthropicClientImporter.importFile(file);

        assertEquals(4, imported.size());
        assertEquals("Question 1", extractText(imported.get(0)));
        assertEquals("Answer 1", extractText(imported.get(1)));
        assertEquals("Question 2", extractText(imported.get(2)));
        assertEquals("Answer 2", extractText(imported.get(3)));
    }

    // ── Helper methods ──────────────────────────────────────────────

    // ── Format compliance tests ────────────────────────────────────

    /**
     * Validates that multiple tool results from one assistant turn are
     * consolidated into a single user message (Anthropic Messages API spec).
     */
    @Test
    void exportConsolidatesToolResultsIntoSingleUserMessage() throws IOException {
        JsonObject tool1 = toolInvocationPart("tc1", "read_file", "{\"path\":\"/a\"}", "contents A");
        JsonObject tool2 = toolInvocationPart("tc2", "search", "{\"q\":\"test\"}", "found it");
        JsonObject textPart = textPart("I'll read the file and search.");

        SessionMessage assistant = new SessionMessage(
            "a1", "assistant", List.of(textPart, tool1, tool2),
            System.currentTimeMillis(), null, null);

        Path target = tempDir.resolve("consolidated.jsonl");
        AnthropicClientExporter.exportToFile(List.of(userMessage("Do both"), assistant), target);

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
        assertEquals("tc1", block1.get("tool_use_id").getAsString());
        assertEquals("tc2", block2.get("tool_use_id").getAsString());
    }

    @Test
    void exportMatchesClaudeCliNativeStructure() throws IOException {
        // Build a multi-turn conversation with tool calls (matching native Claude pattern)
        JsonObject tool1 = toolInvocationPart("tu1", "read_file", "{\"path\":\"/a.txt\"}", "file data");
        JsonObject tool2 = toolInvocationPart("tu2", "search_text", "{\"query\":\"foo\"}", "3 matches");

        List<SessionMessage> messages = List.of(
            userMessage("Read a.txt and search for foo"),
            new SessionMessage("a1", "assistant",
                List.of(textPart("I'll do both."), tool1, tool2),
                System.currentTimeMillis(), null, null),
            userMessage("Now commit"),
            assistantMessage("Done.")
        );

        Path target = tempDir.resolve("native-check.jsonl");
        AnthropicClientExporter.exportToFile(messages, target);

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
        assertEquals("tu1", toolUse1.get("id").getAsString());
        assertEquals("read_file", toolUse1.get("name").getAsString());
        assertTrue(toolUse1.has("input"), "tool_use must have 'input' field");

        // Verify merged user message has tool_result blocks + text
        var mergedUserContent = exported.get(2).getAsJsonArray("content");
        assertEquals(3, mergedUserContent.size());
        assertEquals("tool_result", mergedUserContent.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("tu1", mergedUserContent.get(0).getAsJsonObject().get("tool_use_id").getAsString());
        assertEquals("tool_result", mergedUserContent.get(1).getAsJsonObject().get("type").getAsString());
        assertEquals("tu2", mergedUserContent.get(1).getAsJsonObject().get("tool_use_id").getAsString());
        assertEquals("text", mergedUserContent.get(2).getAsJsonObject().get("type").getAsString());
        assertEquals("Now commit", mergedUserContent.get(2).getAsJsonObject().get("text").getAsString());
    }

    /**
     * Verifies that importing a native Claude CLI session with split tool_result
     * user messages (one per tool) correctly merges them into the assistant message.
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

        List<SessionMessage> messages = importJsonl(jsonl);
        // Native sessions start with assistant (no initial user message in file)
        assertEquals(2, messages.size());

        SessionMessage first = messages.getFirst();
        assertEquals("assistant", first.role);

        // Should have text + 2 tool invocations with results merged
        int toolCount = 0;
        for (JsonObject part : first.parts) {
            if ("tool-invocation".equals(part.get("type").getAsString())) {
                toolCount++;
                JsonObject inv = part.getAsJsonObject("toolInvocation");
                assertEquals("result", inv.get("state").getAsString());
            }
        }
        assertEquals(2, toolCount, "Both tool results should be merged into assistant message");
    }

    /**
     * Round-trip with multiple tool calls preserves all tool results.
     */
    @Test
    void roundTripMultipleToolCalls() throws IOException {
        JsonObject tool1 = toolInvocationPart("tc1", "read", "{}", "data1");
        JsonObject tool2 = toolInvocationPart("tc2", "write", "{}", "ok");
        JsonObject tool3 = toolInvocationPart("tc3", "run", "{}", "output");

        List<SessionMessage> original = List.of(
            userMessage("Do 3 things"),
            new SessionMessage("a1", "assistant",
                List.of(textPart("Doing all three."), tool1, tool2, tool3),
                System.currentTimeMillis(), null, null),
            userMessage("Thanks"),
            assistantMessage("You're welcome.")
        );

        Path file = tempDir.resolve("roundtrip-multi-tools.jsonl");
        AnthropicClientExporter.exportToFile(original, file);
        List<SessionMessage> imported = AnthropicClientImporter.importFile(file);

        assertEquals(4, imported.size());

        // Verify assistant message has all 3 tool invocations with results
        SessionMessage assistant = imported.get(1);
        int toolCount = 0;
        for (JsonObject part : assistant.parts) {
            if ("tool-invocation".equals(part.get("type").getAsString())) {
                toolCount++;
                JsonObject inv = part.getAsJsonObject("toolInvocation");
                assertEquals("result", inv.get("state").getAsString());
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
        JsonObject tool1 = toolInvocationPart("tc1", "read_file", "{\"path\":\"/a\"}", "data A");
        JsonObject tool2 = toolInvocationPart("tc2", "search", "{\"q\":\"test\"}", "found it");

        SessionMessage assistant = new SessionMessage(
            "a1", "assistant",
            List.of(tool1, textPart("Found the file."), tool2, textPart("All done.")),
            System.currentTimeMillis(), null, null);

        Path target = tempDir.resolve("sequential-tools.jsonl");
        AnthropicClientExporter.exportToFile(List.of(userMessage("Do both"), assistant), target);

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
        assertEquals("tc1", turn1Content.get(0).getAsJsonObject().get("id").getAsString());

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
        JsonObject tool1 = toolInvocationPart("tc1", "read_file", "{\"path\":\"/a\"}", "data A");
        JsonObject tool2 = toolInvocationPart("tc2", "read_file", "{\"path\":\"/b\"}", "data B");
        JsonObject tool3 = toolInvocationPart("tc3", "search", "{\"q\":\"test\"}", "found");

        SessionMessage assistant = new SessionMessage(
            "a1", "assistant",
            List.of(textPart("Reading files."), tool1, tool2, tool3),
            System.currentTimeMillis(), null, null);

        Path target = tempDir.resolve("parallel-tools.jsonl");
        AnthropicClientExporter.exportToFile(List.of(userMessage("Read both"), assistant), target);

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
        JsonObject emptyTextPart = new JsonObject();
        emptyTextPart.addProperty("type", "text");
        emptyTextPart.addProperty("text", "");

        JsonObject realTextPart = textPart("Real content");
        JsonObject toolPart = toolInvocationPart("tc1", "read_file", "{\"path\":\"/a\"}", "data");

        SessionMessage assistant = new SessionMessage(
            "a1", "assistant", List.of(emptyTextPart, realTextPart, toolPart),
            System.currentTimeMillis(), null, null);

        Path target = tempDir.resolve("empty-text.jsonl");
        AnthropicClientExporter.exportToFile(List.of(userMessage("Q"), assistant), target);

        String content = Files.readString(target, StandardCharsets.UTF_8);
        // The empty text block must NOT appear — Anthropic API rejects it
        assertFalse(content.contains("\"text\":\"\""),
            "Empty text blocks must be filtered out to avoid Anthropic API 400 errors");
        assertTrue(content.contains("Real content"));
        assertTrue(content.contains("\"type\":\"tool_use\""));
    }

    @Test
    void exportFiltersEmptyTextBlocksFromUserMessages() throws IOException {
        JsonObject emptyTextPart = new JsonObject();
        emptyTextPart.addProperty("type", "text");
        emptyTextPart.addProperty("text", "");

        JsonObject realTextPart = textPart("User question");

        SessionMessage user = new SessionMessage(
            "u1", "user", List.of(emptyTextPart, realTextPart),
            System.currentTimeMillis(), null, null);

        Path target = tempDir.resolve("empty-user-text.jsonl");
        AnthropicClientExporter.exportToFile(List.of(user, assistantMessage("Answer")), target);

        String content = Files.readString(target, StandardCharsets.UTF_8);
        assertFalse(content.contains("\"text\":\"\""),
            "Empty text blocks must be filtered out from user messages too");
        assertTrue(content.contains("User question"));
    }

    @Test
    void exportToolOnlyAssistantMessageOmitsEmptyText() throws IOException {
        // Simulates the EntryDataConverter bug: tool-only assistant messages get an empty
        // EntryData.Text appended for UI rendering, which serializes as {"type":"text","text":""}
        JsonObject emptyTextPart = new JsonObject();
        emptyTextPart.addProperty("type", "text");
        emptyTextPart.addProperty("text", "");

        JsonObject toolPart = toolInvocationPart("tc1", "search_text", "{\"query\":\"foo\"}", "found");

        SessionMessage assistant = new SessionMessage(
            "a1", "assistant", List.of(emptyTextPart, toolPart),
            System.currentTimeMillis(), null, null);

        Path target = tempDir.resolve("tool-only.jsonl");
        AnthropicClientExporter.exportToFile(List.of(userMessage("search"), assistant), target);

        String content = Files.readString(target, StandardCharsets.UTF_8);
        assertFalse(content.contains("\"text\":\"\""),
            "Tool-only assistant messages must not have empty text blocks");
        assertTrue(content.contains("\"type\":\"tool_use\""));
    }

    private List<SessionMessage> importJsonl(String jsonl) throws IOException {
        Path file = tempDir.resolve("test.jsonl");
        Files.writeString(file, jsonl, StandardCharsets.UTF_8);
        return AnthropicClientImporter.importFile(file);
    }

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

    private static JsonObject toolInvocationPart(String callId, String toolName, String args, String result) {
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

    private static String extractText(SessionMessage msg) {
        StringBuilder sb = new StringBuilder();
        for (JsonObject part : msg.parts) {
            if ("text".equals(part.get("type").getAsString())) {
                sb.append(part.get("text").getAsString());
            }
        }
        return sb.toString();
    }
}
