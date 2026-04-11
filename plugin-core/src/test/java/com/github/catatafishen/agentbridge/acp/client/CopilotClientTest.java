package com.github.catatafishen.agentbridge.acp.client;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

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
        assertEquals("agentbridge/git_status", result.get(0));
    }

    @Test
    void merge_onlyBuiltinTools() throws Exception {
        List<String> result = invokeMerge(List.of(), List.of("bash"));
        assertEquals(1, result.size());
        assertEquals("bash", result.get(0));
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
    private static List<String> invokeMerge(List<String> mcpTools, List<String> builtinTools) throws Exception {
        Method m = CopilotClient.class.getDeclaredMethod("merge", List.class, List.class);
        m.setAccessible(true);
        return (List<String>) m.invoke(null, mcpTools, builtinTools);
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
}
