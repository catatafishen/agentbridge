package com.github.catatafishen.agentbridge.acp.client;

import com.github.catatafishen.agentbridge.acp.model.Model;
import com.github.catatafishen.agentbridge.acp.model.PromptResponse;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CopilotClientTest {

    // ── buildAgentDefinition (private static) ───────────────────────────

    @Test
    void buildAgentDefinition_format() throws Exception {
        String result = invokeBuildAgentDefinition(
            "test-agent", "A test agent",
            List.of("agentbridge/read_file", "agentbridge/search_text"),
            "  You are a test agent.\n  Do test things."
        );
        assertTrue(result.startsWith("---\n"));
        assertTrue(result.contains("name: test-agent\n"));
        assertTrue(result.contains("description: \"A test agent\"\n"));
        assertTrue(result.contains("tools:\n"));
        assertTrue(result.contains("  - agentbridge/read_file\n"));
        assertTrue(result.contains("  - agentbridge/search_text\n"));
        assertTrue(result.contains("---\n\n"));
        assertTrue(result.contains("You are a test agent."));
    }

    @Test
    void buildAgentDefinition_stripsLeadingWhitespace() throws Exception {
        String result = invokeBuildAgentDefinition("agent", "desc", List.of(), "\n\n  Body text");
        assertTrue(result.endsWith("Body text"));
    }

    @Test
    void buildAgentDefinition_emptyTools() throws Exception {
        String result = invokeBuildAgentDefinition("agent", "desc", List.of(), "prompt");
        assertTrue(result.contains("tools:\n---\n"));
    }

    // ── merge (private static) ──────────────────────────────────────────

    @Test
    void merge_addsPrefixToMcpTools() throws Exception {
        List<String> result = invokeMerge(List.of("read_file", "search_text"));
        assertTrue(result.contains("agentbridge/read_file"));
        assertTrue(result.contains("agentbridge/search_text"));
        assertEquals(0, result.indexOf("agentbridge/read_file"));
        assertEquals(1, result.indexOf("agentbridge/search_text"));
    }

    @Test
    void merge_emptyMcpTools_returnsOnlyWebTools() throws Exception {
        List<String> result = invokeMerge(List.of());
        assertTrue(result.contains("web_fetch"));
        assertTrue(result.contains("web_search"));
        assertFalse(result.stream().anyMatch(t -> t.startsWith("agentbridge/")));
    }

    @Test
    void merge_onlyMcpTools() throws Exception {
        List<String> result = invokeMerge(List.of("git_status"));
        assertTrue(result.contains("agentbridge/git_status"));
        assertTrue(result.contains("web_fetch"));
    }

    @Test
    void merge_alwaysIncludesWebTools() throws Exception {
        List<String> result = invokeMerge(List.of("read_file"));
        assertTrue(result.contains("web_fetch"));
        assertTrue(result.contains("web_search"));
    }

    // ── mcpAlternative (private static) ─────────────────────────────────

    @Test
    void mcpAlternative_bash() throws Exception {
        assertTrue(invokeMcpAlternative("bash").contains("agentbridge-run_command"));
    }

    @Test
    void mcpAlternative_edit() throws Exception {
        assertTrue(invokeMcpAlternative("edit").contains("agentbridge-edit_text"));
    }

    @Test
    void mcpAlternative_create() throws Exception {
        assertEquals("agentbridge-create_file", invokeMcpAlternative("create"));
    }

    @Test
    void mcpAlternative_view() throws Exception {
        assertEquals("agentbridge-read_file", invokeMcpAlternative("view"));
    }

    @Test
    void mcpAlternative_glob() throws Exception {
        assertEquals("agentbridge-list_project_files", invokeMcpAlternative("glob"));
    }

    @Test
    void mcpAlternative_grep() throws Exception {
        assertEquals("agentbridge-search_text", invokeMcpAlternative("grep"));
    }

    @Test
    void mcpAlternative_reportIntent() throws Exception {
        assertTrue(invokeMcpAlternative("report_intent").contains("not needed"));
    }

    @Test
    void mcpAlternative_unknown() throws Exception {
        assertEquals("the corresponding agentbridge-* tool", invokeMcpAlternative("some_unknown"));
    }

    // ── buildToolReprimand (private static) ─────────────────────────────

    @Test
    void buildToolReprimand_singleTool() throws Exception {
        Set<String> tools = new LinkedHashSet<>();
        tools.add("bash");
        String result = invokeBuildToolReprimand(tools);
        assertTrue(result.contains("[System notice]"));
        assertTrue(result.contains("bash"));
        assertTrue(result.contains("agentbridge-run_command"));
        assertTrue(result.contains("Do NOT use these again"));
    }

    @Test
    void buildToolReprimand_multipleTools() throws Exception {
        Set<String> tools = new LinkedHashSet<>();
        tools.add("bash");
        tools.add("view");
        String result = invokeBuildToolReprimand(tools);
        assertTrue(result.contains("bash"));
        assertTrue(result.contains("view"));
        assertTrue(result.contains("agentbridge-read_file"));
    }

    // ── Reflection helpers ──────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static String invokeBuildAgentDefinition(String name, String description,
                                                     List<String> tools, String systemPrompt) throws Exception {
        Method m = CopilotClient.class.getDeclaredMethod("buildAgentDefinition",
            String.class, String.class, List.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, name, description, tools, systemPrompt);
    }

    @SuppressWarnings("unchecked")
    private static List<String> invokeMerge(List<String> mcpTools) throws Exception {
        Method m = CopilotClient.class.getDeclaredMethod("merge", List.class);
        m.setAccessible(true);
        return (List<String>) m.invoke(null, mcpTools);
    }

    private static String invokeMcpAlternative(String builtInTool) throws Exception {
        Method m = CopilotClient.class.getDeclaredMethod("mcpAlternative", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, builtInTool);
    }

    private static String invokeBuildToolReprimand(Set<String> tools) throws Exception {
        Method m = CopilotClient.class.getDeclaredMethod("buildToolReprimand", Set.class);
        m.setAccessible(true);
        return (String) m.invoke(null, tools);
    }

    // ── resolveToolId (instance override — strips agentbridge- prefix) ──

    @Test
    void resolveToolId_stripsAgentbridgePrefix() throws Exception {
        assertEquals("read_file", invokeResolveToolId("agentbridge-read_file"));
    }

    @Test
    void resolveToolId_noPrefixUnchanged() throws Exception {
        assertEquals("bash", invokeResolveToolId("bash"));
    }

    // ── tryRecoverPromptException (instance override — timeout recovery) ─

    @Test
    void tryRecoverPromptException_directTimeoutReturnsEndTurn() throws Exception {
        PromptResponse r = invokeTryRecover(
            new java.util.concurrent.TimeoutException("timed out"));
        assertNotNull(r);
        assertEquals("end_turn", r.stopReason());
        assertNull(r.usage());
    }

    @Test
    void tryRecoverPromptException_wrappedTimeoutReturnsEndTurn() throws Exception {
        PromptResponse r = invokeTryRecover(
            new RuntimeException("wrap",
                new java.util.concurrent.TimeoutException("root")));
        assertNotNull(r);
        assertEquals("end_turn", r.stopReason());
    }

    @Test
    void tryRecoverPromptException_nonTimeoutReturnsNull() throws Exception {
        assertNull(invokeTryRecover(new java.io.IOException("network")));
    }

    // ── getModelMultiplier (instance — extracts _meta.copilotUsage) ─────

    @Test
    void getModelMultiplier_withCopilotUsage() throws Exception {
        JsonObject meta = new JsonObject();
        meta.addProperty("copilotUsage", "1x");
        assertEquals("1x", invokeGetModelMultiplier(
            new Model("gpt-4", "GPT-4", null, meta)));
    }

    @Test
    void getModelMultiplier_noCopilotUsageField() throws Exception {
        JsonObject meta = new JsonObject();
        meta.addProperty("other", "value");
        assertNull(invokeGetModelMultiplier(
            new Model("gpt-4", "GPT-4", null, meta)));
    }

    @Test
    void getModelMultiplier_nullMeta() throws Exception {
        assertNull(invokeGetModelMultiplier(
            new Model("gpt-4", "GPT-4", null, null)));
    }

    // ── parseToolCallArguments (instance override — rawInput fallback) ───

    @Test
    void parseToolCallArguments_rawInputFallback() throws Exception {
        JsonObject raw = new JsonObject();
        raw.addProperty("command", "ls");
        JsonObject params = new JsonObject();
        params.add("rawInput", raw);
        JsonObject result = invokeParseToolCallArguments(params);
        assertNotNull(result);
        assertEquals("ls", result.get("command").getAsString());
    }

    @Test
    void parseToolCallArguments_neitherReturnsNull() throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("toolCallId", "abc");
        assertNull(invokeParseToolCallArguments(params));
    }

    // ── buildSingleToolReprimand (private static) ───────────────────────

    @Test
    void buildSingleToolReprimand_containsToolAndAlternative() throws Exception {
        String result = invokeBuildSingleToolReprimand("bash");
        assertTrue(result.contains("[System notice]"));
        assertTrue(result.contains("bash"));
        assertTrue(result.contains("agentbridge-run_command"));
    }

    // ── copilotHome (package-private static) ────────────────────────────

    @Test
    void copilotHome_returnsDotCopilotUnderUserHome() {
        Path home = CopilotClient.copilotHome();
        assertEquals(Path.of(System.getProperty("user.home"), ".copilot"), home);
    }

    // ── Reflection helpers (instance methods via Mockito) ───────────────

    private static CopilotClient allocateClient() {
        // JBR 25 restricts Mockito's CALLS_REAL_METHODS — construct directly.
        // Constructor only stores the project; null is acceptable for the methods tested here.
        return new CopilotClient(null);
    }

    private static String invokeResolveToolId(String title) throws Exception {
        Method m = CopilotClient.class.getDeclaredMethod("resolveToolId", String.class);
        m.setAccessible(true);
        return (String) m.invoke(allocateClient(), title);
    }

    private static PromptResponse invokeTryRecover(Exception cause) throws Exception {
        Method m = CopilotClient.class.getDeclaredMethod("tryRecoverPromptException", Exception.class);
        m.setAccessible(true);
        return (PromptResponse) m.invoke(allocateClient(), cause);
    }

    private static String invokeGetModelMultiplier(Model model) throws Exception {
        Method m = CopilotClient.class.getDeclaredMethod("getModelMultiplier", Model.class);
        m.setAccessible(true);
        return (String) m.invoke(allocateClient(), model);
    }

    private static JsonObject invokeParseToolCallArguments(JsonObject params) throws Exception {
        Method m = CopilotClient.class.getDeclaredMethod("parseToolCallArguments", JsonObject.class);
        m.setAccessible(true);
        return (JsonObject) m.invoke(allocateClient(), params);
    }

    private static String invokeBuildSingleToolReprimand(String toolId) throws Exception {
        Method m = CopilotClient.class.getDeclaredMethod("buildSingleToolReprimand", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, toolId);
    }

    @Nested
    class MergeMcpConfig {

        @Test
        void createsNewConfigWhenNoneExists(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
            CopilotClient.mergeMcpConfigStatic(tempDir, 9876, "agentbridge", "http");

            java.nio.file.Path configPath = tempDir.resolve("mcp-config.json");
            assertTrue(java.nio.file.Files.exists(configPath));

            JsonObject root = JsonParser.parseString(
                java.nio.file.Files.readString(configPath)).getAsJsonObject();
            assertTrue(root.has("mcpServers"));
            JsonObject servers = root.getAsJsonObject("mcpServers");
            assertTrue(servers.has("agentbridge"));
            JsonObject entry = servers.getAsJsonObject("agentbridge");
            assertEquals("http", entry.get("type").getAsString());
            assertEquals("http://localhost:9876/mcp", entry.get("url").getAsString());
        }

        @Test
        void preservesExistingServers(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
            // Pre-populate with an existing server
            JsonObject existingServer = new JsonObject();
            existingServer.addProperty("type", "stdio");
            existingServer.addProperty("command", "some-server");
            JsonObject servers = new JsonObject();
            servers.add("my-server", existingServer);
            JsonObject root = new JsonObject();
            root.add("mcpServers", servers);

            java.nio.file.Path configPath = tempDir.resolve("mcp-config.json");
            java.nio.file.Files.writeString(configPath, root.toString());

            CopilotClient.mergeMcpConfigStatic(tempDir, 1234, "agentbridge", "http");

            JsonObject updated = JsonParser.parseString(
                java.nio.file.Files.readString(configPath)).getAsJsonObject();
            JsonObject updatedServers = updated.getAsJsonObject("mcpServers");

            // Existing server should still be there
            assertTrue(updatedServers.has("my-server"));
            assertEquals("stdio", updatedServers.getAsJsonObject("my-server").get("type").getAsString());

            // New server should be added
            assertTrue(updatedServers.has("agentbridge"));
            assertEquals("http://localhost:1234/mcp",
                updatedServers.getAsJsonObject("agentbridge").get("url").getAsString());
        }

        @Test
        void updatesExistingAgentbridgeEntry(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
            // Pre-populate with an old agentbridge entry
            JsonObject oldEntry = new JsonObject();
            oldEntry.addProperty("type", "http");
            oldEntry.addProperty("url", "http://localhost:9999/mcp");
            JsonObject servers = new JsonObject();
            servers.add("agentbridge", oldEntry);
            JsonObject root = new JsonObject();
            root.add("mcpServers", servers);

            java.nio.file.Files.writeString(tempDir.resolve("mcp-config.json"), root.toString());

            CopilotClient.mergeMcpConfigStatic(tempDir, 5555, "agentbridge", "http");

            JsonObject updated = JsonParser.parseString(
                java.nio.file.Files.readString(tempDir.resolve("mcp-config.json"))).getAsJsonObject();
            assertEquals("http://localhost:5555/mcp",
                updated.getAsJsonObject("mcpServers").getAsJsonObject("agentbridge")
                    .get("url").getAsString());
        }

        @Test
        void handlesCorruptedMcpServersField(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
            // mcpServers is a string instead of object
            JsonObject root = new JsonObject();
            root.addProperty("mcpServers", "corrupted");
            java.nio.file.Files.writeString(tempDir.resolve("mcp-config.json"), root.toString());

            CopilotClient.mergeMcpConfigStatic(tempDir, 4321, "agentbridge", "http");

            JsonObject updated = JsonParser.parseString(
                java.nio.file.Files.readString(tempDir.resolve("mcp-config.json"))).getAsJsonObject();
            // Should have replaced the corrupted field with a proper object
            assertTrue(updated.get("mcpServers").isJsonObject());
            assertTrue(updated.getAsJsonObject("mcpServers").has("agentbridge"));
        }
    }
}
