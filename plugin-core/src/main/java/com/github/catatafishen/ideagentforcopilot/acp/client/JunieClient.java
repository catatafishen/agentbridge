package com.github.catatafishen.ideagentforcopilot.acp.client;

import com.github.catatafishen.ideagentforcopilot.acp.model.SessionUpdate;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;

import java.util.List;

/**
 * JetBrains Junie ACP client.
 * <p>
 * Tool prefix: {@code Tool: agentbridge/read_file} → strip {@code Tool: agentbridge/}
 * MCP: injected via session/new mcpServers array
 * Model display: token count
 * Special: ToolExecutionCorrelator for matching MCP results with natural-language summaries
 */
public final class JunieClient extends AcpClient {

    public JunieClient(Project project) {
        super(project);
    }

    @Override
    public String agentId() {
        return "junie";
    }

    @Override
    public String displayName() {
        return "Junie";
    }

    @Override
    protected List<String> buildCommand(String cwd, int mcpPort) {
        return List.of("junie", "--acp=true");
    }

    @Override
    protected void customizeNewSession(String cwd, int mcpPort, JsonObject params) {
        // Junie injects MCP via session/new mcpServers array using stdio (command + args)
        JsonObject server = buildMcpStdioServer("agentbridge", mcpPort);
        if (server == null) return;
        JsonArray servers = new JsonArray();
        servers.add(server);
        params.add("mcpServers", servers);
    }

    @Override
    protected void onSessionCreated(String sessionId) {
        // Inject tool usage instructions
        sendSessionMessage(sessionId, buildInstructions());
    }

    @Override
    protected String resolveToolId(String protocolTitle) {
        return protocolTitle.replaceFirst("^Tool: agentbridge/", "");
    }

    @Override
    protected SessionUpdate processUpdate(SessionUpdate update) {
        // TODO: Wire up ToolExecutionCorrelator for Junie-specific result matching
        return update;
    }

    @Override
    public ModelDisplayMode modelDisplayMode() {
        return ModelDisplayMode.TOKEN_COUNT;
    }

    private String buildInstructions() {
        return "You have access to IntelliJ IDE tools via the agentbridge MCP server. " +
            "Use these tools for file operations, code navigation, git, and terminal access.";
    }
}
