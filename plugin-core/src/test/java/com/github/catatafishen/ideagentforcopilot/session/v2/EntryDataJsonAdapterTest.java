package com.github.catatafishen.ideagentforcopilot.session.v2;

import com.github.catatafishen.ideagentforcopilot.ui.EntryData;
import com.google.gson.JsonObject;
import kotlin.Pair;
import kotlin.Triple;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntryDataJsonAdapterTest {

    // ── 1. Prompt round-trip ──────────────────────────────────────────────────

    @Test
    void promptRoundTrip() {
        var contextFiles = List.of(
            new Triple<>("Main.java", "/src/Main.java", 42)
        );
        var original = new EntryData.Prompt("Hello", "2026-01-01T00:00:00Z", contextFiles, "p1", "eid-1");

        JsonObject json = EntryDataJsonAdapter.serialize(original);
        assertEquals("prompt", json.get("type").getAsString());
        assertEquals("Hello", json.get("text").getAsString());
        assertTrue(json.has("contextFiles"));
        var fileArr = json.getAsJsonArray("contextFiles");
        assertEquals(1, fileArr.size());
        var fileObj = fileArr.get(0).getAsJsonObject();
        assertEquals("Main.java", fileObj.get("name").getAsString());
        assertEquals("/src/Main.java", fileObj.get("path").getAsString());
        assertEquals(42, fileObj.get("line").getAsInt());

        EntryData deserialized = EntryDataJsonAdapter.deserialize(json);
        assertInstanceOf(EntryData.Prompt.class, deserialized);
        var result = (EntryData.Prompt) deserialized;
        assertEquals("Hello", result.getText());
        assertEquals("2026-01-01T00:00:00Z", result.getTimestamp());
        assertNotNull(result.getContextFiles());
        assertEquals(1, result.getContextFiles().size());
        assertEquals("Main.java", result.getContextFiles().get(0).getFirst());
        assertEquals("/src/Main.java", result.getContextFiles().get(0).getSecond());
        assertEquals(42, result.getContextFiles().get(0).getThird());
        assertEquals("p1", result.getId());
        assertEquals("eid-1", result.getEntryId());
    }

    // ── 2. Text round-trip ────────────────────────────────────────────────────

    @Test
    void textRoundTrip() {
        var original = new EntryData.Text(new StringBuilder("reply"), "2026-01-01T00:00:01Z", "copilot", "claude-sonnet-4-6", "eid-2");

        JsonObject json = EntryDataJsonAdapter.serialize(original);
        assertEquals("text", json.get("type").getAsString());
        assertEquals("reply", json.get("raw").getAsString());
        assertEquals("claude-sonnet-4-6", json.get("model").getAsString());

        EntryData deserialized = EntryDataJsonAdapter.deserialize(json);
        assertInstanceOf(EntryData.Text.class, deserialized);
        var result = (EntryData.Text) deserialized;
        assertEquals("reply", result.getRaw().toString());
        assertEquals("2026-01-01T00:00:01Z", result.getTimestamp());
        assertEquals("copilot", result.getAgent());
        assertEquals("claude-sonnet-4-6", result.getModel());
        assertEquals("eid-2", result.getEntryId());
    }

    // ── 3. Thinking round-trip ────────────────────────────────────────────────

    @Test
    void thinkingRoundTrip() {
        var original = new EntryData.Thinking(new StringBuilder("thought"), "2026-01-01T00:00:02Z", "copilot", "gpt-4o", "eid-3");

        JsonObject json = EntryDataJsonAdapter.serialize(original);
        assertEquals("thinking", json.get("type").getAsString());
        assertEquals("thought", json.get("raw").getAsString());
        assertEquals("gpt-4o", json.get("model").getAsString());

        EntryData deserialized = EntryDataJsonAdapter.deserialize(json);
        assertInstanceOf(EntryData.Thinking.class, deserialized);
        var result = (EntryData.Thinking) deserialized;
        assertEquals("thought", result.getRaw().toString());
        assertEquals("2026-01-01T00:00:02Z", result.getTimestamp());
        assertEquals("copilot", result.getAgent());
        assertEquals("gpt-4o", result.getModel());
        assertEquals("eid-3", result.getEntryId());
    }

    // ── 4. ToolCall round-trip ────────────────────────────────────────────────

    @Test
    void toolCallRoundTrip() {
        var original = new EntryData.ToolCall(
            "read_file",        // title
            "{\"path\":\"/src\"}", // arguments
            "read",             // kind
            "content",          // result
            "completed",        // status
            "Read a file",      // description
            "/src/Main.java",   // filePath
            false,              // autoDenied
            null,               // denialReason
            true,               // mcpHandled
            "2026-01-01T00:00:03Z", // timestamp
            "copilot",          // agent
            "claude-sonnet-4-6",    // model
            "eid-4"             // entryId
        );

        JsonObject json = EntryDataJsonAdapter.serialize(original);
        assertEquals("tool", json.get("type").getAsString());
        assertEquals("read_file", json.get("title").getAsString());
        assertEquals("{\"path\":\"/src\"}", json.get("arguments").getAsString());
        assertEquals("read", json.get("kind").getAsString());
        assertEquals("content", json.get("result").getAsString());
        assertEquals("completed", json.get("status").getAsString());
        assertEquals("Read a file", json.get("description").getAsString());
        assertEquals("/src/Main.java", json.get("filePath").getAsString());
        assertFalse(json.has("autoDenied")); // false → omitted
        assertTrue(json.get("mcpHandled").getAsBoolean());
        assertEquals("2026-01-01T00:00:03Z", json.get("timestamp").getAsString());
        assertEquals("copilot", json.get("agent").getAsString());
        assertEquals("claude-sonnet-4-6", json.get("model").getAsString());
        assertEquals("eid-4", json.get("entryId").getAsString());

        EntryData deserialized = EntryDataJsonAdapter.deserialize(json);
        assertInstanceOf(EntryData.ToolCall.class, deserialized);
        var result = (EntryData.ToolCall) deserialized;
        assertEquals("read_file", result.getTitle());
        assertEquals("{\"path\":\"/src\"}", result.getArguments());
        assertEquals("read", result.getKind());
        assertEquals("content", result.getResult());
        assertEquals("completed", result.getStatus());
        assertEquals("Read a file", result.getDescription());
        assertEquals("/src/Main.java", result.getFilePath());
        assertFalse(result.getAutoDenied());
        assertNull(result.getDenialReason());
        assertTrue(result.getMcpHandled());
        assertEquals("2026-01-01T00:00:03Z", result.getTimestamp());
        assertEquals("copilot", result.getAgent());
        assertEquals("claude-sonnet-4-6", result.getModel());
        assertEquals("eid-4", result.getEntryId());
    }

    // ── 5. SubAgent round-trip ────────────────────────────────────────────────

    @Test
    void subAgentRoundTrip() {
        var original = new EntryData.SubAgent(
            "explore",          // agentType
            "Find code",        // description
            "search for X",     // prompt
            "found Y",          // result
            "completed",        // status
            2,                  // colorIndex
            "call-1",           // callId
            false,              // autoDenied
            null,               // denialReason
            "2026-01-01T00:00:04Z", // timestamp
            "copilot",          // agent
            "gpt-4o",           // model
            "eid-5"             // entryId
        );

        JsonObject json = EntryDataJsonAdapter.serialize(original);
        assertEquals("subagent", json.get("type").getAsString());
        assertEquals("explore", json.get("agentType").getAsString());
        assertEquals("Find code", json.get("description").getAsString());
        assertEquals("search for X", json.get("prompt").getAsString());
        assertEquals("found Y", json.get("result").getAsString());
        assertEquals("completed", json.get("status").getAsString());
        assertEquals(2, json.get("colorIndex").getAsInt());
        assertEquals("call-1", json.get("callId").getAsString());
        assertFalse(json.has("autoDenied")); // false → omitted
        assertEquals("2026-01-01T00:00:04Z", json.get("timestamp").getAsString());
        assertEquals("copilot", json.get("agent").getAsString());
        assertEquals("gpt-4o", json.get("model").getAsString());
        assertEquals("eid-5", json.get("entryId").getAsString());

        EntryData deserialized = EntryDataJsonAdapter.deserialize(json);
        assertInstanceOf(EntryData.SubAgent.class, deserialized);
        var result = (EntryData.SubAgent) deserialized;
        assertEquals("explore", result.getAgentType());
        assertEquals("Find code", result.getDescription());
        assertEquals("search for X", result.getPrompt());
        assertEquals("found Y", result.getResult());
        assertEquals("completed", result.getStatus());
        assertEquals(2, result.getColorIndex());
        assertEquals("call-1", result.getCallId());
        assertFalse(result.getAutoDenied());
        assertNull(result.getDenialReason());
        assertEquals("2026-01-01T00:00:04Z", result.getTimestamp());
        assertEquals("copilot", result.getAgent());
        assertEquals("gpt-4o", result.getModel());
        assertEquals("eid-5", result.getEntryId());
    }

    // ── 6. ContextFiles round-trip ────────────────────────────────────────────

    @Test
    void contextFilesRoundTrip() {
        var files = List.of(
            new Pair<>("A.java", "/src/A.java"),
            new Pair<>("B.java", "/src/B.java")
        );
        var original = new EntryData.ContextFiles(files, "eid-6");

        JsonObject json = EntryDataJsonAdapter.serialize(original);
        assertEquals("context", json.get("type").getAsString());
        var arr = json.getAsJsonArray("files");
        assertEquals(2, arr.size());
        assertEquals("A.java", arr.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("/src/B.java", arr.get(1).getAsJsonObject().get("path").getAsString());
        assertEquals("eid-6", json.get("entryId").getAsString());

        EntryData deserialized = EntryDataJsonAdapter.deserialize(json);
        assertInstanceOf(EntryData.ContextFiles.class, deserialized);
        var result = (EntryData.ContextFiles) deserialized;
        assertEquals(2, result.getFiles().size());
        assertEquals("A.java", result.getFiles().get(0).getFirst());
        assertEquals("/src/A.java", result.getFiles().get(0).getSecond());
        assertEquals("B.java", result.getFiles().get(1).getFirst());
        assertEquals("/src/B.java", result.getFiles().get(1).getSecond());
        assertEquals("eid-6", result.getEntryId());
    }

    // ── 7. Status round-trip ──────────────────────────────────────────────────

    @Test
    void statusRoundTrip() {
        var original = new EntryData.Status("ℹ", "Processing...", "eid-7");

        JsonObject json = EntryDataJsonAdapter.serialize(original);
        assertEquals("status", json.get("type").getAsString());
        assertEquals("ℹ", json.get("icon").getAsString());
        assertEquals("Processing...", json.get("message").getAsString());
        assertEquals("eid-7", json.get("entryId").getAsString());

        EntryData deserialized = EntryDataJsonAdapter.deserialize(json);
        assertInstanceOf(EntryData.Status.class, deserialized);
        var result = (EntryData.Status) deserialized;
        assertEquals("ℹ", result.getIcon());
        assertEquals("Processing...", result.getMessage());
        assertEquals("eid-7", result.getEntryId());
    }

    // ── 8. SessionSeparator round-trip ────────────────────────────────────────

    @Test
    void separatorRoundTrip() {
        var original = new EntryData.SessionSeparator("2026-01-01T01:00:00Z", "copilot", "eid-8");

        JsonObject json = EntryDataJsonAdapter.serialize(original);
        assertEquals("separator", json.get("type").getAsString());
        assertEquals("2026-01-01T01:00:00Z", json.get("timestamp").getAsString());
        assertEquals("copilot", json.get("agent").getAsString());
        assertEquals("eid-8", json.get("entryId").getAsString());

        EntryData deserialized = EntryDataJsonAdapter.deserialize(json);
        assertInstanceOf(EntryData.SessionSeparator.class, deserialized);
        var result = (EntryData.SessionSeparator) deserialized;
        assertEquals("2026-01-01T01:00:00Z", result.getTimestamp());
        assertEquals("copilot", result.getAgent());
        assertEquals("eid-8", result.getEntryId());
    }

    // ── 9. Compact serialization omits defaults ───────────────────────────────

    @Test
    void compactSerializationOmitsDefaults() {
        var original = new EntryData.ToolCall(
            "tool",   // title
            null,     // arguments
            "other",  // kind
            null,     // result
            null,     // status
            null,     // description
            null,     // filePath
            false,    // autoDenied
            null,     // denialReason
            false,    // mcpHandled
            "",       // timestamp
            "",       // agent
            "",       // model
            "eid-9"   // entryId
        );

        JsonObject json = EntryDataJsonAdapter.serialize(original);

        // Should be present
        assertTrue(json.has("type"));
        assertTrue(json.has("title"));
        assertTrue(json.has("kind"));
        assertTrue(json.has("entryId"));

        // Should NOT be present (null/empty/false defaults)
        assertFalse(json.has("arguments"), "arguments should be omitted when null");
        assertFalse(json.has("result"), "result should be omitted when null");
        assertFalse(json.has("status"), "status should be omitted when null");
        assertFalse(json.has("description"), "description should be omitted when null");
        assertFalse(json.has("filePath"), "filePath should be omitted when null");
        assertFalse(json.has("autoDenied"), "autoDenied should be omitted when false");
        assertFalse(json.has("mcpHandled"), "mcpHandled should be omitted when false");
        assertFalse(json.has("agent"), "agent should be omitted when empty");
        assertFalse(json.has("model"), "model should be omitted when empty");
        assertFalse(json.has("timestamp"), "timestamp should be omitted when empty");
    }

    // ── 10. isEntryFormat detects type field ──────────────────────────────────

    @Test
    void isEntryFormatDetectsTypeField() {
        assertTrue(EntryDataJsonAdapter.isEntryFormat("{\"type\":\"text\",\"raw\":\"hello\"}"));
    }

    // ── 11. isEntryFormat rejects role field ──────────────────────────────────

    @Test
    void isEntryFormatRejectsRoleField() {
        assertFalse(EntryDataJsonAdapter.isEntryFormat("{\"role\":\"assistant\",\"parts\":[]}"));
    }

    // ── 12. Unknown type deserializes to null ─────────────────────────────────

    @Test
    void unknownTypeDeserializesToNull() {
        var json = new JsonObject();
        json.addProperty("type", "future_type");
        assertNull(EntryDataJsonAdapter.deserialize(json));
    }

    // ── 13. Missing entryId generates UUID ────────────────────────────────────

    @Test
    void missingEntryIdGeneratesUuid() {
        var json = new JsonObject();
        json.addProperty("type", "status");
        json.addProperty("icon", "ℹ");
        json.addProperty("message", "test");
        // no entryId

        EntryData deserialized = EntryDataJsonAdapter.deserialize(json);
        assertInstanceOf(EntryData.Status.class, deserialized);
        var result = (EntryData.Status) deserialized;
        assertNotNull(result.getEntryId());
        assertFalse(result.getEntryId().isEmpty());
    }

    // ── 14. Prompt with null contextFiles round-trip ──────────────────────────

    @Test
    void promptWithNullContextFilesRoundTrip() {
        var original = new EntryData.Prompt("Hi", "2026-01-01T00:00:00Z", null, "p2", "eid-10");

        JsonObject json = EntryDataJsonAdapter.serialize(original);
        assertFalse(json.has("contextFiles"), "contextFiles should not be present when null");

        EntryData deserialized = EntryDataJsonAdapter.deserialize(json);
        assertInstanceOf(EntryData.Prompt.class, deserialized);
        var result = (EntryData.Prompt) deserialized;
        assertTrue(result.getContextFiles() == null || result.getContextFiles().isEmpty());
        assertEquals("Hi", result.getText());
        assertEquals("eid-10", result.getEntryId());
    }

    // ── 15. Context file line zero omitted ────────────────────────────────────

    @Test
    void contextFileLineZeroOmitted() {
        var contextFiles = List.of(
            new Triple<>("X.java", "/x", 0)
        );
        var original = new EntryData.Prompt("test", "", contextFiles, "", "eid-line0");

        JsonObject json = EntryDataJsonAdapter.serialize(original);
        assertTrue(json.has("contextFiles"));
        var fileObj = json.getAsJsonArray("contextFiles").get(0).getAsJsonObject();
        assertEquals("X.java", fileObj.get("name").getAsString());
        assertEquals("/x", fileObj.get("path").getAsString());
        assertFalse(fileObj.has("line"), "line should not be present when value is 0");
    }

    // ── 16. TurnStats round-trip ──────────────────────────────────────────────

    @Test
    void turnStatsRoundTrip() {
        EntryData.TurnStats stats = new EntryData.TurnStats(
            "t3", 45230, 1200, 3500, 0.015, 8, 42, 7,
            "claude-opus-4.6", "5x",
            120000, 5000, 15000, 0.065, 25, 150, 30,
            "eid-stats-1"
        );
        JsonObject json = EntryDataJsonAdapter.serialize(stats);
        assertEquals("turnStats", json.get("type").getAsString());
        assertEquals("t3", json.get("turnId").getAsString());
        assertEquals(45230, json.get("durationMs").getAsLong());
        assertEquals(1200, json.get("inputTokens").getAsLong());
        assertEquals(3500, json.get("outputTokens").getAsLong());
        assertEquals(0.015, json.get("costUsd").getAsDouble(), 0.0001);
        assertEquals(8, json.get("toolCallCount").getAsInt());
        assertEquals(42, json.get("linesAdded").getAsInt());
        assertEquals(7, json.get("linesRemoved").getAsInt());
        assertEquals("claude-opus-4.6", json.get("model").getAsString());
        assertEquals("5x", json.get("multiplier").getAsString());
        assertEquals(120000, json.get("totalDurationMs").getAsLong());
        assertEquals(5000, json.get("totalInputTokens").getAsLong());
        assertEquals(15000, json.get("totalOutputTokens").getAsLong());
        assertEquals(0.065, json.get("totalCostUsd").getAsDouble(), 0.0001);
        assertEquals(25, json.get("totalToolCalls").getAsInt());
        assertEquals(150, json.get("totalLinesAdded").getAsInt());
        assertEquals(30, json.get("totalLinesRemoved").getAsInt());
        assertEquals("eid-stats-1", json.get("entryId").getAsString());

        EntryData deserialized = EntryDataJsonAdapter.deserialize(json);
        assertInstanceOf(EntryData.TurnStats.class, deserialized);
        EntryData.TurnStats rt = (EntryData.TurnStats) deserialized;
        assertEquals("t3", rt.getTurnId());
        assertEquals(45230, rt.getDurationMs());
        assertEquals(1200, rt.getInputTokens());
        assertEquals(3500, rt.getOutputTokens());
        assertEquals(0.015, rt.getCostUsd(), 0.0001);
        assertEquals(8, rt.getToolCallCount());
        assertEquals(42, rt.getLinesAdded());
        assertEquals(7, rt.getLinesRemoved());
        assertEquals("claude-opus-4.6", rt.getModel());
        assertEquals("5x", rt.getMultiplier());
        assertEquals(120000, rt.getTotalDurationMs());
        assertEquals(5000, rt.getTotalInputTokens());
        assertEquals(15000, rt.getTotalOutputTokens());
        assertEquals(0.065, rt.getTotalCostUsd(), 0.0001);
        assertEquals(25, rt.getTotalToolCalls());
        assertEquals(150, rt.getTotalLinesAdded());
        assertEquals(30, rt.getTotalLinesRemoved());
        assertEquals("eid-stats-1", rt.getEntryId());
    }

    // ── 17. TurnStats defaults omitted (compaction) ──────────────────────────

    @Test
    void turnStatsDefaultsOmitted() {
        EntryData.TurnStats stats = new EntryData.TurnStats("t0");
        JsonObject json = EntryDataJsonAdapter.serialize(stats);
        assertEquals("turnStats", json.get("type").getAsString());
        assertEquals("t0", json.get("turnId").getAsString());
        assertFalse(json.has("durationMs"), "zero durationMs should be omitted");
        assertFalse(json.has("inputTokens"), "zero inputTokens should be omitted");
        assertFalse(json.has("outputTokens"), "zero outputTokens should be omitted");
        assertFalse(json.has("costUsd"), "zero costUsd should be omitted");
        assertFalse(json.has("toolCallCount"), "zero toolCallCount should be omitted");
        assertFalse(json.has("linesAdded"), "zero linesAdded should be omitted");
        assertFalse(json.has("linesRemoved"), "zero linesRemoved should be omitted");
        assertFalse(json.has("model"), "empty model should be omitted");
        assertFalse(json.has("multiplier"), "empty multiplier should be omitted");
        assertTrue(json.has("entryId"), "entryId should always be present");
    }
}
