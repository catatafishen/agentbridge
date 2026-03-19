package com.github.catatafishen.ideagentforcopilot.bridge;

import com.github.catatafishen.ideagentforcopilot.services.AgentProfile;
import com.github.catatafishen.ideagentforcopilot.services.GenericSettings;
import com.github.catatafishen.ideagentforcopilot.services.ToolPermission;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JunieAcpClientWhitelistTest {

    private JunieAcpClient client;

    @BeforeEach
    void setUp() {
        AgentProfile profile = JunieAcpClient.createDefaultProfile();
        AgentConfig config = new ProfileBasedAgentConfig(profile, null);
        AgentSettings settings = new GenericAgentSettings(new GenericSettings("junie"), null);
        client = new JunieAcpClient(config, settings, null, null, 0);
    }

    private JsonObject createToolCall(String title) {
        JsonObject toolCall = new JsonObject();
        toolCall.addProperty("title", title);
        return toolCall;
    }

    @Test
    void testWhitelistAllowsAgentBridgeTools() {
        JsonObject toolCall = createToolCall("Tool: agentbridge/read_file");
        assertEquals(ToolPermission.ALLOW, client.resolveEffectivePermission("read_file", toolCall),
            "Should allow agentbridge- prefixed tools");

        JsonObject toolCall2 = createToolCall("Tool: agentbridge/search_text");
        assertEquals(ToolPermission.ALLOW, client.resolveEffectivePermission("search_text", toolCall2),
            "Should allow agentbridge- prefixed tools");
    }

    @Test
    void testWhitelistDeniesKnownBuiltIns() {
        JsonObject toolCall = createToolCall("Tool: bash");
        assertEquals(ToolPermission.DENY, client.resolveEffectivePermission("bash", toolCall),
            "Should deny built-in bash tool");

        JsonObject toolCall2 = createToolCall("Tool: read_file");
        assertEquals(ToolPermission.DENY, client.resolveEffectivePermission("read_file", toolCall2),
            "Should deny built-in read_file tool");

        JsonObject toolCall3 = createToolCall("Tool: execute");
        assertEquals(ToolPermission.DENY, client.resolveEffectivePermission("execute", toolCall3),
            "Should deny built-in execute tool");
    }

    @Test
    void testWhitelistDeniesUnknownTools() {
        JsonObject toolCall = createToolCall("Tool: unknown_tool");
        assertEquals(ToolPermission.DENY, client.resolveEffectivePermission("unknown_tool", toolCall),
            "Should deny unknown tools by default");

        JsonObject toolCall2 = createToolCall("Tool: some_random_builtin");
        assertEquals(ToolPermission.DENY, client.resolveEffectivePermission("some_random_builtin", toolCall2),
            "Should deny unknown built-in tools");
    }

    @Test
    void testGetToolId() {
        JsonObject toolCall = createToolCall("Tool: agentbridge/read_file");
        assertEquals("read_file", client.getToolId(toolCall));

        JsonObject toolCall2 = createToolCall("Tool: read_file");
        assertEquals("read_file", client.getToolId(toolCall2));

        JsonObject toolCall3 = createToolCall("bash");
        assertEquals("bash", client.getToolId(toolCall3));
    }
}
