package com.github.catatafishen.ideagentforcopilot.session;

import com.github.catatafishen.ideagentforcopilot.session.exporters.CopilotClientExporter;
import com.github.catatafishen.ideagentforcopilot.session.importers.CopilotClientImporter;
import com.github.catatafishen.ideagentforcopilot.ui.EntryData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link CopilotClientImporter} and {@link CopilotClientExporter}.
 * Validates import, export, and round-trip conversion between Copilot's native
 * {@code events.jsonl} format and the {@link EntryData} model.
 */
class CopilotClientRoundTripTest {

    @TempDir
    Path tempDir;

    // ── Import tests ────────────────────────────────────────────────

    @Test
    void importBasicConversation() throws IOException {
        String events = """
            {"type":"session.start","data":{"selectedModel":"gpt-4.1"}}
            {"type":"user.message","data":{"content":"Hello"}}
            {"type":"assistant.message","data":{"content":"Hi there!","model":"gpt-4.1"}}
            {"type":"assistant.turn_end","data":{}}
            """;

        List<EntryData> entries = importEvents(events);

        assertEquals(2, entries.size());

        EntryData.Prompt prompt = assertInstanceOf(EntryData.Prompt.class, entries.get(0));
        assertEquals("Hello", prompt.getText());

        EntryData.Text text = assertInstanceOf(EntryData.Text.class, entries.get(1));
        assertEquals("Hi there!", text.getRaw().toString());
        assertEquals("gpt-4.1", text.getModel());
    }

    @Test
    void importWithReasoning() throws IOException {
        String events = """
            {"type":"session.start","data":{"selectedModel":"claude-sonnet-4"}}
            {"type":"user.message","data":{"content":"Explain X"}}
            {"type":"assistant.reasoning","data":{"content":"Let me think..."}}
            {"type":"assistant.message","data":{"content":"Here is my answer","model":"claude-sonnet-4"}}
            {"type":"assistant.turn_end","data":{}}
            """;

        List<EntryData> entries = importEvents(events);
        assertEquals(3, entries.size());

        assertInstanceOf(EntryData.Prompt.class, entries.get(0));

        EntryData.Thinking thinking = assertInstanceOf(EntryData.Thinking.class, entries.get(1));
        assertEquals("Let me think...", thinking.getRaw().toString());

        EntryData.Text text = assertInstanceOf(EntryData.Text.class, entries.get(2));
        assertEquals("Here is my answer", text.getRaw().toString());
    }

    @Test
    void importWithToolCall() throws IOException {
        String events = """
            {"type":"session.start","data":{}}
            {"type":"user.message","data":{"content":"Read a file"}}
            {"type":"assistant.message","data":{"content":"","toolRequests":[{"toolCallId":"tc1","name":"read_file","arguments":{"path":"/tmp/test.txt"}}]}}
            {"type":"tool.execution_complete","data":{"toolCallId":"tc1","result":{"content":"file contents"}}}
            {"type":"assistant.message","data":{"content":"The file contains: file contents"}}
            {"type":"assistant.turn_end","data":{}}
            """;

        List<EntryData> entries = importEvents(events);
        // Prompt + ToolCall + Text
        assertEquals(3, entries.size());

        assertInstanceOf(EntryData.Prompt.class, entries.get(0));

        EntryData.ToolCall toolCall = assertInstanceOf(EntryData.ToolCall.class, entries.get(1));
        assertEquals("read_file", toolCall.getTitle());
        assertEquals("file contents", toolCall.getResult());
        assertEquals("done", toolCall.getStatus());

        EntryData.Text text = assertInstanceOf(EntryData.Text.class, entries.get(2));
        assertEquals("The file contains: file contents", text.getRaw().toString());
    }

    @Test
    void importWithSubagent() throws IOException {
        String events = """
            {"type":"session.start","data":{}}
            {"type":"user.message","data":{"content":"Explore the codebase"}}
            {"type":"subagent.started","data":{"toolCallId":"sa1","agentName":"explore","agentDisplayName":"Code Explorer"}}
            {"type":"subagent.completed","data":{"toolCallId":"sa1"}}
            {"type":"assistant.message","data":{"content":"I explored the code."}}
            {"type":"assistant.turn_end","data":{}}
            """;

        List<EntryData> entries = importEvents(events);
        // Prompt + SubAgent + Text
        assertEquals(3, entries.size());

        assertInstanceOf(EntryData.Prompt.class, entries.get(0));

        EntryData.SubAgent subAgent = assertInstanceOf(EntryData.SubAgent.class, entries.get(1));
        assertEquals("explore", subAgent.getAgentType());
        assertEquals("Code Explorer", subAgent.getDescription());
        assertEquals("done", subAgent.getStatus());

        assertInstanceOf(EntryData.Text.class, entries.get(2));
    }

    @Test
    void importEmptyFileReturnsEmptyList() throws IOException {
        List<EntryData> entries = importEvents("");
        assertTrue(entries.isEmpty());
    }

    // ── Export tests ────────────────────────────────────────────────

