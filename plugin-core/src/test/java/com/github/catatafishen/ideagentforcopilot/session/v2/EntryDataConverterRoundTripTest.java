package com.github.catatafishen.ideagentforcopilot.session.v2;

import com.github.catatafishen.ideagentforcopilot.ui.EntryData;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link EntryDataConverter} round-trips preserve all fields,
 * including per-entry timestamps and newly-added fields.
 */
class EntryDataConverterRoundTripTest {

    // ── Per-entry timestamps ─────────────────────────────────────────────────

    @Test
    void perEntryTimestampsPreservedAcrossRoundTrip() {
        String ts1 = "2026-04-01T10:00:00Z";
        String ts2 = "2026-04-01T10:00:05Z";
        String ts3 = "2026-04-01T10:00:30Z";

        List<EntryData> entries = List.of(
            new EntryData.Prompt("Hello", ts1, null, "p1"),
            new EntryData.Text(new StringBuilder("Thinking..."), ts2, "copilot"),
            new EntryData.ToolCall("read_file", "{}", "read", "result", "completed",
                "Read a file", "/src/Main.java", false, null, true, ts3, "copilot")
        );

        List<SessionMessage> messages = EntryDataConverter.toMessages(entries);
        List<EntryData> restored = EntryDataConverter.fromMessages(messages);

        assertEquals(3, restored.size());

        EntryData.Prompt prompt = assertInstanceOf(EntryData.Prompt.class, restored.get(0));
        assertEquals(ts1, prompt.getTimestamp());

        EntryData.Text text = assertInstanceOf(EntryData.Text.class, restored.get(1));
        assertEquals(ts2, text.getTimestamp());

        EntryData.ToolCall tool = assertInstanceOf(EntryData.ToolCall.class, restored.get(2));
        assertEquals(ts3, tool.getTimestamp());
    }

    @Test
    void differentTimestampsWithinSameAssistantMessage() {
        String ts1 = "2026-04-01T10:00:00Z";
        String ts2 = "2026-04-01T10:05:00Z";

        List<EntryData> entries = List.of(
            new EntryData.Prompt("Q", ts1, null, ""),
            new EntryData.Thinking(new StringBuilder("hmm"), ts1, "copilot"),
            new EntryData.Text(new StringBuilder("answer"), ts2, "copilot")
        );

        List<SessionMessage> messages = EntryDataConverter.toMessages(entries);
        assertEquals(2, messages.size(), "prompt + assistant");

        List<EntryData> restored = EntryDataConverter.fromMessages(messages);
        assertEquals(3, restored.size());

        EntryData.Thinking thinking = assertInstanceOf(EntryData.Thinking.class, restored.get(1));
        assertEquals(ts1, thinking.getTimestamp());

        EntryData.Text text = assertInstanceOf(EntryData.Text.class, restored.get(2));
        assertEquals(ts2, text.getTimestamp());
    }

    // ── ToolCall fields ─────────────────────────────────────────────────────

    @Test
    void toolCallFieldsPreservedAcrossRoundTrip() {
        String ts = "2026-04-01T10:00:00Z";
        EntryData.ToolCall original = new EntryData.ToolCall(
            "edit_file", "{\"path\":\"/a.java\"}", "write",
            "success", "completed", "Edited file a.java", "/a.java",
            false, null, true,
            ts, "copilot");

        List<EntryData> entries = List.of(
            new EntryData.Prompt("edit", ts, null, ""),
            original
        );

        List<SessionMessage> messages = EntryDataConverter.toMessages(entries);
        List<EntryData> restored = EntryDataConverter.fromMessages(messages);

        EntryData.ToolCall tool = (EntryData.ToolCall) restored.stream()
            .filter(e -> e instanceof EntryData.ToolCall).findFirst().orElseThrow();

        assertEquals("edit_file", tool.getTitle());
        assertEquals("{\"path\":\"/a.java\"}", tool.getArguments());
        assertEquals("write", tool.getKind());
        assertEquals("success", tool.getResult());
        assertEquals("completed", tool.getStatus());
        assertEquals("Edited file a.java", tool.getDescription());
        assertEquals("/a.java", tool.getFilePath());
        assertFalse(tool.getAutoDenied());
        assertNull(tool.getDenialReason());
        assertTrue(tool.getMcpHandled());
        assertEquals(ts, tool.getTimestamp());
    }

