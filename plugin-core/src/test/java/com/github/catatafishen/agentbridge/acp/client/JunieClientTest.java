package com.github.catatafishen.agentbridge.acp.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JunieClientTest {

    // ── resolveToolIdStatic ─────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({
        "agentbridge_read_file, read_file",
        "agentbridge_search_text, search_text",
        "agentbridge_git_status, git_status",
    })
    void resolveToolId_stripsUnderscorePrefix(String input, String expected) {
        assertEquals(expected, JunieClient.resolveToolIdStatic(input));
    }

    @ParameterizedTest
    @CsvSource({
        "Tool: agentbridge/read_file, read_file",
        "Tool: agentbridge/git_commit, git_commit",
    })
    void resolveToolId_stripsToolAgentbridgeSlashPrefix(String input, String expected) {
        assertEquals(expected, JunieClient.resolveToolIdStatic(input));
    }

    @ParameterizedTest
    @CsvSource({
        "Open file.txt, read_file",
        "open /tmp/test, read_file",
        "OPEN MyClass.java, read_file",
    })
    void resolveToolId_naturalLanguageOpen(String input, String expected) {
        assertEquals(expected, JunieClient.resolveToolIdStatic(input));
    }

    @ParameterizedTest
    @CsvSource({
        "Searched for patterns, search_text",
        "Found 5 matches in, search_text",
        "found results, search_text",
    })
    void resolveToolId_naturalLanguageSearch(String input, String expected) {
        assertEquals(expected, JunieClient.resolveToolIdStatic(input));
    }

    @ParameterizedTest
    @CsvSource({
        "Edit file.txt, edit_text",
        "EDIT MyClass.java, edit_text",
    })
    void resolveToolId_naturalLanguageEdit(String input, String expected) {
        assertEquals(expected, JunieClient.resolveToolIdStatic(input));
    }

    @ParameterizedTest
    @CsvSource({
        "Bash ls -la, run_command",
        "Run gradle build, run_command",
        "run tests, run_command",
    })
    void resolveToolId_naturalLanguageRun(String input, String expected) {
        assertEquals(expected, JunieClient.resolveToolIdStatic(input));
    }

    @ParameterizedTest
    @CsvSource({
        "Build the project, build_project",
        "build plugin, build_project",
    })
    void resolveToolId_naturalLanguageBuild(String input, String expected) {
        assertEquals(expected, JunieClient.resolveToolIdStatic(input));
    }

    @ParameterizedTest
    @CsvSource({
        "build project, build_project",
        "Build Project, build_project",
        "read file, read_file",
        "Read File, read_file",
        "edit text, edit_text",
        "Edit Text, edit_text",
        "search text, search_text",
        "Search Text, search_text",
        "run command, run_command",
        "Run Command, run_command",
    })
    void resolveToolId_caseInsensitiveExactMatch(String input, String expected) {
        assertEquals(expected, JunieClient.resolveToolIdStatic(input));
    }

    @Test
    void resolveToolId_stripsToolPrefix() {
        assertEquals("some_tool", JunieClient.resolveToolIdStatic("Tool: some_tool"));
    }

    @Test
    void resolveToolId_unknownPassthrough() {
        assertEquals("custom_thing", JunieClient.resolveToolIdStatic("custom_thing"));
    }

    @Test
    void resolveToolId_trimsWhitespace() {
        assertEquals("read_file", JunieClient.resolveToolIdStatic("  agentbridge_read_file  "));
    }

    // ── isMcpToolTitleStatic ────────────────────────────────────────────

    @Test
    void isMcpToolTitle_underscorePrefix() {
        assertTrue(JunieClient.isMcpToolTitleStatic("agentbridge_read_file"));
    }

    @Test
    void isMcpToolTitle_toolAgentbridgeSlash() {
        assertTrue(JunieClient.isMcpToolTitleStatic("Tool: agentbridge/read_file"));
    }

    @Test
    void isMcpToolTitle_naturalLanguage() {
        assertFalse(JunieClient.isMcpToolTitleStatic("Open file.txt"));
    }

    @Test
    void isMcpToolTitle_plainText() {
        assertFalse(JunieClient.isMcpToolTitleStatic("some random text"));
    }

    @Test
    void isMcpToolTitle_emptyString() {
        assertFalse(JunieClient.isMcpToolTitleStatic(""));
    }

    // ── buildPermissionOutcomeStatic ────────────────────────────────────

    @Test
    void buildPermissionOutcome_withChosenOptionKind() {
        JsonObject option = new JsonObject();
        option.addProperty("kind", "AllowOnce");
        option.addProperty("label", "Allow");

        JsonObject result = JunieClient.buildPermissionOutcomeStatic("option-1", option);
        assertEquals("selected", result.get("outcome").getAsString());
        assertEquals("AllowOnce", result.get("kind").getAsString());
        assertEquals("option-1", result.get("optionId").getAsString());
    }

    @Test
    void buildPermissionOutcome_withoutKindUsesOptionId() {
        JsonObject option = new JsonObject();
        option.addProperty("label", "Allow");

        JsonObject result = JunieClient.buildPermissionOutcomeStatic("opt-2", option);
        assertEquals("opt-2", result.get("kind").getAsString());
        assertEquals("opt-2", result.get("optionId").getAsString());
    }

    @Test
    void buildPermissionOutcome_nullOption() {
        JsonObject result = JunieClient.buildPermissionOutcomeStatic("fallback-id", null);
        assertEquals("selected", result.get("outcome").getAsString());
        assertEquals("fallback-id", result.get("kind").getAsString());
        assertEquals("fallback-id", result.get("optionId").getAsString());
    }

    // ── buildInstructionsStatic ─────────────────────────────────────────

    @Test
    void buildInstructions_noUserInstructions() {
        String result = JunieClient.buildInstructionsStatic(null);
        assertTrue(result.startsWith("CRITICAL:"));
        assertTrue(result.contains("agentbridge"));
        assertFalse(result.contains("\n\n"));
    }

    @Test
    void buildInstructions_withUserInstructions() {
        String result = JunieClient.buildInstructionsStatic("Be concise.");
        assertTrue(result.startsWith("CRITICAL:"));
        assertTrue(result.contains("agentbridge"));
        assertTrue(result.endsWith("Be concise."));
    }

    @Test
    void buildInstructions_blankUserInstructions() {
        String result = JunieClient.buildInstructionsStatic("   ");
        assertFalse(result.contains("\n\n"), "Blank user instructions should be ignored");
    }

    @Test
    void buildInstructions_emptyUserInstructions() {
        String result = JunieClient.buildInstructionsStatic("");
        assertFalse(result.contains("\n\n"), "Empty user instructions should be ignored");
    }

    @Test
    void buildInstructions_containsExampleMapping() {
        String result = JunieClient.buildInstructionsStatic(null);
        assertTrue(result.contains("agentbridge/read_file"));
        assertTrue(result.contains("open_file"));
    }

    // ── isJuniePermissionBug (private static) ───────────────────────────

    @Test
    void isJuniePermissionBug_matchesDirectMessage() throws Exception {
        assertTrue(invokeIsJuniePermissionBug(
            new RuntimeException("RequestPermissionOutcome was not received")));
    }

    @Test
    void isJuniePermissionBug_matchesInCauseChain() throws Exception {
        Exception root = new RuntimeException("RequestPermissionOutcome timeout");
        Exception wrapper = new RuntimeException("prompt failed", root);
        assertTrue(invokeIsJuniePermissionBug(wrapper));
    }

    @Test
    void isJuniePermissionBug_noMatch() throws Exception {
        assertFalse(invokeIsJuniePermissionBug(new RuntimeException("connection refused")));
    }

    @Test
    void isJuniePermissionBug_nullMessage() throws Exception {
        assertFalse(invokeIsJuniePermissionBug(new RuntimeException((String) null)));
    }

    // ── tryParseArgsFromContentItem (private static) ────────────────────

    @Test
    void tryParseArgs_directTextBlock() throws Exception {
        JsonObject block = new JsonObject();
        block.addProperty("type", "text");
        block.addProperty("text", "{\"path\": \"test.txt\"}");
        JsonObject result = invokeTryParseArgs(block);
        assertNotNull(result);
        assertEquals("test.txt", result.get("path").getAsString());
    }

    @Test
    void tryParseArgs_nestedContentBlock() throws Exception {
        JsonObject inner = new JsonObject();
        inner.addProperty("type", "text");
        inner.addProperty("text", "{\"query\": \"hello\"}");

        JsonObject outer = new JsonObject();
        outer.addProperty("type", "content");
        outer.add("content", inner);

        JsonObject result = invokeTryParseArgs(outer);
        assertNotNull(result);
        assertEquals("hello", result.get("query").getAsString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json at all", "[1, 2, 3]", "   "})
    void tryParseArgs_invalidTextReturnsNull(String text) throws Exception {
        JsonObject block = new JsonObject();
        block.addProperty("text", text);
        assertNull(invokeTryParseArgs(block));
    }

    @Test
    void tryParseArgs_notJsonObject() throws Exception {
        assertNull(invokeTryParseArgs(new JsonPrimitive("hello")));
    }

    @Test
    void tryParseArgs_noTextKey() throws Exception {
        JsonObject block = new JsonObject();
        block.addProperty("type", "image");
        assertNull(invokeTryParseArgs(block));
    }

    // ── Reflection helpers ──────────────────────────────────────────────

    private static boolean invokeIsJuniePermissionBug(Throwable t) throws Exception {
        Method m = JunieClient.class.getDeclaredMethod("isJuniePermissionBug", Throwable.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, t);
    }

    private static JsonObject invokeTryParseArgs(JsonElement item) throws Exception {
        Method m = JunieClient.class.getDeclaredMethod("tryParseArgsFromContentItem", JsonElement.class);
        m.setAccessible(true);
        return (JsonObject) m.invoke(null, item);
    }
}
