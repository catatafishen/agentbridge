package com.github.catatafishen.agentbridge.acp.client;

import com.github.catatafishen.agentbridge.acp.model.Model;
import com.github.catatafishen.agentbridge.acp.model.PromptResponse;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

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
        List<String> result = invokeMerge(
            List.of("read_file", "search_text"),
            List.of("bash", "view")
        );
        assertEquals(4, result.size());
        assertEquals("agentbridge/read_file", result.get(0));
        assertEquals("agentbridge/search_text", result.get(1));
        assertEquals("bash", result.get(2));
        assertEquals("view", result.get(3));
    }

    @Test
    void merge_emptyLists() throws Exception {
        assertTrue(invokeMerge(List.of(), List.of()).isEmpty());
    }

    @Test
    void merge_onlyMcpTools() throws Exception {
        List<String> result = invokeMerge(List.of("git_status"), List.of());
        assertEquals(1, result.size());
        assertEquals("agentbridge/git_status", result.getFirst());
    }

    @Test
    void merge_onlyBuiltinTools() throws Exception {
        List<String> result = invokeMerge(List.of(), List.of("bash"));
        assertEquals(1, result.size());
        assertEquals("bash", result.getFirst());
    }

    // ── resolveAcpName (protected instance — Copilot-specific override) ─────

    @Test
    void resolveAcpName_mcpTool() throws Exception {
        assertEquals("read_file", invokeResolveAcpName("agentbridge-read_file", "read"));
    }

    @Test
    void resolveAcpName_knownBuiltinTool() throws Exception {
        assertEquals("bash", invokeResolveAcpName("bash", "execute"));
    }

    @Test
    void resolveAcpName_unknownFallsBackToKind() throws Exception {
        assertEquals("read", invokeResolveAcpName("Some unknown tool", "read"));
    }

    // ── Reflection helpers (private static methods) ───────────────────

    private static String invokeBuildAgentDefinition(String name, String description,
                                                     List<String> tools, String systemPrompt) throws Exception {
        Method m = CopilotClient.class.getDeclaredMethod("buildAgentDefinition",
            String.class, String.class, List.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, name, description, tools, systemPrompt);
    }

    @SuppressWarnings("unchecked")
    private static List<String> invokeMerge(List<String> mcpTools, List<String> builtinTools) throws Exception {
        Method m = CopilotClient.class.getDeclaredMethod("merge", List.class, List.class);
        m.setAccessible(true);
        return (List<String>) m.invoke(null, mcpTools, builtinTools);
    }

    private static String invokeResolveAcpName(String title, String kind) throws Exception {
        Method m = CopilotClient.class.getDeclaredMethod("resolveAcpName", String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(allocateClient(), title, kind);
    }

    // ── resolveToolId (instance override — normalizes tool names) ──

    @Test
    void resolveToolId_stripsAgentbridgePrefix() throws Exception {
        assertEquals("read_file", invokeResolveToolId("agentbridge-read_file"));
    }

    @Test
    void resolveToolId_knownBuiltinTool() throws Exception {
        assertEquals("bash", invokeResolveToolId("bash"));
    }

    @Test
    void resolveToolId_humanReadableDescriptionPassedThrough() throws Exception {
        // Unrecognized titles (e.g. sub-agent task descriptions) are returned as-is;
        // ToolCallTracker will correct the display name once MCP execution correlates.
        assertEquals("Post review comment on PR 500", invokeResolveToolId("Post review comment on PR 500"));
    }

    @Test
    void resolveToolId_knownBuiltinToolCaseInsensitive() throws Exception {
        assertEquals("grep", invokeResolveToolId("Grep"));
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

    // ── buildReprimand (private static) ───────────────────────────────

    @Test
    void buildReprimand_readKind() throws Exception {
        String result = invokeBuildReprimand("unknown-tool", "read");
        assertTrue(result.contains("a built-in read tool"), "kind should appear in message as 'a built-in read tool'");
        assertTrue(result.startsWith("Always prefer AgentBridge MCP tools."), "message should start with the standard prefix");
    }

    @Test
    void buildReprimand_executeKind() throws Exception {
        String result = invokeBuildReprimand("unknown-tool", "execute");
        assertTrue(result.contains("a built-in execute tool"), "kind should appear in message as 'a built-in execute tool'");
        assertTrue(result.startsWith("Always prefer AgentBridge MCP tools."), "message should start with the standard prefix");
    }

    @Test
    void buildReprimand_knownToolName() throws Exception {
        String result = invokeBuildReprimand("bash", "execute");
        assertTrue(result.contains("the `bash` built-in tool"), "known tool name should be referenced directly");
    }

    // ── shouldReprimand (private static) ────────────────────────────────

    @Test
    void shouldReprimand_webFetchByName_isSuppressed() throws Exception {
        assertFalse(invokeShouldReprimand("web_fetch"));
        assertFalse(invokeShouldReprimand("web_search"));
    }

    @Test
    void shouldReprimand_webFetchDescription_isSuppressed() throws Exception {
        // Copilot CLI sends "Fetching <url>" as the ACP title for web_fetch calls.
        assertFalse(invokeShouldReprimand("Fetching https://www.jetbrains.com/help/idea/mcp-server.html"));
        assertFalse(invokeShouldReprimand("Fetching www.example.com/page"));
        assertFalse(invokeShouldReprimand("Fetch https://api.example.com/data"));
        assertFalse(invokeShouldReprimand("Some URL https://example.com result"));
    }

    @Test
    void shouldReprimand_sqlToolByName_isSuppressed() throws Exception {
        assertFalse(invokeShouldReprimand("sql"));
    }

    @Test
    void shouldReprimand_sqlToolDescription_isSuppressed() throws Exception {
        // Copilot CLI sends the sql tool's description parameter as the ACP title.
        assertFalse(invokeShouldReprimand("Query ready todos"));
        assertFalse(invokeShouldReprimand("Insert auth todos"));
        assertFalse(invokeShouldReprimand("select * from todos"));
    }

    @Test
    void shouldReprimand_metaTools_areSuppressed() throws Exception {
        assertFalse(invokeShouldReprimand("report_intent"));
        assertFalse(invokeShouldReprimand("skill"));
        assertFalse(invokeShouldReprimand("task_complete"));
    }

    @Test
    void shouldReprimand_bashAndDuplicates_areReprimanded() throws Exception {
        assertTrue(invokeShouldReprimand("bash"));
        assertTrue(invokeShouldReprimand("view"));
        assertTrue(invokeShouldReprimand("grep"));
        assertTrue(invokeShouldReprimand("glob"));
        // bash-with-description that has no URL or SQL pattern
        assertTrue(invokeShouldReprimand("TypeScript compile check"));
        assertTrue(invokeShouldReprimand("Read PsiBridgeService constructor"));
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

    private static String invokeBuildReprimand(String title, String kind) throws Exception {
        Method m = CopilotClient.class.getDeclaredMethod("buildReprimand", String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, title, kind);
    }

    private static boolean invokeShouldReprimand(String toolId) throws Exception {
        Method m = CopilotClient.class.getDeclaredMethod("shouldReprimand", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, toolId);
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
