package com.github.catatafishen.ideagentforcopilot.session;

import com.github.catatafishen.ideagentforcopilot.session.exporters.CopilotClientExporter;
import com.github.catatafishen.ideagentforcopilot.session.importers.CopilotClientImporter;
import com.github.catatafishen.ideagentforcopilot.session.v2.SessionMessage;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link CopilotClientImporter} and {@link CopilotClientExporter}.
 * Validates import, export, and round-trip conversion between Copilot's native
 * {@code events.jsonl} format and the v2 {@link SessionMessage} model.
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

        List<SessionMessage> messages = importEvents(events);

        assertEquals(2, messages.size());

        SessionMessage user = messages.get(0);
        assertEquals("user", user.role);
        assertEquals("Hello", extractText(user));

        SessionMessage assistant = messages.get(1);
        assertEquals("assistant", assistant.role);
        assertEquals("Hi there!", extractText(assistant));
        assertEquals("gpt-4.1", assistant.model);
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

        List<SessionMessage> messages = importEvents(events);
        assertEquals(2, messages.size());

        SessionMessage assistant = messages.get(1);
        assertEquals(2, assistant.parts.size());
        assertEquals("reasoning", assistant.parts.get(0).get("type").getAsString());
        assertEquals("Let me think...", assistant.parts.get(0).get("text").getAsString());
        assertEquals("text", assistant.parts.get(1).get("type").getAsString());
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

        List<SessionMessage> messages = importEvents(events);
        assertEquals(2, messages.size());

        SessionMessage assistant = messages.get(1);
        // Should have: tool-invocation (with result) + text
        boolean hasToolInvocation = false;
        boolean hasText = false;
        for (JsonObject part : assistant.parts) {
            String type = part.get("type").getAsString();
            if ("tool-invocation".equals(type)) {
                hasToolInvocation = true;
                JsonObject inv = part.getAsJsonObject("toolInvocation");
                assertEquals("result", inv.get("state").getAsString());
                assertEquals("tc1", inv.get("toolCallId").getAsString());
                assertEquals("read_file", inv.get("toolName").getAsString());
                assertEquals("file contents", inv.get("result").getAsString());
            } else if ("text".equals(type)) {
                hasText = true;
            }
        }
        assertTrue(hasToolInvocation, "Should contain a tool invocation part");
        assertTrue(hasText, "Should contain a text part");
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

        List<SessionMessage> messages = importEvents(events);
        assertEquals(2, messages.size());

        SessionMessage assistant = messages.get(1);
        boolean hasSubagent = false;
        for (JsonObject part : assistant.parts) {
            if ("subagent".equals(part.get("type").getAsString())) {
                hasSubagent = true;
                assertEquals("explore", part.get("agentType").getAsString());
                assertEquals("Code Explorer", part.get("description").getAsString());
                assertEquals("done", part.get("status").getAsString());
            }
        }
        assertTrue(hasSubagent, "Should contain a subagent part");
    }

    @Test
    void importEmptyFileReturnsEmptyList() throws IOException {
        List<SessionMessage> messages = importEvents("");
        assertTrue(messages.isEmpty());
    }

    // ── Export tests ────────────────────────────────────────────────

    @Test
    void exportProducesValidEventsJsonl() throws IOException {
        List<SessionMessage> messages = List.of(
            userMessage("What is 2+2?"),
            assistantMessage("4", "gpt-4.1")
        );

        Path target = tempDir.resolve("events.jsonl");
        CopilotClientExporter.exportToFile(messages, target);

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
        JsonObject toolPart = toolInvocationPart("tc1", "read_file", "{\"path\":\"/test\"}", "file data");
        SessionMessage assistant = new SessionMessage(
            "a1", "assistant", List.of(toolPart), System.currentTimeMillis(), null, "gpt-4.1");

        Path target = tempDir.resolve("events.jsonl");
        CopilotClientExporter.exportToFile(List.of(userMessage("read"), assistant), target);

        String content = Files.readString(target, StandardCharsets.UTF_8);
        assertTrue(content.contains("\"type\":\"tool.execution_complete\""));
        assertTrue(content.contains("\"toolCallId\":\"tc1\""));
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
        List<SessionMessage> original = List.of(
            userMessage("Hello world"),
            assistantMessage("Greetings!", "gpt-4.1")
        );

        Path file = tempDir.resolve("roundtrip.jsonl");
        CopilotClientExporter.exportToFile(original, file);
        List<SessionMessage> imported = CopilotClientImporter.importFile(file);

        assertEquals(2, imported.size());
        assertEquals("user", imported.get(0).role);
        assertEquals("Hello world", extractText(imported.get(0)));
        assertEquals("assistant", imported.get(1).role);
        assertEquals("Greetings!", extractText(imported.get(1)));
    }

    @Test
    void roundTripPreservesToolCalls() throws IOException {
        JsonObject textPart = textPart("I'll read the file");
        JsonObject toolPart = toolInvocationPart("tc1", "read_file", "{\"path\":\"/a\"}", "contents of a");

        SessionMessage assistant = new SessionMessage(
            "a1", "assistant", List.of(textPart, toolPart), System.currentTimeMillis(), null, "gpt-4.1");

        List<SessionMessage> original = List.of(userMessage("Read /a"), assistant);

        Path file = tempDir.resolve("roundtrip-tools.jsonl");
        CopilotClientExporter.exportToFile(original, file);
        List<SessionMessage> imported = CopilotClientImporter.importFile(file);

        assertEquals(2, imported.size());

        SessionMessage importedAssistant = imported.get(1);
        boolean foundTool = false;
        for (JsonObject part : importedAssistant.parts) {
            if ("tool-invocation".equals(part.get("type").getAsString())) {
                foundTool = true;
                JsonObject inv = part.getAsJsonObject("toolInvocation");
                assertEquals("result", inv.get("state").getAsString());
                assertEquals("read_file", inv.get("toolName").getAsString());
                assertEquals("contents of a", inv.get("result").getAsString());
            }
        }
        assertTrue(foundTool, "Imported assistant should contain tool invocation");
    }

    @Test
    void roundTripPreservesMultipleTurns() throws IOException {
        List<SessionMessage> original = List.of(
            userMessage("First question"),
            assistantMessage("First answer", "gpt-4.1"),
            userMessage("Second question"),
            assistantMessage("Second answer", "gpt-4.1")
        );

        Path file = tempDir.resolve("roundtrip-multi.jsonl");
        CopilotClientExporter.exportToFile(original, file);
        List<SessionMessage> imported = CopilotClientImporter.importFile(file);

        assertEquals(4, imported.size());
        assertEquals("First question", extractText(imported.get(0)));
        assertEquals("First answer", extractText(imported.get(1)));
        assertEquals("Second question", extractText(imported.get(2)));
        assertEquals("Second answer", extractText(imported.get(3)));
    }

    // ── Helper methods ──────────────────────────────────────────────

    private List<SessionMessage> importEvents(String events) throws IOException {
        Path file = tempDir.resolve("test-events.jsonl");
        Files.writeString(file, events, StandardCharsets.UTF_8);
        return CopilotClientImporter.importFile(file);
    }

    private static SessionMessage userMessage(String text) {
        JsonObject part = textPart(text);
        return new SessionMessage("u-" + text.hashCode(), "user", List.of(part),
            System.currentTimeMillis(), null, null);
    }

    private static SessionMessage assistantMessage(String text, String model) {
        JsonObject part = textPart(text);
        return new SessionMessage("a-" + text.hashCode(), "assistant", List.of(part),
            System.currentTimeMillis(), null, model);
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
