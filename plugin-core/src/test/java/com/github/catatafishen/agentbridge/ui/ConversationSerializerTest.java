package com.github.catatafishen.agentbridge.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConversationSerializerTest {

    // ── deserialize ─────────────────────────────────────────────────────

    @Test
    void deserialize_emptyArray() {
        assertTrue(ConversationSerializer.INSTANCE.deserialize("[]").isEmpty());
    }

    @Test
    void deserialize_invalidJson() {
        assertTrue(ConversationSerializer.INSTANCE.deserialize("not json").isEmpty());
    }

    @Test
    void deserialize_singlePrompt() {
        JsonArray arr = new JsonArray();
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "prompt");
        obj.addProperty("text", "Hello");
        obj.addProperty("ts", "2024-01-01T00:00:00Z");
        obj.addProperty("id", "p1");
        arr.add(obj);

        List<EntryData> result = ConversationSerializer.INSTANCE.deserialize(arr.toString());
        assertEquals(1, result.size());
        assertInstanceOf(EntryData.Prompt.class, result.get(0));
        assertEquals("Hello", ((EntryData.Prompt) result.get(0)).getText());
    }

    @Test
    void deserialize_skipsUnknownTypes() {
        JsonArray arr = new JsonArray();
        JsonObject unknown = new JsonObject();
        unknown.addProperty("type", "future_type");
        arr.add(unknown);
        assertTrue(ConversationSerializer.INSTANCE.deserialize(arr.toString()).isEmpty());
    }

    @Test
    void deserialize_mixedEntries() {
        JsonArray arr = new JsonArray();

        JsonObject prompt = new JsonObject();
        prompt.addProperty("type", "prompt");
        prompt.addProperty("text", "Hello");
        arr.add(prompt);

        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("raw", "Hi there");
        arr.add(text);

        JsonObject tool = new JsonObject();
        tool.addProperty("type", "tool");
        tool.addProperty("title", "search_text");
        arr.add(tool);

        List<EntryData> result = ConversationSerializer.INSTANCE.deserialize(arr.toString());
        assertEquals(3, result.size());
        assertInstanceOf(EntryData.Prompt.class, result.get(0));
        assertInstanceOf(EntryData.Text.class, result.get(1));
        assertInstanceOf(EntryData.ToolCall.class, result.get(2));
    }

    // ── fromJson: prompt ────────────────────────────────────────────────

    @Test
    void fromJson_prompt() {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "prompt");
        obj.addProperty("text", "What is 2+2?");
        obj.addProperty("ts", "2024-01-01T00:00:00Z");
        obj.addProperty("id", "prompt-1");
        obj.addProperty("eid", "entry-1");

        EntryData.Prompt prompt = (EntryData.Prompt) ConversationSerializer.INSTANCE.fromJson(obj);
        assertEquals("What is 2+2?", prompt.getText());
        assertEquals("2024-01-01T00:00:00Z", prompt.getTimestamp());
        assertEquals("prompt-1", prompt.getId());
        assertEquals("entry-1", prompt.getEntryId());
    }

    @Test
    void fromJson_promptWithContextFiles() {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "prompt");
        obj.addProperty("text", "Review this");

        JsonArray files = new JsonArray();
        JsonObject file = new JsonObject();
        file.addProperty("name", "Foo.java");
        file.addProperty("path", "/src/Foo.java");
        file.addProperty("line", 42);
        files.add(file);
        obj.add("ctxFiles", files);

        EntryData.Prompt prompt = (EntryData.Prompt) ConversationSerializer.INSTANCE.fromJson(obj);
        assertNotNull(prompt.getContextFiles());
        assertEquals(1, prompt.getContextFiles().size());
        assertEquals("Foo.java", prompt.getContextFiles().get(0).getName());
        assertEquals("/src/Foo.java", prompt.getContextFiles().get(0).getPath());
        assertEquals(42, prompt.getContextFiles().get(0).getLine());
    }

    // ── fromJson: text ──────────────────────────────────────────────────

    @Test
    void fromJson_text() {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "text");
        obj.addProperty("raw", "The answer is 4");
        obj.addProperty("ts", "2024-01-01T00:00:01Z");
        obj.addProperty("agent", "copilot");

        EntryData.Text text = (EntryData.Text) ConversationSerializer.INSTANCE.fromJson(obj);
        assertEquals("The answer is 4", text.getRaw());
        assertEquals("copilot", text.getAgent());
    }

    @Test
    void fromJson_textDefaults() {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "text");

        EntryData.Text text = (EntryData.Text) ConversationSerializer.INSTANCE.fromJson(obj);
        assertEquals("", text.getRaw());
        assertEquals("", text.getTimestamp());
        assertEquals("", text.getAgent());
    }

    // ── fromJson: thinking ──────────────────────────────────────────────

    @Test
    void fromJson_thinking() {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "thinking");
        obj.addProperty("raw", "Let me think...");

        EntryData.Thinking thinking = (EntryData.Thinking) ConversationSerializer.INSTANCE.fromJson(obj);
        assertEquals("Let me think...", thinking.getRaw());
    }

    // ── fromJson: tool ──────────────────────────────────────────────────

    @Test
    void fromJson_tool() {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "tool");
        obj.addProperty("title", "read_file");
        obj.addProperty("args", "{\"path\": \"test.txt\"}");
        obj.addProperty("kind", "file");
        obj.addProperty("result", "file contents");
        obj.addProperty("status", "complete");
        obj.addProperty("filePath", "/test.txt");

        EntryData.ToolCall tool = (EntryData.ToolCall) ConversationSerializer.INSTANCE.fromJson(obj);
        assertEquals("read_file", tool.getTitle());
        assertEquals("{\"path\": \"test.txt\"}", tool.getArguments());
        assertEquals("file", tool.getKind());
        assertEquals("file contents", tool.getResult());
        assertEquals("complete", tool.getStatus());
        assertEquals("/test.txt", tool.getFilePath());
    }

    @Test
    void fromJson_toolWithPluginTool() {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "tool");
        obj.addProperty("title", "agentbridge-read_file");
        obj.addProperty("mcpHandled", true);

        EntryData.ToolCall tool = (EntryData.ToolCall) ConversationSerializer.INSTANCE.fromJson(obj);
        assertEquals("agentbridge-read_file", tool.getPluginTool());
    }

    @Test
    void fromJson_toolAutoDenied() {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "tool");
        obj.addProperty("title", "delete_file");
        obj.addProperty("autoDenied", true);
        obj.addProperty("denialReason", "unsafe operation");

        EntryData.ToolCall tool = (EntryData.ToolCall) ConversationSerializer.INSTANCE.fromJson(obj);
        assertTrue(tool.getAutoDenied());
        assertEquals("unsafe operation", tool.getDenialReason());
    }

    // ── fromJson: subagent ──────────────────────────────────────────────

    @Test
    void fromJson_subagent() {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "subagent");
        obj.addProperty("agentType", "explore");
        obj.addProperty("description", "Exploring codebase");
        obj.addProperty("prompt", "Find all test files");
        obj.addProperty("result", "Found 42 test files");
        obj.addProperty("status", "completed");
        obj.addProperty("colorIndex", 2);

        EntryData.SubAgent sa = (EntryData.SubAgent) ConversationSerializer.INSTANCE.fromJson(obj);
        assertEquals("explore", sa.getAgentType());
        assertEquals("Exploring codebase", sa.getDescription());
        assertEquals("Find all test files", sa.getPrompt());
        assertEquals("Found 42 test files", sa.getResult());
        assertEquals("completed", sa.getStatus());
        assertEquals(2, sa.getColorIndex());
    }

    @Test
    void fromJson_subagentDefaults() {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "subagent");

        EntryData.SubAgent sa = (EntryData.SubAgent) ConversationSerializer.INSTANCE.fromJson(obj);
        assertEquals("general-purpose", sa.getAgentType());
        assertEquals("", sa.getDescription());
        assertNull(sa.getPrompt());
        assertNull(sa.getResult());
        assertEquals("completed", sa.getStatus());
        assertEquals(0, sa.getColorIndex());
    }

    // ── fromJson: context ───────────────────────────────────────────────

    @Test
    void fromJson_context() {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "context");

        JsonArray files = new JsonArray();
        JsonObject f = new JsonObject();
        f.addProperty("name", "build.gradle");
        f.addProperty("path", "/build.gradle");
        files.add(f);
        obj.add("files", files);

        EntryData.ContextFiles ctx = (EntryData.ContextFiles) ConversationSerializer.INSTANCE.fromJson(obj);
        assertEquals(1, ctx.getFiles().size());
        assertEquals("build.gradle", ctx.getFiles().get(0).getName());
        assertEquals("/build.gradle", ctx.getFiles().get(0).getPath());
    }

    // ── fromJson: status ────────────────────────────────────────────────

    @Test
    void fromJson_status() {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "status");
        obj.addProperty("icon", "\u2705");
        obj.addProperty("message", "Build successful");

        EntryData.Status status = (EntryData.Status) ConversationSerializer.INSTANCE.fromJson(obj);
        assertEquals("\u2705", status.getIcon());
        assertEquals("Build successful", status.getMessage());
    }

    // ── fromJson: separator ─────────────────────────────────────────────

    @Test
    void fromJson_separator() {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "separator");
        obj.addProperty("timestamp", "2024-06-15T10:00:00Z");
        obj.addProperty("agent", "copilot");

        EntryData.SessionSeparator sep = (EntryData.SessionSeparator) ConversationSerializer.INSTANCE.fromJson(obj);
        assertEquals("2024-06-15T10:00:00Z", sep.getTimestamp());
        assertEquals("copilot", sep.getAgent());
    }

    // ── fromJson: turnStats ─────────────────────────────────────────────

    @Test
    void fromJson_turnStats() {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "turnStats");
        obj.addProperty("turnId", "t1");
        obj.addProperty("durationMs", 5000);
        obj.addProperty("inputTokens", 1000);
        obj.addProperty("outputTokens", 2000);
        obj.addProperty("costUsd", 0.05);
        obj.addProperty("toolCallCount", 3);
        obj.addProperty("linesAdded", 50);
        obj.addProperty("linesRemoved", 20);
        obj.addProperty("model", "gpt-4");
        obj.addProperty("multiplier", "1x");

        EntryData.TurnStats ts = (EntryData.TurnStats) ConversationSerializer.INSTANCE.fromJson(obj);
        assertEquals("t1", ts.getTurnId());
        assertEquals(5000, ts.getDurationMs());
        assertEquals(1000, ts.getInputTokens());
        assertEquals(2000, ts.getOutputTokens());
        assertEquals(0.05, ts.getCostUsd(), 0.001);
        assertEquals(3, ts.getToolCallCount());
        assertEquals(50, ts.getLinesAdded());
        assertEquals(20, ts.getLinesRemoved());
        assertEquals("gpt-4", ts.getModel());
        assertEquals("1x", ts.getMultiplier());
    }

    @Test
    void fromJson_turnStatsWithTotals() {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "turnStats");
        obj.addProperty("turnId", "t1");
        obj.addProperty("totalDurationMs", 15000);
        obj.addProperty("totalInputTokens", 5000);
        obj.addProperty("totalOutputTokens", 8000);
        obj.addProperty("totalCostUsd", 0.15);
        obj.addProperty("totalToolCalls", 10);
        obj.addProperty("totalLinesAdded", 200);
        obj.addProperty("totalLinesRemoved", 50);

        EntryData.TurnStats ts = (EntryData.TurnStats) ConversationSerializer.INSTANCE.fromJson(obj);
        assertEquals(15000, ts.getTotalDurationMs());
        assertEquals(5000, ts.getTotalInputTokens());
        assertEquals(8000, ts.getTotalOutputTokens());
        assertEquals(0.15, ts.getTotalCostUsd(), 0.001);
        assertEquals(10, ts.getTotalToolCalls());
        assertEquals(200, ts.getTotalLinesAdded());
        assertEquals(50, ts.getTotalLinesRemoved());
    }

    // ── fromJson: unknown type ──────────────────────────────────────────

    @Test
    void fromJson_unknownType() {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "unknown");
        assertNull(ConversationSerializer.INSTANCE.fromJson(obj));
    }

    // ── Entry ID generation ─────────────────────────────────────────────

    @Test
    void fromJson_usesEidIfPresent() {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "text");
        obj.addProperty("eid", "custom-entry-id");

        EntryData.Text text = (EntryData.Text) ConversationSerializer.INSTANCE.fromJson(obj);
        assertEquals("custom-entry-id", text.getEntryId());
    }

    @Test
    void fromJson_generatesEidIfMissing() {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "text");

        EntryData.Text text = (EntryData.Text) ConversationSerializer.INSTANCE.fromJson(obj);
        assertNotNull(text.getEntryId());
        assertFalse(text.getEntryId().isEmpty());
    }
}
