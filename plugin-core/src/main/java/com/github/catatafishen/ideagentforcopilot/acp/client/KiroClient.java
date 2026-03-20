package com.github.catatafishen.ideagentforcopilot.acp.client;

import com.github.catatafishen.ideagentforcopilot.acp.model.SessionUpdate;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AWS Kiro ACP client.
 * <p>
 * Tool prefix: {@code Running: @agentbridge/read_file} → strip {@code Running: @agentbridge/}
 * MCP: reads from config file
 * Special: extracts {@code __tool_use_purpose} from tool arguments for UI context
 */
public final class KiroClient extends AcpClient {

    private final Map<String, String> toolPurposes = new ConcurrentHashMap<>();

    public KiroClient(Project project) {
        super(project);
    }

    @Override
    public String agentId() {
        return "kiro";
    }

    @Override
    public String displayName() {
        return "Kiro";
    }

    @Override
    protected List<String> buildCommand(String cwd, int mcpPort) {
        return List.of("kiro-cli", "acp");
    }

    @Override
    protected void customizeNewSession(String cwd, int mcpPort, JsonObject params) {
        // Kiro requires mcpServers in session/new params (field is mandatory)
        JsonObject server = buildMcpStdioServer("agentbridge", mcpPort);
        if (server == null) return;
        JsonArray servers = new JsonArray();
        servers.add(server);
        params.add("mcpServers", servers);
    }

    @Override
    protected void onSessionCreated(String sessionId) {
        sendSessionMessage(sessionId, buildInstructions());
    }

    @Override
    protected String resolveToolId(String protocolTitle) {
        return protocolTitle.replaceFirst("^Running: @agentbridge/", "");
    }

    @Override
    protected SessionUpdate processUpdate(SessionUpdate update) {
        if (update instanceof SessionUpdate.ToolCall tc) {
            extractPurpose(tc);
        }
        return update;
    }

    /**
     * Get the purpose string extracted from a tool call's arguments.
     */
    public String getToolPurpose(String toolCallId) {
        return toolPurposes.getOrDefault(toolCallId, "");
    }

    private void extractPurpose(SessionUpdate.ToolCall tc) {
        String args = tc.arguments();
        if (args != null && args.contains("__tool_use_purpose")) {
            int start = args.indexOf("\"__tool_use_purpose\"");
            if (start >= 0) {
                int colonIdx = args.indexOf(':', start);
                int quoteStart = args.indexOf('"', colonIdx + 1);
                int quoteEnd = args.indexOf('"', quoteStart + 1);
                if (quoteStart >= 0 && quoteEnd > quoteStart) {
                    toolPurposes.put(tc.toolCallId(),
                        args.substring(quoteStart + 1, quoteEnd));
                }
            }
        }
    }

    private String buildInstructions() {
        return "You have access to IntelliJ IDE tools via the @agentbridge MCP server. " +
            "Use these tools for file operations, code navigation, git, and terminal access.";
    }
}