    @Test
    void toolCallDenialPreserved() {
        String ts = "2026-04-01T10:00:00Z";
        EntryData.ToolCall original = new EntryData.ToolCall(
            "delete_file", "{}", "write",
            null, null, null, null,
            true, "Not safe", false,
            ts, "copilot");

        List<EntryData> entries = List.of(
            new EntryData.Prompt("delete", ts, null, ""),
            original
        );

        List<SessionMessage> messages = EntryDataConverter.toMessages(entries);
        List<EntryData> restored = EntryDataConverter.fromMessages(messages);

        EntryData.ToolCall tool = (EntryData.ToolCall) restored.stream()
            .filter(e -> e instanceof EntryData.ToolCall).findFirst().orElseThrow();

        assertTrue(tool.getAutoDenied());
        assertEquals("Not safe", tool.getDenialReason());
    }

    // ── SubAgent fields ─────────────────────────────────────────────────────

    @Test
    void subAgentFieldsPreservedAcrossRoundTrip() {
        String ts = "2026-04-01T10:00:00Z";
        EntryData.SubAgent original = new EntryData.SubAgent(
            "explore", "Find auth code", "Search for auth",
            "Found 3 files", "completed", 2, "call-123",
            true, "Read-only agent", ts, "copilot");

        List<EntryData> entries = List.of(
            new EntryData.Prompt("find auth", ts, null, ""),
            original
        );

        List<SessionMessage> messages = EntryDataConverter.toMessages(entries);
        List<EntryData> restored = EntryDataConverter.fromMessages(messages);

        EntryData.SubAgent sa = (EntryData.SubAgent) restored.stream()
            .filter(e -> e instanceof EntryData.SubAgent).findFirst().orElseThrow();

        assertEquals("explore", sa.getAgentType());
        assertEquals("Find auth code", sa.getDescription());
        assertEquals("Search for auth", sa.getPrompt());
        assertEquals("Found 3 files", sa.getResult());
        assertEquals("completed", sa.getStatus());
        assertEquals(2, sa.getColorIndex());
        assertEquals("call-123", sa.getCallId());
        assertTrue(sa.getAutoDenied());
        assertEquals("Read-only agent", sa.getDenialReason());
        assertEquals(ts, sa.getTimestamp());
    }

    // ── Backward compatibility ──────────────────────────────────────────────

    @Test
    void partsWithoutTsFallBackToMessageTimestamp() {
        long epochMs = Instant.parse("2026-04-01T10:00:00Z").toEpochMilli();
        String expectedTs = Instant.ofEpochMilli(epochMs).toString();

        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", "Hello");
        // No "ts" field — simulates old format

        SessionMessage msg = new SessionMessage("m1", "assistant", List.of(textPart),
            epochMs, "copilot", null);

        List<EntryData> entries = EntryDataConverter.fromMessages(List.of(msg));
        assertEquals(1, entries.size());

        EntryData.Text text = assertInstanceOf(EntryData.Text.class, entries.getFirst());
        assertEquals(expectedTs, text.getTimestamp());
    }

    @Test
    void partsWithTsOverrideMessageTimestamp() {
        long epochMs = Instant.parse("2026-04-01T10:00:00Z").toEpochMilli();
        String partTs = "2026-04-01T10:05:00Z";

        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", "Hello");
        textPart.addProperty("ts", partTs);

        SessionMessage msg = new SessionMessage("m1", "assistant", List.of(textPart),
            epochMs, "copilot", null);

        List<EntryData> entries = EntryDataConverter.fromMessages(List.of(msg));
        assertEquals(1, entries.size());

        EntryData.Text text = assertInstanceOf(EntryData.Text.class, entries.getFirst());
        assertEquals(partTs, text.getTimestamp());
    }

