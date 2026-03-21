package com.github.catatafishen.ideagentforcopilot.acp.client;

import com.github.catatafishen.ideagentforcopilot.acp.model.SessionUpdate;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;

import java.util.List;

/**
 * AWS Kiro ACP client.
 * <p>
 * Tool prefix: {@code Running: @agentbridge/read_file} → strip {@code Running: @agentbridge/}
 * MCP: reads from config file
 * Special: extracts {@code __tool_use_purpose} from rawInput and populates ToolCall.purpose
 */
public final class KiroClient extends AcpClient {

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
        if (server == null) {
            throw new IllegalStateException("Cannot configure Kiro MCP server — Java binary or mcp-server.jar not found");
        }
        JsonArray servers = new JsonArray();
        servers.add(server);
        params.add("mcpServers", servers);
    }

    @Override
    protected String resolveToolId(String protocolTitle) {
        return protocolTitle.replaceFirst("^Running: @agentbridge/", "");
    }

    @Override
    protected SessionUpdate processUpdate(SessionUpdate update) {
        if (update instanceof SessionUpdate.ToolCall tc) {
            return extractPurpose(tc);
        }
        return update;
    }

    private SessionUpdate.ToolCall extractPurpose(SessionUpdate.ToolCall tc) {
        String args = tc.arguments();
        if (args != null && args.contains("__tool_use_purpose")) {
            int start = args.indexOf("\"__tool_use_purpose\"");
            if (start >= 0) {
                int colonIdx = args.indexOf(':', start);
                int quoteStart = args.indexOf('"', colonIdx + 1);
                int quoteEnd = args.indexOf('"', quoteStart + 1);
                if (quoteStart >= 0 && quoteEnd > quoteStart) {
                    String purpose = args.substring(quoteStart + 1, quoteEnd);
                    return new SessionUpdate.ToolCall(
                        tc.toolCallId(), tc.title(), tc.kind(), tc.arguments(),
                        tc.locations(), tc.agentType(), tc.subAgentDescription(),
                        tc.subAgentPrompt(), purpose
                    );
                }
            }
        }
        return tc;
    }
}
