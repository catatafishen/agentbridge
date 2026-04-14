package com.github.catatafishen.agentbridge.session.v2;

import com.github.catatafishen.agentbridge.ui.ContextFileRef;
import com.github.catatafishen.agentbridge.ui.EntryData;
import com.github.catatafishen.agentbridge.ui.FileRef;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionStoreV2Test {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    // ── Existing truncateSessionName tests ────────────────────────────────────

    @Test
    void sessionNameExtractedFromFirstPrompt() {
        String name = SessionStoreV2.truncateSessionName("Fix the auth bug in the login page and update tests");
        assertEquals("Fix the auth bug in the login page and update tests", name);
    }

    @Test
    void sessionNameTruncatedAt60Chars() {
        String longPrompt = "This is a very long prompt that exceeds the sixty character limit for session names and should be truncated";
        String name = SessionStoreV2.truncateSessionName(longPrompt);
        assertTrue(name.length() <= 61, "name should be at most 60 chars + ellipsis");
        assertTrue(name.endsWith("…"), "truncated name should end with ellipsis");
    }

    @Test
    void sessionNameWhitespaceCollapsed() {
        String name = SessionStoreV2.truncateSessionName("  Fix   the\n  bug  ");
        assertEquals("Fix the bug", name);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // parseJsonlAutoDetect tests
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void parseJsonlAutoDetect_newFormatEntries() {
        String jsonl = "{\"type\":\"prompt\",\"text\":\"Hello world\",\"timestamp\":\"2024-01-01T00:00:00Z\",\"entryId\":\"e1\"}\n"
            + "{\"type\":\"text\",\"raw\":\"Hi there\",\"timestamp\":\"2024-01-01T00:00:01Z\",\"agent\":\"copilot\",\"model\":\"gpt-4\",\"entryId\":\"e2\"}\n";

        List<EntryData> entries = SessionStoreV2.parseJsonlAutoDetect(jsonl);

        assertNotNull(entries);
        assertEquals(2, entries.size());
        assertInstanceOf(EntryData.Prompt.class, entries.get(0));
        assertInstanceOf(EntryData.Text.class, entries.get(1));

        EntryData.Prompt prompt = (EntryData.Prompt) entries.get(0);
        assertEquals("Hello world", prompt.getText());
        assertEquals("2024-01-01T00:00:00Z", prompt.getTimestamp());
        assertEquals("e1", prompt.getEntryId());

        EntryData.Text text = (EntryData.Text) entries.get(1);
        assertEquals("Hi there", text.getRaw());
        assertEquals("copilot", text.getAgent());
        assertEquals("gpt-4", text.getModel());
        assertEquals("e2", text.getEntryId());
    }

    @Test
    void parseJsonlAutoDetect_legacyFormatEntries() {
        // Legacy separator lines do NOT contain "type:" at the top level
        // so isEntryFormat returns false → classified as legacy → convertLegacyMessages
        String jsonl = "{\"role\":\"separator\",\"createdAt\":1704067200000}\n"
            + "{\"role\":\"separator\",\"createdAt\":1704153600000,\"agent\":\"copilot\"}\n";

        List<EntryData> entries = SessionStoreV2.parseJsonlAutoDetect(jsonl);

        assertNotNull(entries);
        assertEquals(2, entries.size());
        assertInstanceOf(EntryData.SessionSeparator.class, entries.get(0));
        assertInstanceOf(EntryData.SessionSeparator.class, entries.get(1));
        assertEquals("copilot", ((EntryData.SessionSeparator) entries.get(1)).getAgent());
    }

    @Test
    void parseJsonlAutoDetect_mixedContent_newFormatWins() {
        // When both new-format and legacy lines are present, new format wins
        String jsonl = "{\"type\":\"prompt\",\"text\":\"New format\",\"entryId\":\"e1\"}\n"
            + "{\"role\":\"separator\",\"createdAt\":1704067200000}\n";

        List<EntryData> entries = SessionStoreV2.parseJsonlAutoDetect(jsonl);

        assertNotNull(entries);
        assertEquals(1, entries.size());
        assertInstanceOf(EntryData.Prompt.class, entries.get(0));
        assertEquals("New format", ((EntryData.Prompt) entries.get(0)).getText());
    }

    @Test
    void parseJsonlAutoDetect_emptyContent_returnsNull() {
        assertNull(SessionStoreV2.parseJsonlAutoDetect(""));
        assertNull(SessionStoreV2.parseJsonlAutoDetect("   \n  \n  "));
    }

    @Test
    void parseJsonlAutoDetect_malformedLines_skipped() {
        String jsonl = "not json at all\n"
            + "{\"type\":\"prompt\",\"text\":\"Valid\",\"entryId\":\"e1\"}\n"
            + "{broken json\n";

        List<EntryData> entries = SessionStoreV2.parseJsonlAutoDetect(jsonl);

        assertNotNull(entries);
        assertEquals(1, entries.size());
        assertInstanceOf(EntryData.Prompt.class, entries.get(0));
        assertEquals("Valid", ((EntryData.Prompt) entries.get(0)).getText());
    }

    @Test
    void parseJsonlAutoDetect_allMalformed_returnsNull() {
        String jsonl = "garbage line 1\n{broken json\n";
        assertNull(SessionStoreV2.parseJsonlAutoDetect(jsonl));
    }

    @Test
    void parseJsonlAutoDetect_unknownNewFormatType_skipped() {
        // A line with "type" but an unknown value is silently skipped
        String jsonl = "{\"type\":\"future_unknown_entry\",\"data\":\"something\"}\n"
            + "{\"type\":\"prompt\",\"text\":\"Hello\",\"entryId\":\"e1\"}\n";

        List<EntryData> entries = SessionStoreV2.parseJsonlAutoDetect(jsonl);

        assertNotNull(entries);
        assertEquals(1, entries.size());
        assertEquals("Hello", ((EntryData.Prompt) entries.get(0)).getText());
    }

    @Test
    void parseJsonlAutoDetect_allNewFormatTypes() {
        // Build one of each new-format type and verify all parse
        String jsonl = "{\"type\":\"prompt\",\"text\":\"Q\",\"entryId\":\"p1\"}\n"
            + "{\"type\":\"text\",\"raw\":\"A\",\"entryId\":\"t1\"}\n"
            + "{\"type\":\"thinking\",\"raw\":\"T\",\"entryId\":\"th1\"}\n"
            + "{\"type\":\"tool\",\"title\":\"read\",\"entryId\":\"tc1\"}\n"
            + "{\"type\":\"subagent\",\"agentType\":\"explore\",\"description\":\"D\",\"entryId\":\"sa1\"}\n"
            + "{\"type\":\"context\",\"files\":[{\"name\":\"A\",\"path\":\"/a\"}],\"entryId\":\"cf1\"}\n"
            + "{\"type\":\"status\",\"icon\":\"i\",\"message\":\"m\",\"entryId\":\"st1\"}\n"
            + "{\"type\":\"separator\",\"timestamp\":\"ts\",\"entryId\":\"sep1\"}\n"
            + "{\"type\":\"turnStats\",\"turnId\":\"turn1\",\"entryId\":\"ts1\"}\n";

        List<EntryData> entries = SessionStoreV2.parseJsonlAutoDetect(jsonl);

        assertNotNull(entries);
        assertEquals(9, entries.size());
        assertInstanceOf(EntryData.Prompt.class, entries.get(0));
        assertInstanceOf(EntryData.Text.class, entries.get(1));
        assertInstanceOf(EntryData.Thinking.class, entries.get(2));
        assertInstanceOf(EntryData.ToolCall.class, entries.get(3));
        assertInstanceOf(EntryData.SubAgent.class, entries.get(4));
        assertInstanceOf(EntryData.ContextFiles.class, entries.get(5));
        assertInstanceOf(EntryData.Status.class, entries.get(6));
        assertInstanceOf(EntryData.SessionSeparator.class, entries.get(7));
        assertInstanceOf(EntryData.TurnStats.class, entries.get(8));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // convertLegacyMessages tests
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void convertLegacy_userTextBecomesPrompt() {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        msg.addProperty("createdAt", 1704067200000L); // 2024-01-01T00:00:00Z

        JsonArray parts = new JsonArray();
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", "Hello");
        parts.add(textPart);
        msg.add("parts", parts);

        List<EntryData> result = SessionStoreV2.convertLegacyMessages(List.of(msg));

        assertEquals(1, result.size());
        assertInstanceOf(EntryData.Prompt.class, result.get(0));
        EntryData.Prompt prompt = (EntryData.Prompt) result.get(0);
        assertEquals("Hello", prompt.getText());
        assertTrue(prompt.getTimestamp().contains("2024-01-01"));
    }

    @Test
    void convertLegacy_assistantTextBecomesText() {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "assistant");
        msg.addProperty("createdAt", 1704067200000L);
        msg.addProperty("agent", "copilot");
        msg.addProperty("model", "gpt-4");

        JsonArray parts = new JsonArray();
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", "Hi there");
        parts.add(textPart);
        msg.add("parts", parts);

        List<EntryData> result = SessionStoreV2.convertLegacyMessages(List.of(msg));

        assertEquals(1, result.size());
        assertInstanceOf(EntryData.Text.class, result.get(0));
        EntryData.Text text = (EntryData.Text) result.get(0);
        assertEquals("Hi there", text.getRaw());
        assertEquals("copilot", text.getAgent());
        assertEquals("gpt-4", text.getModel());
    }

    @Test
    void convertLegacy_toolInvocationBecomesToolCall() {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "assistant");
        msg.addProperty("agent", "copilot");
        msg.addProperty("model", "gpt-4");

        JsonArray parts = new JsonArray();
        JsonObject toolPart = new JsonObject();
        toolPart.addProperty("type", "tool-invocation");
        JsonObject toolInvocation = new JsonObject();
        toolInvocation.addProperty("toolName", "readFile");
        toolInvocation.addProperty("args", "{\"path\":\"a.txt\"}");
        toolInvocation.addProperty("result", "file content here");
        toolInvocation.addProperty("kind", "filesystem");
        toolInvocation.addProperty("status", "completed");
        toolInvocation.addProperty("description", "Read a file");
        toolInvocation.addProperty("filePath", "/src/a.txt");
        toolPart.add("toolInvocation", toolInvocation);
        parts.add(toolPart);
        msg.add("parts", parts);

        List<EntryData> result = SessionStoreV2.convertLegacyMessages(List.of(msg));

        // Tool call only — no trailing empty text appended
        assertEquals(1, result.size());
        assertInstanceOf(EntryData.ToolCall.class, result.get(0));
        EntryData.ToolCall tc = (EntryData.ToolCall) result.get(0);
        assertEquals("readFile", tc.getTitle());
        assertEquals("{\"path\":\"a.txt\"}", tc.getArguments());
        assertEquals("file content here", tc.getResult());
        assertEquals("filesystem", tc.getKind());
        assertEquals("completed", tc.getStatus());
        assertEquals("Read a file", tc.getDescription());
        assertEquals("/src/a.txt", tc.getFilePath());
        assertEquals("copilot", tc.getAgent());
        assertEquals("gpt-4", tc.getModel());
    }

    @Test
    void convertLegacy_toolWithDenialReason() {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "assistant");
        msg.addProperty("agent", "copilot");

        JsonArray parts = new JsonArray();
        JsonObject toolPart = new JsonObject();
        toolPart.addProperty("type", "tool-invocation");
        JsonObject inv = new JsonObject();
        inv.addProperty("toolName", "deleteFile");
        inv.addProperty("denialReason", "Operation not allowed");
        inv.addProperty("mcpHandled", true);
        toolPart.add("toolInvocation", inv);
        parts.add(toolPart);
        msg.add("parts", parts);

        List<EntryData> result = SessionStoreV2.convertLegacyMessages(List.of(msg));

        assertEquals(1, result.size()); // tool only, no trailing empty text
        EntryData.ToolCall tc = (EntryData.ToolCall) result.get(0);
        assertTrue(tc.getAutoDenied());
        assertEquals("Operation not allowed", tc.getDenialReason());
        assertEquals("deleteFile", tc.getPluginTool());
    }

    @Test
    void convertLegacy_reasoningBecomesThinking() {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "assistant");
        msg.addProperty("agent", "copilot");
        msg.addProperty("model", "gpt-4");

        JsonArray parts = new JsonArray();
        JsonObject reasoningPart = new JsonObject();
        reasoningPart.addProperty("type", "reasoning");
        reasoningPart.addProperty("text", "Let me think about this...");
        parts.add(reasoningPart);
        msg.add("parts", parts);

        List<EntryData> result = SessionStoreV2.convertLegacyMessages(List.of(msg));

        // Reasoning sets hasTextOrThinking → no trailing empty text
        assertEquals(1, result.size());
        assertInstanceOf(EntryData.Thinking.class, result.get(0));
        EntryData.Thinking thinking = (EntryData.Thinking) result.get(0);
        assertEquals("Let me think about this...", thinking.getRaw());
        assertEquals("copilot", thinking.getAgent());
        assertEquals("gpt-4", thinking.getModel());
    }

    @Test
    void convertLegacy_subagentBecomesSubAgent() {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "assistant");
        msg.addProperty("agent", "copilot");
        msg.addProperty("model", "gpt-4");

        JsonArray parts = new JsonArray();
        JsonObject subagentPart = new JsonObject();
        subagentPart.addProperty("type", "subagent");
        subagentPart.addProperty("agentType", "explore");
        subagentPart.addProperty("description", "Exploring the codebase");
        subagentPart.addProperty("prompt", "Find all test files");
        subagentPart.addProperty("result", "Found 42 test files");
        subagentPart.addProperty("status", "completed");
        subagentPart.addProperty("colorIndex", 2);
        subagentPart.addProperty("callId", "call-123");
        parts.add(subagentPart);
        msg.add("parts", parts);

        List<EntryData> result = SessionStoreV2.convertLegacyMessages(List.of(msg));

        // SubAgent only, no trailing empty text
        assertEquals(1, result.size());
        assertInstanceOf(EntryData.SubAgent.class, result.get(0));
        EntryData.SubAgent sa = (EntryData.SubAgent) result.get(0);
        assertEquals("explore", sa.getAgentType());
        assertEquals("Exploring the codebase", sa.getDescription());
        assertEquals("Find all test files", sa.getPrompt());
        assertEquals("Found 42 test files", sa.getResult());
        assertEquals("completed", sa.getStatus());
        assertEquals(2, sa.getColorIndex());
        assertEquals("call-123", sa.getCallId());
        assertEquals("copilot", sa.getAgent());
        assertEquals("gpt-4", sa.getModel());
    }

    @Test
    void convertLegacy_subagentAutoDenied() {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "assistant");

        JsonArray parts = new JsonArray();
        JsonObject subagentPart = new JsonObject();
        subagentPart.addProperty("type", "subagent");
        subagentPart.addProperty("agentType", "general-purpose");
        subagentPart.addProperty("description", "Denied agent");
        subagentPart.addProperty("autoDenied", true);
        subagentPart.addProperty("denialReason", "Not allowed");
        parts.add(subagentPart);
        msg.add("parts", parts);

        List<EntryData> result = SessionStoreV2.convertLegacyMessages(List.of(msg));

        assertEquals(1, result.size()); // subagent only, no trailing empty text
        EntryData.SubAgent sa = (EntryData.SubAgent) result.get(0);
        assertTrue(sa.getAutoDenied());
        assertEquals("Not allowed", sa.getDenialReason());
    }

    @Test
    void convertLegacy_separatorBecomesSeparator() {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "separator");
        msg.addProperty("createdAt", 1704067200000L); // 2024-01-01T00:00:00Z
        msg.addProperty("agent", "copilot");

        List<EntryData> result = SessionStoreV2.convertLegacyMessages(List.of(msg));

        assertEquals(1, result.size());
        assertInstanceOf(EntryData.SessionSeparator.class, result.get(0));
        EntryData.SessionSeparator sep = (EntryData.SessionSeparator) result.get(0);
        assertEquals("copilot", sep.getAgent());
        assertEquals("2024-01-01T00:00:00Z", sep.getTimestamp());
    }

    @Test
    void convertLegacy_separatorWithNullAgent() {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "separator");
        msg.addProperty("createdAt", 1704067200000L);
        // No agent field

        List<EntryData> result = SessionStoreV2.convertLegacyMessages(List.of(msg));

        assertEquals(1, result.size());
        EntryData.SessionSeparator sep = (EntryData.SessionSeparator) result.get(0);
        assertEquals("", sep.getAgent());
    }

    @Test
    void convertLegacy_userTextWithFileParts() {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");

        JsonArray parts = new JsonArray();

        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", "Fix this file");
        parts.add(textPart);

        JsonObject filePart1 = new JsonObject();
        filePart1.addProperty("type", "file");
        filePart1.addProperty("filename", "Main.java");
        filePart1.addProperty("path", "/src/Main.java");
        filePart1.addProperty("line", 42);
        parts.add(filePart1);

        JsonObject filePart2 = new JsonObject();
        filePart2.addProperty("type", "file");
        filePart2.addProperty("filename", "Test.java");
        filePart2.addProperty("path", "/test/Test.java");
        // No line → defaults to 0
        parts.add(filePart2);

        msg.add("parts", parts);

        List<EntryData> result = SessionStoreV2.convertLegacyMessages(List.of(msg));

        assertEquals(1, result.size());
        assertInstanceOf(EntryData.Prompt.class, result.get(0));
        EntryData.Prompt prompt = (EntryData.Prompt) result.get(0);
        assertEquals("Fix this file", prompt.getText());
        assertNotNull(prompt.getContextFiles());
        assertEquals(2, prompt.getContextFiles().size());
        assertEquals("Main.java", prompt.getContextFiles().get(0).getName());
        assertEquals("/src/Main.java", prompt.getContextFiles().get(0).getPath());
        assertEquals(42, (int) prompt.getContextFiles().get(0).getLine());
        assertEquals("Test.java", prompt.getContextFiles().get(1).getName());
        assertEquals("/test/Test.java", prompt.getContextFiles().get(1).getPath());
        assertEquals(0, (int) prompt.getContextFiles().get(1).getLine());
    }

    @Test
    void convertLegacy_assistantToolsOnly_noTrailingEmptyText() {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "assistant");
        msg.addProperty("agent", "copilot");

        JsonArray parts = new JsonArray();
        JsonObject toolPart = new JsonObject();
        toolPart.addProperty("type", "tool-invocation");
        JsonObject inv = new JsonObject();
        inv.addProperty("toolName", "runCommand");
        toolPart.add("toolInvocation", inv);
        parts.add(toolPart);
        msg.add("parts", parts);

        List<EntryData> result = SessionStoreV2.convertLegacyMessages(List.of(msg));

        assertEquals(1, result.size());
        assertInstanceOf(EntryData.ToolCall.class, result.get(0));
    }

    @Test
    void convertLegacy_assistantWithToolAndText_noTrailingEmptyText() {
        // When assistant has both tool and text parts, no trailing empty text is added
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "assistant");
        msg.addProperty("agent", "copilot");

        JsonArray parts = new JsonArray();

        JsonObject toolPart = new JsonObject();
        toolPart.addProperty("type", "tool-invocation");
        JsonObject inv = new JsonObject();
        inv.addProperty("toolName", "readFile");
        toolPart.add("toolInvocation", inv);
        parts.add(toolPart);

        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", "I read the file");
        parts.add(textPart);

        msg.add("parts", parts);

        List<EntryData> result = SessionStoreV2.convertLegacyMessages(List.of(msg));

        assertEquals(2, result.size());
        assertInstanceOf(EntryData.ToolCall.class, result.get(0));
        assertInstanceOf(EntryData.Text.class, result.get(1));
        assertEquals("I read the file", ((EntryData.Text) result.get(1)).getRaw());
    }

    @Test
    void convertLegacy_timestampFromCreatedAt() {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "separator");
        msg.addProperty("createdAt", 1704067200000L); // 2024-01-01T00:00:00Z

        List<EntryData> result = SessionStoreV2.convertLegacyMessages(List.of(msg));

        assertEquals(1, result.size());
        String ts = ((EntryData.SessionSeparator) result.get(0)).getTimestamp();
        assertEquals("2024-01-01T00:00:00Z", ts);
    }

    @Test
    void convertLegacy_zeroCreatedAt_emptyTimestamp() {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "assistant");
        msg.addProperty("createdAt", 0);

        JsonArray parts = new JsonArray();
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", "Hello");
        parts.add(textPart);
        msg.add("parts", parts);

        List<EntryData> result = SessionStoreV2.convertLegacyMessages(List.of(msg));

        assertEquals(1, result.size());
        assertEquals("", ((EntryData.Text) result.get(0)).getTimestamp());
    }

    @Test
    void convertLegacy_missingCreatedAt_emptyTimestamp() {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "assistant");
        // No createdAt at all

        JsonArray parts = new JsonArray();
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", "Hello");
        parts.add(textPart);
        msg.add("parts", parts);

        List<EntryData> result = SessionStoreV2.convertLegacyMessages(List.of(msg));

        assertEquals(1, result.size());
        assertEquals("", ((EntryData.Text) result.get(0)).getTimestamp());
    }

    @Test
    void convertLegacy_partLevelTimestampOverridesMessage() {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "assistant");
        msg.addProperty("createdAt", 1704067200000L); // 2024-01-01T00:00:00Z

        JsonArray parts = new JsonArray();
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", "Hello");
        textPart.addProperty("ts", "2024-06-15T12:00:00Z");
        parts.add(textPart);
        msg.add("parts", parts);

        List<EntryData> result = SessionStoreV2.convertLegacyMessages(List.of(msg));

        assertEquals(1, result.size());
        EntryData.Text text = (EntryData.Text) result.get(0);
        assertEquals("2024-06-15T12:00:00Z", text.getTimestamp());
    }

    @Test
    void convertLegacy_emptyPartTimestamp_fallsBackToMessage() {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "assistant");
        msg.addProperty("createdAt", 1704067200000L);

        JsonArray parts = new JsonArray();
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", "Hello");
        textPart.addProperty("ts", ""); // empty ts → falls back to message-level
        parts.add(textPart);
        msg.add("parts", parts);

        List<EntryData> result = SessionStoreV2.convertLegacyMessages(List.of(msg));

        assertEquals(1, result.size());
        assertEquals("2024-01-01T00:00:00Z", ((EntryData.Text) result.get(0)).getTimestamp());
    }

    @Test
    void convertLegacy_entryIdFromEid() {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "assistant");

        JsonArray parts = new JsonArray();
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", "Hello");
        textPart.addProperty("eid", "custom-entry-id-123");
        parts.add(textPart);
        msg.add("parts", parts);

        List<EntryData> result = SessionStoreV2.convertLegacyMessages(List.of(msg));

        assertEquals(1, result.size());
        assertEquals("custom-entry-id-123", result.get(0).getEntryId());
    }

    @Test
    void convertLegacy_noEidGeneratesUuid() {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "assistant");

        JsonArray parts = new JsonArray();
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", "Hello");
        // No "eid" field
        parts.add(textPart);
        msg.add("parts", parts);

        List<EntryData> result = SessionStoreV2.convertLegacyMessages(List.of(msg));

        assertEquals(1, result.size());
        String entryId = result.get(0).getEntryId();
        assertNotNull(entryId);
        assertFalse(entryId.isEmpty());
        // Verify it's a valid UUID
        assertDoesNotThrow(() -> java.util.UUID.fromString(entryId));
    }

    @Test
    void convertLegacy_nullAgentAndModel_defaultsToEmpty() {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "assistant");
        // No agent or model set

        JsonArray parts = new JsonArray();
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", "Hello");
        parts.add(textPart);
        msg.add("parts", parts);

        List<EntryData> result = SessionStoreV2.convertLegacyMessages(List.of(msg));

        assertEquals(1, result.size());
        EntryData.Text text = (EntryData.Text) result.get(0);
        assertEquals("", text.getAgent());
        assertEquals("", text.getModel());
    }

    @Test
    void convertLegacy_statusPartBecomesStatus() {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "assistant");

        JsonArray parts = new JsonArray();
        JsonObject statusPart = new JsonObject();
        statusPart.addProperty("type", "status");
        statusPart.addProperty("icon", "⚠");
        statusPart.addProperty("message", "Rate limited");
        parts.add(statusPart);
        msg.add("parts", parts);

        List<EntryData> result = SessionStoreV2.convertLegacyMessages(List.of(msg));

        // status only, no trailing empty text
        assertEquals(1, result.size());
        assertInstanceOf(EntryData.Status.class, result.get(0));
        EntryData.Status st = (EntryData.Status) result.get(0);
        assertEquals("⚠", st.getIcon());
        assertEquals("Rate limited", st.getMessage());
    }

    @Test
    void convertLegacy_standaloneFilePart_becomesContextFiles() {
        // File parts NOT preceded by a text part → ContextFiles entry
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");

        JsonArray parts = new JsonArray();
        JsonObject filePart = new JsonObject();
        filePart.addProperty("type", "file");
        filePart.addProperty("filename", "Readme.md");
        filePart.addProperty("path", "/Readme.md");
        parts.add(filePart);
        msg.add("parts", parts);

        List<EntryData> result = SessionStoreV2.convertLegacyMessages(List.of(msg));

        assertEquals(1, result.size());
        assertInstanceOf(EntryData.ContextFiles.class, result.get(0));
        EntryData.ContextFiles cf = (EntryData.ContextFiles) result.get(0);
        assertEquals(1, cf.getFiles().size());
        assertEquals("Readme.md", cf.getFiles().get(0).getName());
        assertEquals("/Readme.md", cf.getFiles().get(0).getPath());
    }

    @Test
    void convertLegacy_multipleMessages_preservesOrder() {
        // Simulate a conversation: user → assistant → user → assistant
        JsonObject userMsg1 = userMessage("First question");
        JsonObject assistantMsg1 = assistantMessage("First answer", "copilot", "gpt-4");
        JsonObject userMsg2 = userMessage("Second question");
        JsonObject assistantMsg2 = assistantMessage("Second answer", "copilot", "gpt-4");

        List<EntryData> result = SessionStoreV2.convertLegacyMessages(
            List.of(userMsg1, assistantMsg1, userMsg2, assistantMsg2));

        assertEquals(4, result.size());
        assertInstanceOf(EntryData.Prompt.class, result.get(0));
        assertInstanceOf(EntryData.Text.class, result.get(1));
        assertInstanceOf(EntryData.Prompt.class, result.get(2));
        assertInstanceOf(EntryData.Text.class, result.get(3));
        assertEquals("First question", ((EntryData.Prompt) result.get(0)).getText());
        assertEquals("First answer", ((EntryData.Text) result.get(1)).getRaw());
        assertEquals("Second question", ((EntryData.Prompt) result.get(2)).getText());
        assertEquals("Second answer", ((EntryData.Text) result.get(3)).getRaw());
    }

    @Test
    void convertLegacy_unknownPartType_skipped() {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "assistant");

        JsonArray parts = new JsonArray();
        JsonObject unknownPart = new JsonObject();
        unknownPart.addProperty("type", "future_part_type");
        unknownPart.addProperty("data", "something");
        parts.add(unknownPart);

        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", "Known part");
        parts.add(textPart);

        msg.add("parts", parts);

        List<EntryData> result = SessionStoreV2.convertLegacyMessages(List.of(msg));

        assertEquals(1, result.size());
        assertInstanceOf(EntryData.Text.class, result.get(0));
        assertEquals("Known part", ((EntryData.Text) result.get(0)).getRaw());
    }

    @Test
    void convertLegacy_emptyParts_producesNoEntries() {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "assistant");
        msg.add("parts", new JsonArray());

        List<EntryData> result = SessionStoreV2.convertLegacyMessages(List.of(msg));

        // No entries before, no entries after → no trailing text either
        assertTrue(result.isEmpty());
    }

    @Test
    void convertLegacy_missingParts_producesNoEntries() {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "assistant");
        // No "parts" key at all

        List<EntryData> result = SessionStoreV2.convertLegacyMessages(List.of(msg));

        assertTrue(result.isEmpty());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Round-trip tests (serialize → JSONL → parseJsonlAutoDetect)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void roundTrip_allEntryTypes() {
        List<EntryData> originals = List.of(
            new EntryData.Prompt("Test prompt", "2024-01-01T00:00:00Z", null, "", "p1"),
            new EntryData.Text("Response", "2024-01-01T00:00:01Z", "copilot", "gpt-4", "t1"),
            new EntryData.Thinking("Reasoning", "2024-01-01T00:00:02Z", "copilot", "gpt-4", "th1"),
            new EntryData.ToolCall("readFile", "{\"path\":\"a.txt\"}", "filesystem",
                "content", "completed", "Read file", "/a.txt",
                false, null, null,
                "2024-01-01T00:00:03Z", "copilot", "gpt-4", "tc1"),
            new EntryData.SubAgent("explore", "Exploring", "Find files", "Found 3",
                "completed", 1, "call-1", false, null,
                "2024-01-01T00:00:04Z", "copilot", "gpt-4", "sa1"),
            new EntryData.ContextFiles(List.of(new FileRef("A.java", "/src/A.java")), "cf1"),
            new EntryData.Status("✓", "Done", "st1"),
            new EntryData.SessionSeparator("2024-01-01T00:00:05Z", "copilot", "sep1"),
            new EntryData.TurnStats("turn-1", 5000L, 1000L, 500L, 0.05, 3, 10, 2,
                "gpt-4", "1x", 10000L, 2000L, 1000L, 0.10, 6, 20, 4, "", "ts1")
        );

        String jsonl = toJsonl(originals);
        List<EntryData> loaded = SessionStoreV2.parseJsonlAutoDetect(jsonl);

        assertNotNull(loaded);
        assertEquals(originals.size(), loaded.size());

        assertInstanceOf(EntryData.Prompt.class, loaded.get(0));
        assertInstanceOf(EntryData.Text.class, loaded.get(1));
        assertInstanceOf(EntryData.Thinking.class, loaded.get(2));
        assertInstanceOf(EntryData.ToolCall.class, loaded.get(3));
        assertInstanceOf(EntryData.SubAgent.class, loaded.get(4));
        assertInstanceOf(EntryData.ContextFiles.class, loaded.get(5));
        assertInstanceOf(EntryData.Status.class, loaded.get(6));
        assertInstanceOf(EntryData.SessionSeparator.class, loaded.get(7));
        assertInstanceOf(EntryData.TurnStats.class, loaded.get(8));

        // Verify entry IDs survived the round-trip
        assertEquals("p1", loaded.get(0).getEntryId());
        assertEquals("t1", loaded.get(1).getEntryId());
        assertEquals("th1", loaded.get(2).getEntryId());
        assertEquals("tc1", loaded.get(3).getEntryId());
        assertEquals("sa1", loaded.get(4).getEntryId());
        assertEquals("cf1", loaded.get(5).getEntryId());
        assertEquals("st1", loaded.get(6).getEntryId());
        assertEquals("sep1", loaded.get(7).getEntryId());
        assertEquals("ts1", loaded.get(8).getEntryId());
    }

    @Test
    void roundTrip_promptPreservesContextFiles() {
        List<ContextFileRef> ctxFiles = List.of(
            new ContextFileRef("Main.java", "/src/Main.java", 42),
            new ContextFileRef("Test.java", "/test/Test.java", 0)
        );
        EntryData.Prompt original = new EntryData.Prompt(
            "Fix bugs", "2024-01-01T00:00:00Z", ctxFiles, "id1", "eid1");

        String jsonl = toJsonl(List.of(original));
        List<EntryData> loaded = SessionStoreV2.parseJsonlAutoDetect(jsonl);

        assertNotNull(loaded);
        assertEquals(1, loaded.size());
        EntryData.Prompt p = (EntryData.Prompt) loaded.get(0);
        assertEquals("Fix bugs", p.getText());
        assertEquals("2024-01-01T00:00:00Z", p.getTimestamp());
        assertEquals("id1", p.getId());
        assertEquals("eid1", p.getEntryId());
        assertNotNull(p.getContextFiles());
        assertEquals(2, p.getContextFiles().size());
        assertEquals("Main.java", p.getContextFiles().get(0).getName());
        assertEquals("/src/Main.java", p.getContextFiles().get(0).getPath());
        assertEquals(42, (int) p.getContextFiles().get(0).getLine());
        assertEquals("Test.java", p.getContextFiles().get(1).getName());
        assertEquals("/test/Test.java", p.getContextFiles().get(1).getPath());
        assertEquals(0, (int) p.getContextFiles().get(1).getLine());
    }

    @Test
    void roundTrip_promptWithNullContextFiles() {
        EntryData.Prompt original = new EntryData.Prompt(
            "Simple question", "2024-01-01T00:00:00Z", null, "", "eid1");

        String jsonl = toJsonl(List.of(original));
        List<EntryData> loaded = SessionStoreV2.parseJsonlAutoDetect(jsonl);

        assertNotNull(loaded);
        assertEquals(1, loaded.size());
        EntryData.Prompt p = (EntryData.Prompt) loaded.get(0);
        assertEquals("Simple question", p.getText());
        assertNull(p.getContextFiles());
    }

    @Test
    void roundTrip_toolCallPreservesAllFields() {
        EntryData.ToolCall original = new EntryData.ToolCall(
            "editFile",
            "{\"path\":\"/a.txt\",\"content\":\"x\"}",
            "filesystem",
            "Applied edit",
            "completed",
            "Edit a file",
            "/a.txt",
            true,
            "User declined",
            "editFile",
            "2024-01-01T00:00:00Z",
            "copilot",
            "gpt-4",
            "tc-full"
        );

        String jsonl = toJsonl(List.of(original));
        List<EntryData> loaded = SessionStoreV2.parseJsonlAutoDetect(jsonl);

        assertNotNull(loaded);
        assertEquals(1, loaded.size());
        EntryData.ToolCall tc = (EntryData.ToolCall) loaded.get(0);
        assertEquals("editFile", tc.getTitle());
        assertEquals("{\"path\":\"/a.txt\",\"content\":\"x\"}", tc.getArguments());
        assertEquals("filesystem", tc.getKind());
        assertEquals("Applied edit", tc.getResult());
        assertEquals("completed", tc.getStatus());
        assertEquals("Edit a file", tc.getDescription());
        assertEquals("/a.txt", tc.getFilePath());
        assertTrue(tc.getAutoDenied());
        assertEquals("User declined", tc.getDenialReason());
        assertEquals("editFile", tc.getPluginTool());
        assertEquals("2024-01-01T00:00:00Z", tc.getTimestamp());
        assertEquals("copilot", tc.getAgent());
        assertEquals("gpt-4", tc.getModel());
        assertEquals("tc-full", tc.getEntryId());
    }

    @Test
    void roundTrip_toolCallWithNullOptionalFields() {
        EntryData.ToolCall original = new EntryData.ToolCall(
            "simpleRead",
            null,       // arguments
            "other",
            null,       // result
            null,       // status
            null,       // description
            null,       // filePath
            false,
            null,       // denialReason
            null,
            "",         // timestamp
            "",         // agent
            "",         // model
            "tc-minimal"
        );

        String jsonl = toJsonl(List.of(original));
        List<EntryData> loaded = SessionStoreV2.parseJsonlAutoDetect(jsonl);

        assertNotNull(loaded);
        assertEquals(1, loaded.size());
        EntryData.ToolCall tc = (EntryData.ToolCall) loaded.get(0);
        assertEquals("simpleRead", tc.getTitle());
        assertNull(tc.getArguments());
        assertNull(tc.getResult());
        assertNull(tc.getStatus());
        assertNull(tc.getDescription());
        assertNull(tc.getFilePath());
        assertFalse(tc.getAutoDenied());
        assertNull(tc.getDenialReason());
        assertNull(tc.getPluginTool());
    }

    @Test
    void roundTrip_turnStatsPreservesMetrics() {
        EntryData.TurnStats original = new EntryData.TurnStats(
            "turn-42",
            12345L,    // durationMs
            5000L,     // inputTokens
            2500L,     // outputTokens
            0.123,     // costUsd
            7,         // toolCallCount
            150,       // linesAdded
            30,        // linesRemoved
            "claude-3",
            "2x",
            50000L,    // totalDurationMs
            20000L,    // totalInputTokens
            10000L,    // totalOutputTokens
            0.5,       // totalCostUsd
            25,        // totalToolCalls
            500,       // totalLinesAdded
            100,       // totalLinesRemoved
            "",        // timestamp
            "ts-42"
        );

        String jsonl = toJsonl(List.of(original));
        List<EntryData> loaded = SessionStoreV2.parseJsonlAutoDetect(jsonl);

        assertNotNull(loaded);
        assertEquals(1, loaded.size());
        EntryData.TurnStats ts = (EntryData.TurnStats) loaded.get(0);
        assertEquals("turn-42", ts.getTurnId());
        assertEquals(12345L, ts.getDurationMs());
        assertEquals(5000L, ts.getInputTokens());
        assertEquals(2500L, ts.getOutputTokens());
        assertEquals(0.123, ts.getCostUsd(), 0.0001);
        assertEquals(7, ts.getToolCallCount());
        assertEquals(150, ts.getLinesAdded());
        assertEquals(30, ts.getLinesRemoved());
        assertEquals("claude-3", ts.getModel());
        assertEquals("2x", ts.getMultiplier());
        assertEquals(50000L, ts.getTotalDurationMs());
        assertEquals(20000L, ts.getTotalInputTokens());
        assertEquals(10000L, ts.getTotalOutputTokens());
        assertEquals(0.5, ts.getTotalCostUsd(), 0.0001);
        assertEquals(25, ts.getTotalToolCalls());
        assertEquals(500, ts.getTotalLinesAdded());
        assertEquals(100, ts.getTotalLinesRemoved());
        assertEquals("ts-42", ts.getEntryId());
    }

    @Test
    void roundTrip_turnStatsWithZeroValues() {
        EntryData.TurnStats original = new EntryData.TurnStats(
            "turn-empty", 0, 0, 0, 0.0, 0, 0, 0, "", "", 0, 0, 0, 0.0, 0, 0, 0, "", "ts-zero");

        String jsonl = toJsonl(List.of(original));
        List<EntryData> loaded = SessionStoreV2.parseJsonlAutoDetect(jsonl);

        assertNotNull(loaded);
        assertEquals(1, loaded.size());
        EntryData.TurnStats ts = (EntryData.TurnStats) loaded.get(0);
        assertEquals("turn-empty", ts.getTurnId());
        assertEquals(0L, ts.getDurationMs());
        assertEquals(0L, ts.getInputTokens());
        assertEquals(0.0, ts.getCostUsd());
        assertEquals(0, ts.getToolCallCount());
    }

    @Test
    void roundTrip_textWithSpecialCharacters() {
        String specialContent = "Line 1\nLine 2\tTabbed\nUnicode: 日本語 émojis 🎉\n"
            + "JSON: {\"key\": \"value\"}\nBackslash: C:\\path\\to\\file";
        EntryData.Text original = new EntryData.Text(
            specialContent,
            "2024-01-01T00:00:00Z", "copilot", "gpt-4", "special-1");

        String jsonl = toJsonl(List.of(original));
        List<EntryData> loaded = SessionStoreV2.parseJsonlAutoDetect(jsonl);

        assertNotNull(loaded);
        assertEquals(1, loaded.size());
        EntryData.Text text = (EntryData.Text) loaded.get(0);
        assertEquals(specialContent, text.getRaw());
    }

    @Test
    void roundTrip_textWithEmptyContent() {
        EntryData.Text original = new EntryData.Text(
            "", "2024-01-01T00:00:00Z", "copilot", "gpt-4", "empty-1");

        String jsonl = toJsonl(List.of(original));
        List<EntryData> loaded = SessionStoreV2.parseJsonlAutoDetect(jsonl);

        assertNotNull(loaded);
        assertEquals(1, loaded.size());
        EntryData.Text text = (EntryData.Text) loaded.get(0);
        assertEquals("", text.getRaw());
    }

    @Test
    void roundTrip_subAgentPreservesAllFields() {
        EntryData.SubAgent original = new EntryData.SubAgent(
            "code-review",
            "Reviewing code quality",
            "Check for bugs",
            "Found 3 issues",
            "completed",
            5,
            "call-abc",
            true,
            "Auto-denied for safety",
            "2024-01-01T00:00:00Z",
            "copilot",
            "gpt-4",
            "sa-full"
        );

        String jsonl = toJsonl(List.of(original));
        List<EntryData> loaded = SessionStoreV2.parseJsonlAutoDetect(jsonl);

        assertNotNull(loaded);
        assertEquals(1, loaded.size());
        EntryData.SubAgent sa = (EntryData.SubAgent) loaded.get(0);
        assertEquals("code-review", sa.getAgentType());
        assertEquals("Reviewing code quality", sa.getDescription());
        assertEquals("Check for bugs", sa.getPrompt());
        assertEquals("Found 3 issues", sa.getResult());
        assertEquals("completed", sa.getStatus());
        assertEquals(5, sa.getColorIndex());
        assertEquals("call-abc", sa.getCallId());
        assertTrue(sa.getAutoDenied());
        assertEquals("Auto-denied for safety", sa.getDenialReason());
        assertEquals("2024-01-01T00:00:00Z", sa.getTimestamp());
        assertEquals("copilot", sa.getAgent());
        assertEquals("gpt-4", sa.getModel());
        assertEquals("sa-full", sa.getEntryId());
    }

    @Test
    void roundTrip_sessionSeparatorPreservesFields() {
        EntryData.SessionSeparator original = new EntryData.SessionSeparator(
            "2024-01-01T00:00:00Z", "copilot", "sep-1");

        String jsonl = toJsonl(List.of(original));
        List<EntryData> loaded = SessionStoreV2.parseJsonlAutoDetect(jsonl);

        assertNotNull(loaded);
        assertEquals(1, loaded.size());
        EntryData.SessionSeparator sep = (EntryData.SessionSeparator) loaded.get(0);
        assertEquals("2024-01-01T00:00:00Z", sep.getTimestamp());
        assertEquals("copilot", sep.getAgent());
        assertEquals("sep-1", sep.getEntryId());
    }

    @Test
    void roundTrip_contextFilesPreservesFileList() {
        List<FileRef> files = List.of(
            new FileRef("Main.java", "/src/main/Main.java"),
            new FileRef("Utils.kt", "/src/main/Utils.kt")
        );
        EntryData.ContextFiles original = new EntryData.ContextFiles(files, "cf-1");

        String jsonl = toJsonl(List.of(original));
        List<EntryData> loaded = SessionStoreV2.parseJsonlAutoDetect(jsonl);

        assertNotNull(loaded);
        assertEquals(1, loaded.size());
        EntryData.ContextFiles cf = (EntryData.ContextFiles) loaded.get(0);
        assertEquals(2, cf.getFiles().size());
        assertEquals("Main.java", cf.getFiles().get(0).getName());
        assertEquals("/src/main/Main.java", cf.getFiles().get(0).getPath());
        assertEquals("Utils.kt", cf.getFiles().get(1).getName());
        assertEquals("/src/main/Utils.kt", cf.getFiles().get(1).getPath());
        assertEquals("cf-1", cf.getEntryId());
    }

    @Test
    void roundTrip_statusPreservesFields() {
        EntryData.Status original = new EntryData.Status("⚠", "Warning message", "status-1");

        String jsonl = toJsonl(List.of(original));
        List<EntryData> loaded = SessionStoreV2.parseJsonlAutoDetect(jsonl);

        assertNotNull(loaded);
        assertEquals(1, loaded.size());
        EntryData.Status st = (EntryData.Status) loaded.get(0);
        assertEquals("⚠", st.getIcon());
        assertEquals("Warning message", st.getMessage());
        assertEquals("status-1", st.getEntryId());
    }

    @Test
    void roundTrip_thinkingPreservesFields() {
        EntryData.Thinking original = new EntryData.Thinking(
            "Deep reasoning here",
            "2024-01-01T00:00:00Z", "copilot", "o1-preview", "think-1");

        String jsonl = toJsonl(List.of(original));
        List<EntryData> loaded = SessionStoreV2.parseJsonlAutoDetect(jsonl);

        assertNotNull(loaded);
        assertEquals(1, loaded.size());
        EntryData.Thinking th = (EntryData.Thinking) loaded.get(0);
        assertEquals("Deep reasoning here", th.getRaw());
        assertEquals("2024-01-01T00:00:00Z", th.getTimestamp());
        assertEquals("copilot", th.getAgent());
        assertEquals("o1-preview", th.getModel());
        assertEquals("think-1", th.getEntryId());
    }

    @Test
    void roundTrip_largeConversation() {
        // Verify a realistic conversation size round-trips correctly
        var entries = new java.util.ArrayList<EntryData>();
        for (int i = 0; i < 50; i++) {
            entries.add(new EntryData.Prompt("Question " + i, "", null, "", "p" + i));
            entries.add(new EntryData.ToolCall("tool" + i, null, "other", null, null, null, null,
                false, null, null, "", "", "", "tc" + i));
            entries.add(new EntryData.Text("Answer " + i, "", "agent", "model", "t" + i));
        }

        String jsonl = toJsonl(entries);
        List<EntryData> loaded = SessionStoreV2.parseJsonlAutoDetect(jsonl);

        assertNotNull(loaded);
        assertEquals(150, loaded.size());

        // Spot-check first and last entries
        assertEquals("p0", loaded.get(0).getEntryId());
        assertEquals("Question 0", ((EntryData.Prompt) loaded.get(0)).getText());
        assertEquals("t49", loaded.get(149).getEntryId());
        assertEquals("Answer 49", ((EntryData.Text) loaded.get(149)).getRaw());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Serializes a list of EntryData to a JSONL string (same format as SessionStoreV2.saveEntries).
     */
    private static String toJsonl(List<EntryData> entries) {
        StringBuilder sb = new StringBuilder();
        for (EntryData entry : entries) {
            sb.append(GSON.toJson(EntryDataJsonAdapter.serialize(entry))).append('\n');
        }
        return sb.toString();
    }

    /**
     * Creates a simple legacy user message with a text part.
     */
    private static JsonObject userMessage(String text) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        JsonArray parts = new JsonArray();
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", text);
        parts.add(textPart);
        msg.add("parts", parts);
        return msg;
    }

    /**
     * Creates a simple legacy assistant message with a text part.
     */
    private static JsonObject assistantMessage(String text, String agent, String model) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "assistant");
        msg.addProperty("agent", agent);
        msg.addProperty("model", model);
        JsonArray parts = new JsonArray();
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", text);
        parts.add(textPart);
        msg.add("parts", parts);
        return msg;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Instance method tests — file I/O via @TempDir
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("InstanceMethodTests")
    class InstanceMethodTests {

        @TempDir
        Path tempDir;

        private SessionStoreV2 newStore() {
            SessionStoreV2 store = new SessionStoreV2();
            store.setCurrentAgent("test-agent");
            return store;
        }

        /**
         * Returns the sessions directory for the given tempDir base path.
         */
        private Path sessionsDir() {
            return tempDir.resolve(".agent-work").resolve("sessions");
        }

        private Path currentSessionIdFile() {
            return sessionsDir().resolve(".current-session-id");
        }

        private Path sessionsIndexFile() {
            return sessionsDir().resolve("sessions-index.json");
        }

        // ── getCurrentSessionId ───────────────────────────────────────────────

        @Test
        @DisplayName("getCurrentSessionId creates the .current-session-id file on first call")
        void getCurrentSessionId_createsFileOnFirstCall() {
            SessionStoreV2 store = newStore();

            String id = store.getCurrentSessionId(tempDir.toString());

            assertNotNull(id);
            assertFalse(id.isBlank());
            assertTrue(currentSessionIdFile().toFile().exists(),
                ".current-session-id file should exist after first call");
        }

        @Test
        @DisplayName("getCurrentSessionId returns the same ID on repeated calls (cached on disk)")
        void getCurrentSessionId_returnsSameIdOnRepeatedCalls() {
            SessionStoreV2 store = newStore();

            String first = store.getCurrentSessionId(tempDir.toString());
            String second = store.getCurrentSessionId(tempDir.toString());
            String third = store.getCurrentSessionId(tempDir.toString());

            assertEquals(first, second);
            assertEquals(second, third);
        }

        @Test
        @DisplayName("after resetCurrentSessionId the next call generates a new ID")
        void getCurrentSessionId_newIdAfterReset() {
            SessionStoreV2 store = newStore();

            String original = store.getCurrentSessionId(tempDir.toString());
            store.resetCurrentSessionId(tempDir.toString());
            String fresh = store.getCurrentSessionId(tempDir.toString());

            assertNotEquals(original, fresh, "a fresh UUID should be generated after reset");
        }

        @Test
        @DisplayName("two different basePaths produce different session IDs")
        void getCurrentSessionId_differentBasePathsProduceDifferentIds(@TempDir Path other) {
            SessionStoreV2 store = newStore();

            String id1 = store.getCurrentSessionId(tempDir.toString());
            String id2 = store.getCurrentSessionId(other.toString());

            assertNotEquals(id1, id2);
        }

        // ── resetCurrentSessionId ─────────────────────────────────────────────

        @Test
        @DisplayName("resetCurrentSessionId deletes the .current-session-id file when it exists")
        void resetCurrentSessionId_deletesFile() throws IOException {
            SessionStoreV2 store = newStore();
            store.getCurrentSessionId(tempDir.toString()); // create the file
            assertTrue(currentSessionIdFile().toFile().exists(), "prerequisite: file must exist");

            store.resetCurrentSessionId(tempDir.toString());

            assertFalse(currentSessionIdFile().toFile().exists(), "file should be deleted");
        }

        @Test
        @DisplayName("resetCurrentSessionId is a no-op when the file does not exist")
        void resetCurrentSessionId_noOpWhenFileMissing() {
            SessionStoreV2 store = newStore();
            assertFalse(currentSessionIdFile().toFile().exists(), "prerequisite: file must not exist");

            assertDoesNotThrow(() -> store.resetCurrentSessionId(tempDir.toString()));
        }

        // ── listSessions ──────────────────────────────────────────────────────

        @Test
        @DisplayName("listSessions returns empty list when no sessions index exists")
        void listSessions_emptyWhenNoIndex() {
            SessionStoreV2 store = newStore();

            List<SessionStoreV2.SessionRecord> sessions = store.listSessions(tempDir.toString());

            assertNotNull(sessions);
            assertTrue(sessions.isEmpty());
        }

        @Test
        @DisplayName("listSessions skips records with missing or empty id")
        void listSessions_skipsMissingOrEmptyId() throws IOException {
            Files.createDirectories(sessionsDir());
            String json = "[{\"agent\":\"test\",\"updatedAt\":1000},{\"id\":\"\",\"agent\":\"test\",\"updatedAt\":2000}]";
            Files.writeString(sessionsIndexFile(), json, StandardCharsets.UTF_8);

            SessionStoreV2 store = newStore();
            List<SessionStoreV2.SessionRecord> sessions = store.listSessions(tempDir.toString());

            assertTrue(sessions.isEmpty(), "records without a valid id should be skipped");
        }

        @Test
        @DisplayName("listSessions parses all fields and sorts descending by updatedAt")
        void listSessions_parsesFieldsAndSortsDescending() throws IOException {
            Files.createDirectories(sessionsDir());
            String json = "["
                + "{\"id\":\"aa\",\"agent\":\"bot-a\",\"name\":\"Session A\","
                + "\"createdAt\":1000,\"updatedAt\":3000,\"turnCount\":5},"
                + "{\"id\":\"bb\",\"agent\":\"bot-b\",\"name\":\"Session B\","
                + "\"createdAt\":2000,\"updatedAt\":5000,\"turnCount\":2}"
                + "]";
            Files.writeString(sessionsIndexFile(), json, StandardCharsets.UTF_8);

            SessionStoreV2 store = newStore();
            List<SessionStoreV2.SessionRecord> sessions = store.listSessions(tempDir.toString());

            assertEquals(2, sessions.size());
            // sorted descending by updatedAt: bb (5000) first, then aa (3000)
            assertEquals("bb", sessions.get(0).id());
            assertEquals("bot-b", sessions.get(0).agent());
            assertEquals("Session B", sessions.get(0).name());
            assertEquals(2000L, sessions.get(0).createdAt());
            assertEquals(5000L, sessions.get(0).updatedAt());
            assertEquals(2, sessions.get(0).turnCount());

            assertEquals("aa", sessions.get(1).id());
            assertEquals(5, sessions.get(1).turnCount());
        }

        @Test
        @DisplayName("listSessions defaults missing optional fields")
        void listSessions_defaultsMissingOptionalFields() throws IOException {
            Files.createDirectories(sessionsDir());
            // Only id is present; all other fields use defaults
            String json = "[{\"id\":\"cc\"}]";
            Files.writeString(sessionsIndexFile(), json, StandardCharsets.UTF_8);

            SessionStoreV2 store = newStore();
            List<SessionStoreV2.SessionRecord> sessions = store.listSessions(tempDir.toString());

            assertEquals(1, sessions.size());
            SessionStoreV2.SessionRecord rec = sessions.get(0);
            assertEquals("cc", rec.id());
            assertEquals("Unknown", rec.agent());
            assertEquals("", rec.name());
            assertEquals(0L, rec.createdAt());
            assertEquals(0L, rec.updatedAt());
            assertEquals(0, rec.turnCount());
        }

        // ── appendEntries + loadEntries round-trip ────────────────────────────

        @Test
        @DisplayName("appendEntries + loadEntries round-trips a Prompt entry")
        void appendAndLoad_promptRoundTrip() {
            SessionStoreV2 store = newStore();
            EntryData.Prompt prompt = new EntryData.Prompt("Hello world", "2024-01-01T00:00:00Z",
                null, "eid-1", "eid-1");

            store.appendEntries(tempDir.toString(), List.of(prompt));
            List<EntryData> loaded = store.loadEntries(tempDir.toString());

            assertNotNull(loaded);
            assertEquals(1, loaded.size());
            assertInstanceOf(EntryData.Prompt.class, loaded.get(0));
            assertEquals("Hello world", ((EntryData.Prompt) loaded.get(0)).getText());
        }

        @Test
        @DisplayName("appendEntries + loadEntries round-trips Text, ToolCall, and Thinking entries")
        void appendAndLoad_multipleEntryTypesRoundTrip() {
            SessionStoreV2 store = newStore();
            List<EntryData> entries = List.of(
                new EntryData.Prompt("Question", "2024-01-01T00:00:00Z", null, "p1", "p1"),
                new EntryData.Text("Answer", "2024-01-01T00:00:01Z", "test-agent", "gpt-4", "t1"),
                new EntryData.Thinking("hmm...", "2024-01-01T00:00:02Z", "test-agent", "gpt-4", "th1"),
                new EntryData.ToolCall("readFile", "{\"path\":\"a.txt\"}", "filesystem",
                    "contents", "completed", "Read a file", "/a.txt",
                    false, null, null, "2024-01-01T00:00:03Z", "test-agent", "gpt-4", "tc1")
            );

            store.appendEntries(tempDir.toString(), entries);
            List<EntryData> loaded = store.loadEntries(tempDir.toString());

            assertNotNull(loaded);
            assertEquals(4, loaded.size());
            assertInstanceOf(EntryData.Prompt.class, loaded.get(0));
            assertInstanceOf(EntryData.Text.class, loaded.get(1));
            assertInstanceOf(EntryData.Thinking.class, loaded.get(2));
            assertInstanceOf(EntryData.ToolCall.class, loaded.get(3));
            assertEquals("readFile", ((EntryData.ToolCall) loaded.get(3)).getTitle());
        }

        @Test
        @DisplayName("loadEntries returns null when no session file exists")
        void loadEntries_nullWhenNoSessionFile() {
            SessionStoreV2 store = newStore();
            // No file created, no getCurrentSessionId called — no session exists

            List<EntryData> loaded = store.loadEntries(tempDir.toString());

            assertNull(loaded);
        }

        @Test
        @DisplayName("multiple appendEntries calls accumulate entries in the JSONL file")
        void appendEntries_multipleAppendsAccumulate() {
            SessionStoreV2 store = newStore();

            store.appendEntries(tempDir.toString(),
                List.of(new EntryData.Prompt("First", "2024-01-01T00:00:00Z", null, "p1", "p1")));
            store.appendEntries(tempDir.toString(),
                List.of(new EntryData.Text("Second", "2024-01-01T00:00:01Z", "test-agent", "gpt-4", "t1")));

            List<EntryData> loaded = store.loadEntries(tempDir.toString());

            assertNotNull(loaded);
            assertEquals(2, loaded.size());
            assertInstanceOf(EntryData.Prompt.class, loaded.get(0));
            assertInstanceOf(EntryData.Text.class, loaded.get(1));
        }

        // ── sessions index updated via appendEntries ───────────────────────────

        @Test
        @DisplayName("after appendEntries the session appears in listSessions")
        void appendEntries_sessionAppearsInListSessions() {
            SessionStoreV2 store = newStore();

            store.appendEntries(tempDir.toString(),
                List.of(new EntryData.Prompt("Fix the bug", "2024-01-01T00:00:00Z", null, "p1", "p1")));

            List<SessionStoreV2.SessionRecord> sessions = store.listSessions(tempDir.toString());

            assertEquals(1, sessions.size());
            assertEquals("test-agent", sessions.get(0).agent());
            assertEquals("Fix the bug", sessions.get(0).name());
            assertEquals(1, sessions.get(0).turnCount());
        }

        @Test
        @DisplayName("appendEntries increments turnCount for each Prompt in the batch")
        void appendEntries_incrementsTurnCount() {
            SessionStoreV2 store = newStore();

            store.appendEntries(tempDir.toString(), List.of(
                new EntryData.Prompt("First turn", "2024-01-01T00:00:00Z", null, "p1", "p1"),
                new EntryData.Text("Reply", "2024-01-01T00:00:01Z", "test-agent", "gpt-4", "t1"),
                new EntryData.Prompt("Second turn", "2024-01-01T00:00:02Z", null, "p2", "p2")
            ));

            List<SessionStoreV2.SessionRecord> sessions = store.listSessions(tempDir.toString());
            assertEquals(1, sessions.size());
            assertEquals(2, sessions.get(0).turnCount());
        }

        @Test
        @DisplayName("appendEntries does not overwrite existing session name on second call")
        void appendEntries_doesNotOverwriteExistingName() {
            SessionStoreV2 store = newStore();

            store.appendEntries(tempDir.toString(),
                List.of(new EntryData.Prompt("First prompt", "2024-01-01T00:00:00Z", null, "p1", "p1")));
            store.appendEntries(tempDir.toString(),
                List.of(new EntryData.Prompt("Second prompt", "2024-01-01T00:00:01Z", null, "p2", "p2")));

            List<SessionStoreV2.SessionRecord> sessions = store.listSessions(tempDir.toString());
            assertEquals("First prompt", sessions.get(0).name(),
                "session name should remain from the very first prompt");
        }

        @Test
        @DisplayName("appendEntries does not overwrite agent after switching to a different agent")
        void appendEntries_preservesOriginalAgent() {
            SessionStoreV2 store = newStore(); // currentAgent = "test-agent"

            store.appendEntries(tempDir.toString(),
                List.of(new EntryData.Prompt("Hello", "2024-01-01T00:00:00Z", null, "p1", "p1")));

            // Simulate switching to a different agent mid-session
            store.setCurrentAgent("different-agent");
            store.appendEntries(tempDir.toString(),
                List.of(new EntryData.Text("Reply", "2024-01-01T00:00:01Z", "different-agent", "gpt-4", "t1")));

            List<SessionStoreV2.SessionRecord> sessions = store.listSessions(tempDir.toString());
            assertEquals(1, sessions.size());
            assertEquals("test-agent", sessions.get(0).agent(),
                "session agent should remain from the first append, not be overwritten by the current agent");
        }

        // ── branchCurrentSession ──────────────────────────────────────────────

        @Test
        @DisplayName("branchCurrentSession is a no-op when no current-session-id file exists")
        void branchCurrentSession_noOpWhenNoIdFile() {
            SessionStoreV2 store = newStore();
            assertFalse(currentSessionIdFile().toFile().exists(), "prerequisite: no id file");

            assertDoesNotThrow(() -> store.branchCurrentSession(tempDir.toString()));

            // No sessions should have been created
            assertTrue(store.listSessions(tempDir.toString()).isEmpty());
        }

        @Test
        @DisplayName("branchCurrentSession creates a copy with a new ID when current session has content")
        void branchCurrentSession_createsCopyWithNewId() {
            SessionStoreV2 store = newStore();

            // Seed the current session with some content
            store.appendEntries(tempDir.toString(),
                List.of(new EntryData.Prompt("Branch me", "2024-01-01T00:00:00Z", null, "p1", "p1")));
            String originalId = store.getCurrentSessionId(tempDir.toString());

            store.branchCurrentSession(tempDir.toString());

            List<SessionStoreV2.SessionRecord> sessions = store.listSessions(tempDir.toString());
            // The original + one branch should be in the index
            assertEquals(2, sessions.size());

            boolean hasOriginal = sessions.stream().anyMatch(r -> r.id().equals(originalId));
            boolean hasBranch = sessions.stream().anyMatch(r -> !r.id().equals(originalId)
                && r.agent().contains("(branch"));
            assertTrue(hasOriginal, "original session should still be in the index");
            assertTrue(hasBranch, "a branch entry should have been added");
        }

        @Test
        @DisplayName("branchCurrentSession does not change the current-session-id file")
        void branchCurrentSession_originalIdFileUnchanged() {
            SessionStoreV2 store = newStore();

            store.appendEntries(tempDir.toString(),
                List.of(new EntryData.Prompt("Seed", "2024-01-01T00:00:00Z", null, "p1", "p1")));
            String originalId = store.getCurrentSessionId(tempDir.toString());

            store.branchCurrentSession(tempDir.toString());

            String idAfterBranch = store.getCurrentSessionId(tempDir.toString());
            assertEquals(originalId, idAfterBranch, "current-session-id must not change after branching");
        }

        @Test
        @DisplayName("branched session content matches original session")
        void branchCurrentSession_branchedContentMatchesOriginal() {
            SessionStoreV2 store = newStore();
            String promptText = "Original content";

            store.appendEntries(tempDir.toString(),
                List.of(new EntryData.Prompt(promptText, "2024-01-01T00:00:00Z", null, "p1", "p1")));
            String originalId = store.getCurrentSessionId(tempDir.toString());
            store.branchCurrentSession(tempDir.toString());

            List<SessionStoreV2.SessionRecord> sessions = store.listSessions(tempDir.toString());
            String branchId = sessions.stream()
                .map(SessionStoreV2.SessionRecord::id)
                .filter(id -> !id.equals(originalId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("branch not found in sessions index"));

            List<EntryData> branchEntries = store.loadEntriesBySessionId(tempDir.toString(), branchId);
            assertNotNull(branchEntries);
            assertEquals(1, branchEntries.size());
            assertInstanceOf(EntryData.Prompt.class, branchEntries.get(0));
            assertEquals(promptText, ((EntryData.Prompt) branchEntries.get(0)).getText());
        }

        // ── appendSessionsIndex agent-preservation ────────────────────────────

        @Test
        @DisplayName("agent is not overwritten on subsequent appendEntries calls")
        void appendSessionsIndex_agentPreservedOnSubsequentAppend() {
            SessionStoreV2 store = newStore();
            store.setCurrentAgent("agent-one");

            store.appendEntries(tempDir.toString(),
                List.of(new EntryData.Prompt("First prompt", "2024-01-01T00:00:00Z", null, "p1", "p1")));
            String sessionId = store.getCurrentSessionId(tempDir.toString());

            // Switch to a different agent and append more entries.
            store.setCurrentAgent("agent-two");
            store.appendEntries(tempDir.toString(),
                List.of(new EntryData.Prompt("Second prompt", "2024-01-01T00:01:00Z", null, "p2", "p2")));

            Optional<SessionStoreV2.SessionRecord> rec = store.listSessions(tempDir.toString())
                .stream().filter(r -> r.id().equals(sessionId)).findFirst();
            assertTrue(rec.isPresent(), "session should be in the index");
            assertEquals("agent-one", rec.get().agent(),
                "original agent must not be overwritten by subsequent appends");
        }

        @Test
        @DisplayName("agent is set from index record when field was previously absent")
        void appendSessionsIndex_agentSetWhenMissingFromExistingRecord() throws IOException {
            SessionStoreV2 store = newStore();
            store.setCurrentAgent("original-agent");

            // Bootstrap the sessions directory and current-session-id file.
            store.appendEntries(tempDir.toString(),
                List.of(new EntryData.Prompt("Seed", "2024-01-01T00:00:00Z", null, "p1", "p1")));
            String sessionId = store.getCurrentSessionId(tempDir.toString());

            // Rewrite the sessions index without the "agent" field to simulate an old record.
            Path indexFile = sessionsDir().resolve("sessions-index.json");
            String indexJson = Files.readString(indexFile);
            String withoutAgent = indexJson.replaceAll(",?\"agent\":\"[^\"]*\"", "");
            Files.writeString(indexFile, withoutAgent, StandardCharsets.UTF_8);

            // Appending entries should set the agent on the now-agent-less record.
            store.setCurrentAgent("backfill-agent");
            store.appendEntries(tempDir.toString(),
                List.of(new EntryData.Prompt("Follow-up", "2024-01-01T00:02:00Z", null, "p3", "p3")));

            Optional<SessionStoreV2.SessionRecord> rec = store.listSessions(tempDir.toString())
                .stream().filter(r -> r.id().equals(sessionId)).findFirst();
            assertTrue(rec.isPresent(), "session should still be in the index");
            assertEquals("backfill-agent", rec.get().agent(),
                "agent should be set when the existing record had no agent field");
        }
    }
}
