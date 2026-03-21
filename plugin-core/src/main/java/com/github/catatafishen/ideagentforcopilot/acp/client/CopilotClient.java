package com.github.catatafishen.ideagentforcopilot.acp.client;

import com.github.catatafishen.ideagentforcopilot.acp.model.Model;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * GitHub Copilot ACP client.
 * <p>
 * Command: {@code copilot --acp --stdio [--config-dir ...]}
 * Tool prefix: {@code agentbridge-read_file} → strip {@code agentbridge-}
 * Model display: multiplier from {@code _meta.copilotUsage}
 * References: requires inline (no ACP resource blocks)
 * MCP: HTTP via {@code mcpServers} in {@code session/new}
 */
public final class CopilotClient extends AcpClient {

    private static final String AGENT_ID = "copilot";
    private static final String MCP_SERVER_NAME = "agentbridge";
    private static final String MCP_TYPE_HTTP = "http";

    public CopilotClient(Project project) {
        super(project);
    }

    @Override
    public String agentId() {
        return AGENT_ID;
    }

    @Override
    public String displayName() {
        return "GitHub Copilot";
    }

    @Override
    protected List<String> buildCommand(String cwd, int mcpPort) {
        // Use per-project config dir to avoid cross-project contamination
        String configDir = cwd + File.separator + ".agent-work" + File.separator + AGENT_ID;
        return List.of(AGENT_ID, "--acp", "--stdio", "--config-dir", configDir);
    }

    @Override
    protected Map<String, String> buildEnvironment(int mcpPort, String cwd) {
        // COPILOT_HOME points to the project-specific Copilot config directory
        String copilotHome = cwd + File.separator + ".agent-work" + File.separator + AGENT_ID;
        return Map.of("COPILOT_HOME", copilotHome);
    }

    @Override
    protected void customizeNewSession(String cwd, int mcpPort, JsonObject params) {
        // Copilot requires mcpServers in session/new as an array with headers as array (not object)
        JsonObject server = new JsonObject();
        server.addProperty("name", MCP_SERVER_NAME);
        server.addProperty("type", MCP_TYPE_HTTP);
        server.addProperty("url", "http://localhost:" + mcpPort + "/mcp");
        server.add("headers", new JsonArray()); // Copilot requires headers as empty array

        JsonArray servers = new JsonArray();
        servers.add(server);
        params.add("mcpServers", servers);
    }

    @Override
    protected String resolveToolId(String protocolTitle) {
        return protocolTitle.replaceFirst("^agentbridge-", "");
    }

    @Override
    public boolean requiresInlineReferences() {
        return true;
    }

    @Override
    public ModelDisplayMode modelDisplayMode() {
        return ModelDisplayMode.MULTIPLIER;
    }

    @Override
    public @Nullable String getModelMultiplier(Model model) {
        JsonObject meta = model._meta();
        if (meta != null && meta.has("copilotUsage")) {
            return meta.get("copilotUsage").getAsString();
        }
        return null;
    }
}
