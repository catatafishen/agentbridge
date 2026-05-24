package com.github.catatafishen.agentbridge.session.exporters;

import com.github.catatafishen.agentbridge.bridge.EntryData;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