    @Test
    void subagentWithoutNewFieldsFallsBackGracefully() {
        long epochMs = System.currentTimeMillis();

        JsonObject saPart = new JsonObject();
        saPart.addProperty("type", "subagent");
        saPart.addProperty("agentType", "explore");
        saPart.addProperty("description", "desc");
        saPart.addProperty("prompt", "find stuff");
        saPart.addProperty("result", "found it");
        saPart.addProperty("status", "completed");
        saPart.addProperty("colorIndex", 1);
        // No callId, autoDenied, denialReason — simulates old format

        SessionMessage msg = new SessionMessage("m1", "assistant", List.of(saPart),
            epochMs, null, null);

        List<EntryData> entries = EntryDataConverter.fromMessages(List.of(msg));
        EntryData.SubAgent sa = assertInstanceOf(EntryData.SubAgent.class, entries.getFirst());

        assertNull(sa.getCallId());
        assertFalse(sa.getAutoDenied());
        assertNull(sa.getDenialReason());
    }

    @Test
    void toolInvocationWithoutNewFieldsFallsBackGracefully() {
        long epochMs = System.currentTimeMillis();

        JsonObject invocation = new JsonObject();
        invocation.addProperty("state", "result");
        invocation.addProperty("toolCallId", "t1");
        invocation.addProperty("toolName", "read_file");
        invocation.addProperty("args", "{}");
        invocation.addProperty("result", "content");
        // No status, description, filePath, mcpHandled — simulates old format

        JsonObject part = new JsonObject();
        part.addProperty("type", "tool-invocation");
        part.add("toolInvocation", invocation);

        SessionMessage msg = new SessionMessage("m1", "assistant", List.of(part),
            epochMs, null, null);

        List<EntryData> entries = EntryDataConverter.fromMessages(List.of(msg));
        EntryData.ToolCall tool = (EntryData.ToolCall) entries.stream()
            .filter(e -> e instanceof EntryData.ToolCall).findFirst().orElseThrow();

        assertNull(tool.getStatus());
        assertNull(tool.getDescription());
        assertNull(tool.getFilePath());
        assertFalse(tool.getMcpHandled());
    }

    // ── SessionSeparator ────────────────────────────────────────────────────

    @Test
    void sessionSeparatorPreservesTimestamp() {
        String ts = "2026-04-01T10:00:00Z";
        List<EntryData> entries = List.of(new EntryData.SessionSeparator(ts, "copilot"));

        List<SessionMessage> messages = EntryDataConverter.toMessages(entries);
        assertEquals(1, messages.size());
        assertEquals("separator", messages.getFirst().role);

        List<EntryData> restored = EntryDataConverter.fromMessages(messages);
        assertEquals(1, restored.size());
        EntryData.SessionSeparator sep = assertInstanceOf(EntryData.SessionSeparator.class, restored.getFirst());
        assertNotNull(sep.getTimestamp());
        assertFalse(sep.getTimestamp().isEmpty());
    }

    @Test
    void contextFileLineNumbersPreservedAcrossRoundTrip() {
        List<kotlin.Triple<String, String, Integer>> files = List.of(
            new kotlin.Triple<>("Main.java", "/src/Main.java", 42),
            new kotlin.Triple<>("Utils.java", "/src/Utils.java", 0),
            new kotlin.Triple<>("Config.kt", "/src/Config.kt", 100)
        );

        List<EntryData> entries = List.of(
            new EntryData.Prompt("Look at these files", "2026-04-01T10:00:00Z", files, "p1")
        );

        List<SessionMessage> messages = EntryDataConverter.toMessages(entries);
        List<EntryData> restored = EntryDataConverter.fromMessages(messages);

        assertEquals(1, restored.size());
        var prompt = assertInstanceOf(EntryData.Prompt.class, restored.getFirst());
        assertNotNull(prompt.getContextFiles());
        assertEquals(3, prompt.getContextFiles().size());

        var f1 = prompt.getContextFiles().get(0);
        assertEquals("Main.java", f1.getFirst());
        assertEquals("/src/Main.java", f1.getSecond());
        assertEquals(42, f1.getThird());

        var f2 = prompt.getContextFiles().get(1);
        assertEquals("Utils.java", f2.getFirst());
        assertEquals(0, f2.getThird()); // line 0 = no line specified

        var f3 = prompt.getContextFiles().get(2);
        assertEquals("Config.kt", f3.getFirst());
        assertEquals(100, f3.getThird());
    }

