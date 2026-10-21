package com.github.catatafishen.agentbridge.session.exporters;

import com.github.catatafishen.agentbridge.bridge.EntryData;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for static utility methods in {@link OpenCodeClientExporter}.
 * Covers SHA-1 hashing, slug generation, ID generation, and budget trimming.
 */
class OpenCodeClientExporterTest {

    // ── sha1Hex ──────────────────────────────────────────────────────────────

    @Test
    void sha1Hex_producesCorrectHash() throws Exception {
        // Verify against known SHA-1 hash of "hello"
        String expected = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-1").digest("hello".getBytes(StandardCharsets.UTF_8))
        );
        assertEquals(expected, invokeSha1Hex("hello"));
    }

    @Test
    void sha1Hex_produces40CharHex() throws Exception {
        String hash = invokeSha1Hex("/home/user/project");
        assertEquals(40, hash.length());
        assertTrue(hash.matches("[0-9a-f]+"));
    }

    @Test
    void sha1Hex_isDeterministic() throws Exception {
        assertEquals(invokeSha1Hex("same input"), invokeSha1Hex("same input"));
    }

    @Test
    void sha1Hex_differentInputsDifferentHashes() throws Exception {
        assertNotEquals(invokeSha1Hex("path/a"), invokeSha1Hex("path/b"));
    }

    // ── generateSlug ─────────────────────────────────────────────────────────

    @Test
    void generateSlug_returnsAdjectiveNounFormat() throws Exception {
        String slug = invokeGenerateSlug();
        assertNotNull(slug);
        assertTrue(slug.contains("-"), "Slug should be adjective-noun: " + slug);
        assertEquals(2, slug.split("-").length, "Slug should have exactly two parts: " + slug);
    }

    @Test
    void generateSlug_containsOnlyLowercaseAndDash() throws Exception {
        for (int i = 0; i < 20; i++) {
            String slug = invokeGenerateSlug();
            assertTrue(slug.matches("[a-z]+-[a-z]+"), "Invalid slug format: " + slug);
        }
    }

    // ── generateId ───────────────────────────────────────────────────────────

    @Test
    void generateId_hasPrefixAndUnderscore() throws Exception {
        String id = invokeGenerateId("ses");
        assertTrue(id.startsWith("ses_"), "ID should start with prefix_: " + id);
    }

    @Test
    void generateId_isUnique() throws Exception {
        String id1 = invokeGenerateId("msg");
        String id2 = invokeGenerateId("msg");
        assertNotEquals(id1, id2);
    }

    @Test
    void generateId_differentPrefixes() throws Exception {
        String sesId = invokeGenerateId("ses");
        String msgId = invokeGenerateId("msg");
        assertTrue(sesId.startsWith("ses_"));
        assertTrue(msgId.startsWith("msg_"));
    }

    // ── trimEntriesToBudget ──────────────────────────────────────────────────

    @Test
    void trimEntriesToBudget_returnsAllWhenUnderBudget() throws Exception {
        List<EntryData> entries = List.of(
            new EntryData.Prompt("short prompt"),
            new EntryData.Text("short reply")
        );
        List<EntryData> result = invokeTrimEntriesToBudget(entries, 10000);
        assertEquals(2, result.size());
    }

    @Test
    void trimEntriesToBudget_returnsAllWhenBudgetIsZero() throws Exception {
        List<EntryData> entries = List.of(
            new EntryData.Prompt("hello"),
            new EntryData.Text("world")
        );
        // maxTotalChars <= 0 means no limit
        List<EntryData> result = invokeTrimEntriesToBudget(entries, 0);
        assertEquals(2, result.size());
    }

    @Test
    void trimEntriesToBudget_dropsOlderTurnsFirst() throws Exception {
        // Two turns: prompt1 + text1, prompt2 + text2
        // Each prompt = 100 chars, each text = 100 chars => total 400 chars
        String longText = "x".repeat(100);
        List<EntryData> entries = List.of(
            new EntryData.Prompt(longText),
            new EntryData.Text(longText),
            new EntryData.Prompt(longText),
            new EntryData.Text(longText)
        );
        // Budget of 250 should drop the first turn (200 chars)
        List<EntryData> result = invokeTrimEntriesToBudget(entries, 250);
        assertTrue(result.size() < 4, "Should have dropped older entries");
        // The first entry in result should be the second prompt
        assertInstanceOf(EntryData.Prompt.class, result.getFirst());
    }

    @Test
    void trimEntriesToBudget_singleTurnDropsNonPromptEntries() throws Exception {
        String longText = "x".repeat(200);
        List<EntryData> entries = List.of(
            new EntryData.Prompt("short prompt"),
            new EntryData.Text(longText),
            new EntryData.Text(longText)
        );
        // Budget of 250 should drop some text entries
        List<EntryData> result = invokeTrimEntriesToBudget(entries, 250);
        assertTrue(result.size() < 3);
        // Prompt should always be preserved
        assertInstanceOf(EntryData.Prompt.class, result.getFirst());
    }

    @Test
    void trimEntriesToBudget_handlesToolCallEntries() throws Exception {
        String longArgs = "a".repeat(200);
        List<EntryData> entries = List.of(
            new EntryData.Prompt("prompt"),
            new EntryData.ToolCall("tool1", longArgs),
            new EntryData.Prompt("prompt2"),
            new EntryData.Text("reply")
        );
        // ToolCall with 200 chars args contributes to budget
        List<EntryData> result = invokeTrimEntriesToBudget(entries, 100);
        // Should trim down to fit budget
        assertFalse(result.isEmpty());
    }

    @Test
    void trimEntriesToBudget_handlesEmptyList() throws Exception {
        List<EntryData> result = invokeTrimEntriesToBudget(List.of(), 100);
        assertTrue(result.isEmpty());
    }

    // ── buildMessageData ───────────────────────────────────────────────────

    @Test
    void buildMessageData_userRole_hasTimeAgentModel() throws Exception {
        JsonObject data = invokeBuildMessageData("user", "coder", "gpt-4", 1000L, "/proj", null);
        assertEquals("user", data.get("role").getAsString());
        assertEquals(1000L, data.getAsJsonObject("time").get("created").getAsLong());
        assertEquals("coder", data.get("agent").getAsString());
        JsonObject model = data.getAsJsonObject("model");
        assertEquals("imported", model.get("providerID").getAsString());
        assertEquals("gpt-4", model.get("modelID").getAsString());
        // user role should NOT have parentID, tokens, cost, path
        assertFalse(data.has("parentID"));
        assertFalse(data.has("tokens"));
        assertFalse(data.has("cost"));
        assertFalse(data.has("path"));
    }

    @Test
    void buildMessageData_assistantRole_hasParentIdTokensPathCost() throws Exception {
        JsonObject data = invokeBuildMessageData("assistant", "coder", "gpt-4", 2000L, "/proj", "msg_parent");
        assertEquals("assistant", data.get("role").getAsString());
        assertEquals("msg_parent", data.get("parentID").getAsString());
        assertEquals("gpt-4", data.get("modelID").getAsString());
        assertEquals("imported", data.get("providerID").getAsString());
        assertEquals("build", data.get("mode").getAsString());
        assertEquals("coder", data.get("agent").getAsString());
        // path
        JsonObject path = data.getAsJsonObject("path");
        assertEquals("/proj", path.get("cwd").getAsString());
        assertEquals("/proj", path.get("root").getAsString());
        // cost & tokens
        assertEquals(0, data.get("cost").getAsInt());
        JsonObject tokens = data.getAsJsonObject("tokens");
        assertEquals(0, tokens.get("input").getAsInt());
        assertEquals(0, tokens.get("output").getAsInt());
        assertEquals(0, tokens.get("reasoning").getAsInt());
        JsonObject cache = tokens.getAsJsonObject("cache");
        assertEquals(0, cache.get("read").getAsInt());
        assertEquals(0, cache.get("write").getAsInt());
        // time
        JsonObject time = data.getAsJsonObject("time");
        assertEquals(2000L, time.get("created").getAsLong());
        assertEquals(2000L, time.get("completed").getAsLong());
        // assistant should NOT have model object
        assertFalse(data.has("model"));
    }

    @Test
    void buildMessageData_nullAgent_defaultsToBuild() throws Exception {
        JsonObject data = invokeBuildMessageData("user", null, null, 1L, "/p", null);
        assertEquals("build", data.get("agent").getAsString());
        assertEquals("imported", data.getAsJsonObject("model").get("modelID").getAsString());
    }

    @Test
    void buildMessageData_emptyAgentAndModel_defaultsToBuildAndImported() throws Exception {
        JsonObject data = invokeBuildMessageData("assistant", "", "", 1L, "/p", null);
        assertEquals("build", data.get("agent").getAsString());
        assertEquals("imported", data.get("modelID").getAsString());
        assertEquals("imported", data.get("providerID").getAsString());
    }

    // ── buildToolInvocationPart ──────────────────────────────────────────────

    @Test
    void buildToolInvocationPart_completedToolCall_statusCompleted() throws Exception {
        EntryData.ToolCall tc = new EntryData.ToolCall("read_file");
        tc.setResult("file contents here");
        JsonObject part = invokeBuildToolInvocationPart(tc, 5000L);
        assertEquals("tool", part.get("type").getAsString());
        assertEquals("read_file", part.get("tool").getAsString());
        JsonObject state = part.getAsJsonObject("state");
        assertEquals("completed", state.get("status").getAsString());
        assertEquals("file contents here", state.get("output").getAsString());
        assertTrue(state.has("title"));
        assertTrue(state.has("metadata"));
        JsonObject time = state.getAsJsonObject("time");
        assertEquals(5000L, time.get("start").getAsLong());
        assertEquals(5000L, time.get("end").getAsLong());
    }

    @Test
    void buildToolInvocationPart_agentBridgeToolUsesOpenCodeMcpNamespace() throws Exception {
        EntryData.ToolCall tc = new EntryData.ToolCall("Viewing project settings");
        tc.setPluginTool("read_file");
        tc.setResult("file contents here");

        JsonObject part = invokeBuildToolInvocationPart(tc, 5000L);

        assertEquals("agentbridge_read_file", part.get("tool").getAsString());
    }

    @Test
    void buildToolInvocationPart_runningToolCall_statusRunning() throws Exception {
        EntryData.ToolCall tc = new EntryData.ToolCall("bash");
        // result is null by default → running
        JsonObject part = invokeBuildToolInvocationPart(tc, 3000L);
        JsonObject state = part.getAsJsonObject("state");
        assertEquals("running", state.get("status").getAsString());
        assertFalse(state.has("output"));
        assertFalse(state.has("title"));
        assertFalse(state.has("metadata"));
        JsonObject time = state.getAsJsonObject("time");
        assertEquals(3000L, time.get("start").getAsLong());
        assertFalse(time.has("end"));
    }

    @Test
    void buildToolInvocationPart_jsonArgs_parsedAsInputObject() throws Exception {
        EntryData.ToolCall tc = new EntryData.ToolCall("edit_text", "{\"path\":\"a.txt\",\"old\":\"x\"}");
        tc.setResult("ok");
        JsonObject part = invokeBuildToolInvocationPart(tc, 1L);
        JsonObject state = part.getAsJsonObject("state");
        JsonObject input = state.getAsJsonObject("input");
        assertEquals("a.txt", input.get("path").getAsString());
        assertEquals("x", input.get("old").getAsString());
    }

    @Test
    void buildToolInvocationPart_nonJsonArgs_wrappedInRaw() throws Exception {
        EntryData.ToolCall tc = new EntryData.ToolCall("bash", "not valid json");
        tc.setResult("done");
        JsonObject part = invokeBuildToolInvocationPart(tc, 1L);
        JsonObject state = part.getAsJsonObject("state");
        JsonObject input = state.getAsJsonObject("input");
        assertEquals("not valid json", input.get("raw").getAsString());
    }

    @Test
    void buildToolInvocationPart_nullArgs_emptyObject() throws Exception {
        EntryData.ToolCall tc = new EntryData.ToolCall("read_file", null);
        tc.setResult("ok");
        JsonObject part = invokeBuildToolInvocationPart(tc, 1L);
        JsonObject state = part.getAsJsonObject("state");
        JsonObject input = state.getAsJsonObject("input");
        assertEquals(0, input.size(), "null args should produce empty input object");
    }

    @Test
    void buildToolInvocationPart_emptyArgs_emptyObject() throws Exception {
        EntryData.ToolCall tc = new EntryData.ToolCall("read_file", "   ");
        tc.setResult("ok");
        JsonObject part = invokeBuildToolInvocationPart(tc, 1L);
        JsonObject state = part.getAsJsonObject("state");
        JsonObject input = state.getAsJsonObject("input");
        assertEquals(0, input.size(), "blank args should produce empty input object");
    }

    // ── hasExportableEntries ─────────────────────────────────────────────────

    @Test
    void hasExportableEntries_emptyList_returnsFalse() throws Exception {
        assertFalse(invokeHasExportableEntries(List.of()));
    }

    @Test
    void hasExportableEntries_onlyNonExportable_returnsFalse() throws Exception {
        List<EntryData> entries = List.of(
            new EntryData.SessionSeparator("2024-01-01T00:00:00Z")
        );
        assertFalse(invokeHasExportableEntries(entries));
    }

    @Test
    void hasExportableEntries_withPrompt_returnsTrue() throws Exception {
        List<EntryData> entries = List.of(new EntryData.Prompt("hello"));
        assertTrue(invokeHasExportableEntries(entries));
    }

    @Test
    void hasExportableEntries_withText_returnsTrue() throws Exception {
        List<EntryData> entries = new ArrayList<>();
        entries.add(new EntryData.Text("reply"));
        assertTrue(invokeHasExportableEntries(entries));
    }

    @Test
    void hasExportableEntries_withToolCall_returnsTrue() throws Exception {
        List<EntryData> entries = new ArrayList<>();
        entries.add(new EntryData.ToolCall("bash"));
        assertTrue(invokeHasExportableEntries(entries));
    }

    // ── buildSubAgentPart ────────────────────────────────────────────────────

    @Test
    void buildSubAgentPart_withDescriptionAndResult() throws Exception {
        EntryData.SubAgent sub = new EntryData.SubAgent("Explore", "Search the codebase");
        sub.setResult("Found 3 matches");
        JsonObject part = invokeBuildSubAgentPart(sub);
        assertEquals("text", part.get("type").getAsString());
        String text = part.get("text").getAsString();
        assertTrue(text.contains("Subagent"), "Should contain 'Subagent'");
        assertTrue(text.contains("Explore"), "Should contain agent type");
        assertTrue(text.contains("Search the codebase"), "Should contain description");
        assertTrue(text.contains("Found 3 matches"), "Should contain result");
    }

    @Test
    void buildSubAgentPart_withoutDescription() throws Exception {
        EntryData.SubAgent sub = new EntryData.SubAgent("Plan", "");
        sub.setResult("Done planning");
        JsonObject part = invokeBuildSubAgentPart(sub);
        String text = part.get("text").getAsString();
        assertTrue(text.contains("Plan"), "Should contain agent type");
        assertFalse(text.contains(": ]"), "Should not have dangling colon for empty desc");
        assertTrue(text.contains("Done planning"), "Should contain result");
    }

    @Test
    void buildSubAgentPart_withoutResult() throws Exception {
        EntryData.SubAgent sub = new EntryData.SubAgent("Explore", "Look around");
        // result stays null
        JsonObject part = invokeBuildSubAgentPart(sub);
        String text = part.get("text").getAsString();
        assertTrue(text.contains("Look around"), "Should contain description");
        // Text should end after the bracket — no result appended
        assertTrue(text.endsWith("]"), "Should end with ] when no result: " + text);
    }

    // ── stripTrailingPendingPrompt ────────────────────────────────────────────

    @Test
    void stripTrailingPendingPrompt_noTrailingPrompt_unchanged() {
        List<EntryData> entries = List.of(
            new EntryData.Prompt("q"),
            new EntryData.Text("a")
        );
        List<EntryData> result = OpenCodeClientExporter.stripTrailingPendingPrompt(entries);
        assertEquals(2, result.size());
        assertInstanceOf(EntryData.Prompt.class, result.get(0));
        assertInstanceOf(EntryData.Text.class, result.get(1));
    }

    @Test
    void stripTrailingPendingPrompt_trailingPrompt_isStripped() {
        List<EntryData> entries = List.of(
            new EntryData.Prompt("q1"),
            new EntryData.Text("a1"),
            new EntryData.Prompt("pending")
        );
        List<EntryData> result = OpenCodeClientExporter.stripTrailingPendingPrompt(entries);
        assertEquals(2, result.size());
        assertInstanceOf(EntryData.Text.class, result.get(1));
    }

    @Test
    void stripTrailingPendingPrompt_onlySinglePrompt_stripsToEmpty() {
        List<EntryData> entries = new ArrayList<>();
        entries.add(new EntryData.Prompt("only prompt"));
        List<EntryData> result = OpenCodeClientExporter.stripTrailingPendingPrompt(entries);
        assertTrue(result.isEmpty());
    }

    @Test
    void stripTrailingPendingPrompt_orphanedInMiddle_preserved() {
        // Orphaned prompt in the middle (followed by more content) must not be stripped
        List<EntryData> entries = List.of(
            new EntryData.Prompt("failed attempt 1"),  // orphaned — no response
            new EntryData.Prompt("copilot question"),  // answered by another agent
            new EntryData.Text("copilot answer"),
            new EntryData.Prompt("pending")            // trailing — must be stripped
        );
        List<EntryData> result = OpenCodeClientExporter.stripTrailingPendingPrompt(entries);
        assertEquals(3, result.size(), "Only the trailing prompt should be removed");
        assertInstanceOf(EntryData.Prompt.class, result.get(0), "Orphaned prompt in middle must be kept");
    }

    @Test
    void stripTrailingPendingPrompt_emptyList_returnsEmpty() {
        List<EntryData> result = OpenCodeClientExporter.stripTrailingPendingPrompt(List.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void stripTrailingPendingPrompt_nonContentSuffixBeforePrompt_stillStrips() {
        // Non-content entries (e.g. SessionSeparator) after a Prompt should not protect it
        List<EntryData> entries = new ArrayList<>();
        entries.add(new EntryData.Prompt("q1"));
        entries.add(new EntryData.Text("a1"));
        entries.add(new EntryData.Prompt("pending"));
        entries.add(new EntryData.SessionSeparator("2024-01-01T00:00:00Z"));
        List<EntryData> result = OpenCodeClientExporter.stripTrailingPendingPrompt(entries);
        // SessionSeparator is non-content — scanning should skip it and find the Prompt
        assertEquals(3, result.size());
        // The SessionSeparator stays; the Prompt before it is stripped
        assertInstanceOf(EntryData.SessionSeparator.class, result.get(2));
    }

    // ── applyForeignAgentPrefix ───────────────────────────────────────────────

    @Test
    void applyForeignAgentPrefix_foreignAgent_prefixesFirstTextPart() {
        JsonObject part = new JsonObject();
        part.addProperty("type", "text");
        part.addProperty("text", "The answer is 42.");
        List<JsonObject> parts = new ArrayList<>();
        parts.add(part);

        OpenCodeClientExporter.applyForeignAgentPrefix(parts, "copilot");

        String text = parts.get(0).get("text").getAsString();
        assertTrue(text.startsWith("[Note: this response was provided by agent 'copilot'"),
            "Should have foreign-agent prefix: " + text);
        assertTrue(text.contains("The answer is 42."), "Original text should be preserved");
    }

    @Test
    void applyForeignAgentPrefix_selfAgent_noChange() {
        // EntryData.Text.getAgent() stores the display name ("OpenCode"), not the profile ID ("opencode").
        // The comparison is case-insensitive so both forms are recognised as self.
        JsonObject part = new JsonObject();
        part.addProperty("type", "text");
        part.addProperty("text", "My own response.");
        List<JsonObject> parts = new ArrayList<>();
        parts.add(part);

        OpenCodeClientExporter.applyForeignAgentPrefix(parts, "OpenCode");

        assertEquals("My own response.", parts.get(0).get("text").getAsString());
    }

    @Test
    void applyForeignAgentPrefix_selfAgentProfileId_noChange() {
        // Profile ID ("opencode") is also accepted as self-agent via case-insensitive match
        JsonObject part = new JsonObject();
        part.addProperty("type", "text");
        part.addProperty("text", "My own response.");
        List<JsonObject> parts = new ArrayList<>();
        parts.add(part);

        OpenCodeClientExporter.applyForeignAgentPrefix(parts, "opencode");

        assertEquals("My own response.", parts.get(0).get("text").getAsString());
    }

    @Test
    void applyForeignAgentPrefix_nullAgent_noChange() {
        JsonObject part = new JsonObject();
        part.addProperty("type", "text");
        part.addProperty("text", "text");
        List<JsonObject> parts = new ArrayList<>();
        parts.add(part);

        OpenCodeClientExporter.applyForeignAgentPrefix(parts, null);

        assertEquals("text", parts.get(0).get("text").getAsString());
    }

    @Test
    void applyForeignAgentPrefix_emptyAgent_noChange() {
        JsonObject part = new JsonObject();
        part.addProperty("type", "text");
        part.addProperty("text", "text");
        List<JsonObject> parts = new ArrayList<>();
        parts.add(part);

        OpenCodeClientExporter.applyForeignAgentPrefix(parts, "");

        assertEquals("text", parts.get(0).get("text").getAsString());
    }

    @Test
    void applyForeignAgentPrefix_noTextPart_prependsNotePart() {
        // Parts with only tool invocations — a note part should be prepended
        JsonObject toolPart = new JsonObject();
        toolPart.addProperty("type", "tool");
        toolPart.addProperty("tool", "read_file");
        List<JsonObject> parts = new ArrayList<>();
        parts.add(toolPart);

        OpenCodeClientExporter.applyForeignAgentPrefix(parts, "copilot");

        assertEquals(2, parts.size(), "A note part should have been prepended");
        assertEquals("text", parts.get(0).get("type").getAsString());
        assertTrue(parts.get(0).get("text").getAsString().contains("copilot"));
        assertEquals("tool", parts.get(1).get("type").getAsString());
    }

    // ── OpenCode 2.x payload builders ────────────────────────────────────────

    @Test
    void buildV2UserData_hasTextAttachmentsAndTime() {
        JsonObject data = OpenCodeClientExporter.buildV2UserData("hello", 1000L);
        assertEquals("hello", data.get("text").getAsString());
        assertEquals(0, data.getAsJsonArray("files").size());
        assertEquals(0, data.getAsJsonArray("agents").size());
        assertEquals(1000L, data.getAsJsonObject("time").get("created").getAsLong());
        assertFalse(data.has("role"));
        assertFalse(data.has("agent"));
        assertFalse(data.has("model"));
    }

    @Test
    void buildV2AssistantData_hasModelContentCostTokens() {
        JsonObject textEntry = new JsonObject();
        textEntry.addProperty("type", "text");
        textEntry.addProperty("text", "answer");
        JsonObject data = OpenCodeClientExporter.buildV2AssistantData("coder", "gpt-4", List.of(textEntry), 2000L);
        assertEquals("coder", data.get("agent").getAsString());
        JsonObject model = data.getAsJsonObject("model");
        assertEquals("imported", model.get("providerID").getAsString());
        assertEquals("gpt-4", model.get("id").getAsString());
        assertEquals("default", model.get("variant").getAsString());
        assertEquals(1, data.getAsJsonArray("content").size());
        assertEquals(0, data.get("cost").getAsInt());
        JsonObject tokens = data.getAsJsonObject("tokens");
        assertEquals(0, tokens.get("input").getAsInt());
        assertEquals(0, tokens.getAsJsonObject("cache").get("read").getAsInt());
        assertEquals(2000L, data.getAsJsonObject("time").get("created").getAsLong());
        assertEquals(2000L, data.getAsJsonObject("time").get("completed").getAsLong());
        assertFalse(data.has("role"));
        assertFalse(data.has("path"));
        assertFalse(data.has("parentID"));
    }

    @Test
    void buildV2AssistantData_nullAgentAndModel_useDefaults() {
        JsonObject data = OpenCodeClientExporter.buildV2AssistantData(null, null, List.of(), 1L);
        assertEquals("build", data.get("agent").getAsString());
        assertEquals("imported", data.getAsJsonObject("model").get("id").getAsString());
    }

    @Test
    void buildV2ReasoningContent_usesCreatedCompletedTimePair() {
        JsonObject content = OpenCodeClientExporter.buildV2ReasoningContent("thinking", 3000L);
        assertEquals("reasoning", content.get("type").getAsString());
        assertEquals("thinking", content.get("text").getAsString());
        assertEquals(3000L, content.getAsJsonObject("time").get("created").getAsLong());
        assertEquals(3000L, content.getAsJsonObject("time").get("completed").getAsLong());
        assertFalse(content.getAsJsonObject("time").has("start"));
    }

    @Test
    void buildV2ToolContent_completedCall_outputInsideStateContent() {
        EntryData.ToolCall tc = new EntryData.ToolCall("read_file", "{\"path\":\"a.txt\"}");
        tc.setResult("file contents");
        JsonObject content = OpenCodeClientExporter.buildV2ToolContent(tc, 5000L);
        assertEquals("tool", content.get("type").getAsString());
        assertEquals("read_file", content.get("name").getAsString());
        JsonObject state = content.getAsJsonObject("state");
        assertEquals("completed", state.get("status").getAsString());
        assertEquals("a.txt", state.getAsJsonObject("input").get("path").getAsString());
        assertEquals("file contents", state.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());
        assertEquals(5000L, content.getAsJsonObject("time").get("created").getAsLong());
        assertEquals(5000L, content.getAsJsonObject("time").get("completed").getAsLong());
        assertFalse(state.has("output"));
    }

    @Test
    void buildV2ToolContent_unfinishedCall_recordedAsInterrupted() {
        EntryData.ToolCall tc = new EntryData.ToolCall("bash");
        JsonObject content = OpenCodeClientExporter.buildV2ToolContent(tc, 3000L);
        JsonObject state = content.getAsJsonObject("state");
        assertEquals("error", state.get("status").getAsString());
        assertEquals("tool.interrupted", state.getAsJsonObject("error").get("type").getAsString());
        assertFalse(state.has("content"));
        assertFalse(content.getAsJsonObject("time").has("completed"));
    }

    // ── parseToolInput ───────────────────────────────────────────────────────

    @Test
    void parseToolInput_jsonObject_returnedAsIs() {
        JsonObject input = OpenCodeClientExporter.parseToolInput("{\"path\":\"a.txt\"}");
        assertEquals("a.txt", input.get("path").getAsString());
    }

    @Test
    void parseToolInput_nonJson_wrappedInRaw() {
        JsonObject input = OpenCodeClientExporter.parseToolInput("not valid json");
        assertEquals("not valid json", input.get("raw").getAsString());
    }

    @Test
    void parseToolInput_nullAndBlank_emptyObject() {
        assertEquals(0, OpenCodeClientExporter.parseToolInput(null).size());
        assertEquals(0, OpenCodeClientExporter.parseToolInput("   ").size());
    }

    // ── findGitRoot ──────────────────────────────────────────────────────────

    @Test
    void findGitRoot_walksUpToDotGit() throws Exception {
        Path root = Files.createTempDirectory("oc-gitroot-test");
        try {
            Files.createDirectories(root.resolve(".git"));
            Path nested = Files.createDirectories(root.resolve("sub").resolve("dir"));
            assertEquals(root.toAbsolutePath().normalize(), OpenCodeClientExporter.findGitRoot(nested.toString()).toAbsolutePath().normalize());
        } finally {
            deleteRecursively(root);
        }
    }

    // ── exportSession against real SQLite databases ──────────────────────────

    @Test
    void exportSession_v2Schema_writesSessionV2AndSessionMessages() throws Exception {
        Path dir = Files.createTempDirectory("oc-v2-export-test");
        Path dbPath = dir.resolve("opencode.db");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            createV2Schema(conn);
            insertProjectRow(conn, "proj_test", dir.toString());
        }

        List<EntryData> entries = new ArrayList<>();
        entries.add(new EntryData.Prompt("first question"));
        entries.add(new EntryData.Text("first answer"));
        EntryData.ToolCall tc = new EntryData.ToolCall("read_file", "{\"path\":\"a.txt\"}");
        tc.setResult("contents");
        entries.add(tc);
        entries.add(new EntryData.Text("second answer"));

        String sessionId = OpenCodeClientExporter.exportSession(entries, dbPath, dir.toString(), 0);

        assertNotNull(sessionId);
        assertTrue(sessionId.startsWith("ses_"));
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery(
                "SELECT project_id, directory, version, title FROM session_v2 WHERE id = '" + sessionId + "'")) {
                assertTrue(rs.next());
                assertEquals("proj_test", rs.getString(1));
                assertEquals(dir.toString().replace('\\', '/'), rs.getString(2));
                assertEquals("2.0.0", rs.getString(3));
                assertEquals("AgentBridge Session", rs.getString(4));
            }
            try (ResultSet rs = stmt.executeQuery(
                "SELECT type, seq, data FROM session_message WHERE session_id = '" + sessionId + "' ORDER BY seq")) {
                assertTrue(rs.next());
                assertEquals("user", rs.getString(1));
                assertEquals(0, rs.getInt(2));
                JsonObject userData = JsonParser.parseString(rs.getString(3)).getAsJsonObject();
                assertEquals("first question", userData.get("text").getAsString());
                assertTrue(rs.next());
                assertEquals("assistant", rs.getString(1));
                assertEquals(1, rs.getInt(2));
                JsonObject assistantData = JsonParser.parseString(rs.getString(3)).getAsJsonObject();
                JsonArray content = assistantData.getAsJsonArray("content");
                assertEquals(3, content.size(), "text, tool, and trailing text share one assistant message");
                assertEquals("text", content.get(0).getAsJsonObject().get("type").getAsString());
                assertEquals("first answer", content.get(0).getAsJsonObject().get("text").getAsString());
                JsonObject toolEntry = content.get(1).getAsJsonObject();
                assertEquals("tool", toolEntry.get("type").getAsString());
                assertEquals("a.txt", toolEntry.getAsJsonObject("state").getAsJsonObject("input").get("path").getAsString());
                assertEquals("text", content.get(2).getAsJsonObject().get("type").getAsString());
                assertEquals("second answer", content.get(2).getAsJsonObject().get("text").getAsString());
                assertFalse(rs.next());
            }
            try (ResultSet rs = stmt.executeQuery(
                "SELECT seq FROM event_sequence WHERE aggregate_id = '" + sessionId + "'")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
            try (ResultSet rs = stmt.executeQuery(
                "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name IN ('session', 'message', 'part')")) {
                assertEquals(0, rs.getInt(1), "legacy tables must not be created against a v2 database");
            }
        }
    }

    @Test
    void exportSession_v2Schema_pathIsSubdirRelativeToGitRoot() throws Exception {
        Path root = Files.createTempDirectory("oc-v2-path-test");
        Path dbPath = root.resolve("opencode.db");
        Path sub = Files.createDirectories(root.resolve("coreapp"));
        try {
            Files.createDirectories(root.resolve(".git"));
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
                createV2Schema(conn);
                insertProjectRow(conn, "proj_git", root.toString());
            }

            List<EntryData> entries = new ArrayList<>();
            entries.add(new EntryData.Prompt("q"));
            entries.add(new EntryData.Text("a"));

            String sessionId = OpenCodeClientExporter.exportSession(entries, dbPath, sub.toString(), 0);

            assertNotNull(sessionId);
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                 Statement stmt = conn.createStatement()) {
                try (ResultSet rs = stmt.executeQuery(
                    "SELECT path, project_id, directory FROM session_v2 WHERE id = '" + sessionId + "'")) {
                    assertTrue(rs.next());
                    assertEquals("coreapp", rs.getString(1), "path must be the subdir relative to the git root, not the up-walk");
                    assertEquals("proj_git", rs.getString(2), "project must resolve to the git root worktree");
                    assertEquals(sub.toString().replace('\\', '/'), rs.getString(3).replace('\\', '/'));
                }
            }
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    void exportSession_v2Schema_noProjectRow_fallsBackToGlobal() throws Exception {
        Path dir = Files.createTempDirectory("oc-v2-global-test");
        Path dbPath = dir.resolve("opencode.db");
        try {
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
                createV2Schema(conn);
            }

            List<EntryData> entries = new ArrayList<>();
            entries.add(new EntryData.Prompt("q"));
            entries.add(new EntryData.Text("a"));

            String sessionId = OpenCodeClientExporter.exportSession(entries, dbPath, dir.toString(), 0);

            assertNotNull(sessionId);
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT project_id FROM session_v2 WHERE id = '" + sessionId + "'")) {
                assertTrue(rs.next());
                assertEquals("global", rs.getString(1), "no matching project row must fall back to the global id");
            }
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void exportSession_legacySchema_stillUsesLegacyTables() throws Exception {
        Path dir = Files.createTempDirectory("oc-v1-export-test");
        Path dbPath = dir.resolve("opencode.db");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE session (id TEXT PRIMARY KEY, project_id TEXT NOT NULL, parent_id TEXT, "
                + "slug TEXT NOT NULL, directory TEXT NOT NULL, title TEXT NOT NULL, version TEXT NOT NULL, "
                + "share_url TEXT, summary_additions INTEGER, summary_deletions INTEGER, summary_files INTEGER, "
                + "summary_diffs TEXT, revert TEXT, permission TEXT, time_created INTEGER NOT NULL, "
                + "time_updated INTEGER NOT NULL, time_compacting INTEGER, time_archived INTEGER, workspace_id TEXT)");
        }

        List<EntryData> entries = new ArrayList<>();
        entries.add(new EntryData.Prompt("q"));
        entries.add(new EntryData.Text("a"));

        String sessionId = OpenCodeClientExporter.exportSession(entries, dbPath, dir.toString(), 0);

        assertNotNull(sessionId);
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT count(*) FROM session WHERE id = '" + sessionId + "'")) {
            assertEquals(1, rs.getInt(1), "legacy path must keep writing the legacy session table");
        }
    }

    @Test
    void exportSession_uninitializedDatabase_skipsExport() throws Exception {
        Path dir = Files.createTempDirectory("oc-fresh-export-test");
        Path dbPath = dir.resolve("opencode.db");

        String sessionId = OpenCodeClientExporter.exportSession(
            List.of(new EntryData.Prompt("q"), new EntryData.Text("a")), dbPath, dir.toString(), 0);

        assertNull(sessionId);
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name IN ('session', 'session_v2')")) {
            assertEquals(0, rs.getInt(1), "no schema may be fabricated into an uninitialized database");
        }
    }

    private static void createV2Schema(Connection conn) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE project (id TEXT PRIMARY KEY, worktree TEXT NOT NULL, vcs TEXT, "
                + "time_created INTEGER NOT NULL, time_updated INTEGER NOT NULL, sandboxes TEXT NOT NULL)");
            stmt.execute("CREATE TABLE session_v2 (id TEXT PRIMARY KEY, project_id TEXT NOT NULL, "
                + "workspace_id TEXT, parent_id TEXT, fork_session_id TEXT, fork_boundary TEXT, slug TEXT NOT NULL, "
                + "directory TEXT NOT NULL, path TEXT, title TEXT, version TEXT NOT NULL, share_url TEXT, "
                + "summary_additions INTEGER, summary_deletions INTEGER, summary_files INTEGER, summary_diffs TEXT, "
                + "metadata TEXT, cost REAL DEFAULT 0 NOT NULL, tokens_input INTEGER DEFAULT 0 NOT NULL, "
                + "tokens_output INTEGER DEFAULT 0 NOT NULL, tokens_reasoning INTEGER DEFAULT 0 NOT NULL, "
                + "tokens_cache_read INTEGER DEFAULT 0 NOT NULL, tokens_cache_write INTEGER DEFAULT 0 NOT NULL, "
                + "revert TEXT, permission TEXT, agent TEXT, model TEXT, time_created INTEGER NOT NULL, "
                + "time_updated INTEGER NOT NULL, time_compacting INTEGER, time_archived INTEGER)");
            stmt.execute("CREATE TABLE session_message (id TEXT PRIMARY KEY, session_id TEXT NOT NULL, "
                + "type TEXT NOT NULL, seq INTEGER NOT NULL, time_created INTEGER NOT NULL, "
                + "time_updated INTEGER NOT NULL, data TEXT NOT NULL)");
            stmt.execute("CREATE TABLE event_sequence (aggregate_id TEXT PRIMARY KEY, seq INTEGER NOT NULL, owner_id TEXT)");
        }
    }

    private static void insertProjectRow(Connection conn, String id, String worktree) throws Exception {
        try (java.sql.PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO project (id, worktree, vcs, time_created, time_updated, sandboxes) VALUES (?, ?, NULL, 0, 0, '[]')")) {
            ps.setString(1, id);
            ps.setString(2, worktree.replace('\\', '/'));
            ps.executeUpdate();
        }
    }

    private static void deleteRecursively(Path root) {
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (Exception ignored) {
                    // best-effort cleanup of a temp directory
                }
            });
        } catch (Exception ignored) {
            // best-effort cleanup of a temp directory
        }
    }

    // ── Reflection helpers ───────────────────────────────────────────────────

    private static String invokeSha1Hex(String input) throws Exception {
        Method m = OpenCodeClientExporter.class.getDeclaredMethod("sha1Hex", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, input);
    }

    private static String invokeGenerateSlug() throws Exception {
        Method m = OpenCodeClientExporter.class.getDeclaredMethod("generateSlug");
        m.setAccessible(true);
        return (String) m.invoke(null);
    }

    private static String invokeGenerateId(String prefix) throws Exception {
        Method m = OpenCodeClientExporter.class.getDeclaredMethod("generateId", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, prefix);
    }

    @SuppressWarnings("unchecked")
    private static List<EntryData> invokeTrimEntriesToBudget(List<EntryData> entries, int maxChars) throws Exception {
        Method m = OpenCodeClientExporter.class.getDeclaredMethod("trimEntriesToBudget", List.class, int.class);
        m.setAccessible(true);
        return (List<EntryData>) m.invoke(null, entries, maxChars);
    }

    private static JsonObject invokeBuildMessageData(
        String role, String agent, String model, long timeCreated, String projectDir, String parentMessageId)
        throws Exception {
        Method m = OpenCodeClientExporter.class.getDeclaredMethod(
            "buildMessageData", String.class, String.class, String.class, long.class, String.class, String.class);
        m.setAccessible(true);
        return (JsonObject) m.invoke(null, role, agent, model, timeCreated, projectDir, parentMessageId);
    }

    private static JsonObject invokeBuildToolInvocationPart(EntryData.ToolCall toolCall, long timeMs) throws Exception {
        Method m = OpenCodeClientExporter.class.getDeclaredMethod(
            "buildToolInvocationPart", EntryData.ToolCall.class, long.class);
        m.setAccessible(true);
        return (JsonObject) m.invoke(null, toolCall, timeMs);
    }

    private static boolean invokeHasExportableEntries(List<EntryData> entries) throws Exception {
        Method m = OpenCodeClientExporter.class.getDeclaredMethod("hasExportableEntries", List.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, entries);
    }

    private static JsonObject invokeBuildSubAgentPart(EntryData.SubAgent subAgent) throws Exception {
        Method m = OpenCodeClientExporter.class.getDeclaredMethod("buildSubAgentPart", EntryData.SubAgent.class);
        m.setAccessible(true);
        return (JsonObject) m.invoke(null, subAgent);
    }
}