    @Test
    void exportProducesValidEventsJsonl() throws IOException {
        List<EntryData> entries = List.of(
            promptEntry("What is 2+2?"),
            textEntry("4", "gpt-4.1")
        );

        Path target = tempDir.resolve("events.jsonl");
        CopilotClientExporter.exportToFile(entries, target);

        String content = Files.readString(target, StandardCharsets.UTF_8);
        assertTrue(content.contains("\"type\":\"session.start\""));
        assertTrue(content.contains("\"type\":\"user.message\""));
        assertTrue(content.contains("\"type\":\"assistant.message\""));
        assertTrue(content.contains("\"type\":\"assistant.turn_end\""));
        assertTrue(content.contains("What is 2+2?"));
        assertTrue(content.contains("\"selectedModel\":\"gpt-4.1\""));
    }

    @Test
    void exportToolInvocationProducesCallAndResult() throws IOException {
        EntryData.ToolCall toolCall = new EntryData.ToolCall(
            "read_file", "{\"path\":\"/test\"}", "other", "file data", "done",
            null, null, false, null, false, "", "", "gpt-4.1");

        List<EntryData> entries = List.of(promptEntry("read"), toolCall);

        Path target = tempDir.resolve("events.jsonl");
        CopilotClientExporter.exportToFile(entries, target);

        String content = Files.readString(target, StandardCharsets.UTF_8);
        assertTrue(content.contains("\"type\":\"tool.execution_complete\""));
        assertTrue(content.contains("\"toolCallId\":"));
        assertTrue(content.contains("file data"));
    }

    @Test
    void defaultSessionStateDirIsCorrect() {
        Path result = CopilotClientExporter.defaultSessionStateDir("/home/user/project");
        String userHome = System.getProperty("user.home");
        assertTrue(result.toString().endsWith(".copilot/session-state"));
        assertTrue(result.toString().startsWith(userHome));
    }

    // ── Round-trip tests ────────────────────────────────────────────

    @Test
    void roundTripPreservesUserText() throws IOException {
        List<EntryData> original = List.of(
            promptEntry("Hello world"),
            textEntry("Greetings!", "gpt-4.1")
        );

        Path file = tempDir.resolve("roundtrip.jsonl");
        CopilotClientExporter.exportToFile(original, file);
        List<EntryData> imported = CopilotClientImporter.importFile(file);

        assertEquals(2, imported.size());

        EntryData.Prompt prompt = assertInstanceOf(EntryData.Prompt.class, imported.get(0));
        assertEquals("Hello world", prompt.getText());

        EntryData.Text text = assertInstanceOf(EntryData.Text.class, imported.get(1));
        assertEquals("Greetings!", text.getRaw().toString());
    }

    @Test
    void roundTripPreservesToolCalls() throws IOException {
        EntryData.ToolCall toolCall = new EntryData.ToolCall(
            "read_file", "{\"path\":\"/a\"}", "other", "contents of a", "done",
            null, null, false, null, false, "", "", "gpt-4.1");

        List<EntryData> original = List.of(
            promptEntry("Read /a"),
            textEntry("I'll read the file", "gpt-4.1"),
            toolCall
        );

        Path file = tempDir.resolve("roundtrip-tools.jsonl");
        CopilotClientExporter.exportToFile(original, file);
        List<EntryData> imported = CopilotClientImporter.importFile(file);

        // Prompt + Text + ToolCall
        assertEquals(3, imported.size());

        assertInstanceOf(EntryData.Prompt.class, imported.get(0));

        boolean foundToolCall = false;
        for (EntryData entry : imported) {
            if (entry instanceof EntryData.ToolCall tc) {
                foundToolCall = true;
                assertEquals("read_file", tc.getTitle());
                assertEquals("done", tc.getStatus());
                assertEquals("contents of a", tc.getResult());
            }
        }
        assertTrue(foundToolCall, "Imported entries should contain a tool call");
    }

    @Test
    void roundTripPreservesMultipleTurns() throws IOException {
        List<EntryData> original = List.of(
            promptEntry("First question"),
            textEntry("First answer", "gpt-4.1"),
            promptEntry("Second question"),
            textEntry("Second answer", "gpt-4.1")
        );

        Path file = tempDir.resolve("roundtrip-multi.jsonl");
        CopilotClientExporter.exportToFile(original, file);
        List<EntryData> imported = CopilotClientImporter.importFile(file);

        assertEquals(4, imported.size());
        assertEquals("First question", ((EntryData.Prompt) imported.get(0)).getText());
        assertEquals("First answer", ((EntryData.Text) imported.get(1)).getRaw().toString());
        assertEquals("Second question", ((EntryData.Prompt) imported.get(2)).getText());
        assertEquals("Second answer", ((EntryData.Text) imported.get(3)).getRaw().toString());
    }

    // ── Helper methods ──────────────────────────────────────────────

    private List<EntryData> importEvents(String events) throws IOException {
        Path file = tempDir.resolve("test-events.jsonl");
        Files.writeString(file, events, StandardCharsets.UTF_8);
        return CopilotClientImporter.importFile(file);
    }

    private static EntryData.Prompt promptEntry(String text) {
        return new EntryData.Prompt(text, Instant.now().toString(), null);
    }

    private static EntryData.Text textEntry(String text, String model) {
        return new EntryData.Text(new StringBuilder(text), Instant.now().toString(), "", model);
    }
}