    @Test
    void entryIdsPreservedAcrossRoundTrip() {
        String eid1 = "eid-prompt-123";
        String eid2 = "eid-text-456";
        String eid3 = "eid-tool-789";

        List<EntryData> entries = List.of(
            new EntryData.Prompt("Hello", "2026-04-01T10:00:00Z", null, "p1", eid1),
            new EntryData.Text(new StringBuilder("Reply"), "2026-04-01T10:00:05Z", "copilot", "", eid2),
            new EntryData.ToolCall("read_file", "{}", "read", "result", "completed",
                "Read a file", "/src/Main.java", false, null, true,
                "2026-04-01T10:00:10Z", "copilot", "", eid3)
        );

        List<SessionMessage> messages = EntryDataConverter.toMessages(entries);
        List<EntryData> restored = EntryDataConverter.fromMessages(messages);

        assertEquals(3, restored.size());
        assertEquals(eid1, restored.get(0).getEntryId());
        assertEquals(eid2, restored.get(1).getEntryId());
        assertEquals(eid3, restored.get(2).getEntryId());
    }

    @Test
    void filePartsWithoutLineFieldDefaultToZero() {
        // Simulate a V2 file part written before line field was added
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", "Hello");

        JsonObject filePart = new JsonObject();
        filePart.addProperty("type", "file");
        filePart.addProperty("filename", "Old.java");
        filePart.addProperty("path", "/src/Old.java");
        // Note: no "line" field

        SessionMessage userMsg = new SessionMessage(
            "u1", "user", List.of(textPart, filePart),
            System.currentTimeMillis(), null, null);

        List<EntryData> restored = EntryDataConverter.fromMessages(List.of(userMsg));
        assertEquals(1, restored.size());
        var prompt = assertInstanceOf(EntryData.Prompt.class, restored.getFirst());
        assertNotNull(prompt.getContextFiles());
        assertEquals(1, prompt.getContextFiles().size());
        assertEquals(0, prompt.getContextFiles().getFirst().getThird()); // defaults to 0
    }

    @Test
    void collectFilePartsOnlyCollectsFileTypes() {
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", "ignored");

        JsonObject filePart1 = new JsonObject();
        filePart1.addProperty("type", "file");
        filePart1.addProperty("filename", "A.java");
        filePart1.addProperty("path", "/A.java");
        filePart1.addProperty("line", 10);

        JsonObject reasoningPart = new JsonObject();
        reasoningPart.addProperty("type", "reasoning");
        reasoningPart.addProperty("text", "thinking");

        JsonObject filePart2 = new JsonObject();
        filePart2.addProperty("type", "file");
        filePart2.addProperty("filename", "B.java");
        filePart2.addProperty("path", "/B.java");
        filePart2.addProperty("line", 20);

        java.util.Set<Integer> consumed = new java.util.HashSet<>();
        var files = EntryDataConverter.collectFileParts(
            List.of(textPart, filePart1, reasoningPart, filePart2), 1, consumed);

        assertEquals(2, files.size());
        assertEquals("A.java", files.get(0).getFirst());
        assertEquals(10, files.get(0).getThird());
        assertEquals("B.java", files.get(1).getFirst());
        assertEquals(20, files.get(1).getThird());
        assertTrue(consumed.contains(1));
        assertFalse(consumed.contains(2)); // reasoning not consumed
        assertTrue(consumed.contains(3));
    }
}
