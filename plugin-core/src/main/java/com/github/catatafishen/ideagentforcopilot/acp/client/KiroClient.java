package com.github.catatafishen.ideagentforcopilot.acp.client;

import com.github.catatafishen.ideagentforcopilot.acp.model.SessionUpdate;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class KiroClient extends AcpClient {

    private static final String KEY_RAW_INPUT = "rawInput";

    public KiroClient(Project project) {
        super(project);
    }

    @Override
    public String displayName() {
        return "Kiro";
    }

    @Override
    public String agentId() {
        return "kiro";
    }

    @Override
    protected String resolveToolId(String protocolTitle) {
        if (protocolTitle.startsWith("@agentbridge/")) {
            return protocolTitle.substring("@agentbridge/".length());
        }
        return protocolTitle.replaceFirst("^Running: @agentbridge/", "");
    }

    @Override
    protected boolean isMcpToolTitle(@org.jetbrains.annotations.NotNull String protocolTitle) {
        return protocolTitle.startsWith("Running: @agentbridge/")
            || protocolTitle.startsWith("@agentbridge/");
    }

    @Override
    protected List<String> buildCommand(String cwd, int mcpPort) {
        com.github.catatafishen.ideagentforcopilot.services.AgentProfile profile =
            com.github.catatafishen.ideagentforcopilot.services.ActiveAgentManager.getInstance(project).getActiveProfile();

        if (profile.isExcludeAgentBuiltInTools()) {
            return List.of("kiro-cli", "--agent", "intellij-task", "acp");
        }
        return List.of("kiro-cli", "acp");
    }

    @Override
    protected void beforeLaunch(String cwd, int mcpPort) throws java.io.IOException {
        com.github.catatafishen.ideagentforcopilot.services.AgentProfile profile =
            com.github.catatafishen.ideagentforcopilot.services.ActiveAgentManager.getInstance(project).getActiveProfile();

        if (profile.isExcludeAgentBuiltInTools()) {
            java.nio.file.Path kiroDir = java.nio.file.Path.of(cwd, ".agent-work", ".kiro", "agents");
            java.nio.file.Files.createDirectories(kiroDir);
            java.nio.file.Path agentPath = kiroDir.resolve("intellij-task.json");

            JsonObject agent = new JsonObject();
            agent.addProperty("name", "intellij-task");
            agent.addProperty("description", "IDE-only agent");

            JsonArray tools = new JsonArray();
            tools.add("@agentbridge/*");
            tools.add("web_fetch");
            tools.add("web_search");
            agent.add("tools", tools);

            JsonArray allowedTools = new JsonArray();
            allowedTools.add("@agentbridge/*");
            agent.add("allowedTools", allowedTools);

            try (java.io.Writer writer = java.nio.file.Files.newBufferedWriter(agentPath)) {
                gson.toJson(agent, writer);
                com.intellij.openapi.diagnostic.Logger.getInstance(KiroClient.class)
                    .info("Kiro: wrote agent definition to " + agentPath + " to restrict built-in tools");
            }
        }
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
    protected JsonObject parseToolCallArguments(@NotNull JsonObject update) {
        // Kiro sends args in "rawInput" (object) instead of "content" (array)
        return update.has(KEY_RAW_INPUT) && update.get(KEY_RAW_INPUT).isJsonObject()
            ? update.getAsJsonObject(KEY_RAW_INPUT)
            : null;
    }

    @Override
    protected SessionUpdate processUpdate(SessionUpdate update) {
        if (update instanceof SessionUpdate.ToolCall tc) {
            // Kiro sends multiple tool_call updates for the same toolCallId:
            // 1. First with just title (e.g., "search_text") - NO rawInput
            // 2. Second with full details ("Running: @agentbridge/search_text" + rawInput)
            // We need the rawInput to compute the hash for MCP correlation, so skip the first one
            if (tc.arguments() == null || tc.arguments().isEmpty()) {
                return null;  // Skip - wait for the one with rawInput
            }
            return extractPurpose(tc);
        }
        return update;  // Pass through all other update types unchanged
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
