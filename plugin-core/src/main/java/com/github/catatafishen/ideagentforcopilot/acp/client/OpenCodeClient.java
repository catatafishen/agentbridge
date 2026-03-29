package com.github.catatafishen.ideagentforcopilot.acp.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * OpenCode ACP client.
 * <p>
 * Command: {@code opencode acp}
 * Tool prefix: {@code agentbridge_read_file} → strip {@code agentbridge_}
 * MCP: HTTP via {@code mcpServers} in {@code session/new}
 * References: requires inline (no ACP resource blocks)
 */
public final class OpenCodeClient extends AcpClient {

    private static final String AGENT_ID = "opencode";

    private static final String KEY_RAW_INPUT = "rawInput";
    private static final List<String> NATIVE_TOOLS_TO_DENY = List.of(
        "grep", "glob", "ls", "read", "write", "edit", "patch", "bash"
    );

    public OpenCodeClient(Project project) {
        super(project);
    }

    @Override
    public String agentId() {
        return AGENT_ID;
    }

    @Override
    public String displayName() {
        return "OpenCode";
    }

    @Override
    protected List<String> buildCommand(String cwd, int mcpPort) {
        return List.of(AGENT_ID, "acp");
    }

    @Override
    protected Map<String, String> buildEnvironment(int mcpPort, String cwd) {
        // Inject OPENCODE_CONFIG_CONTENT to deny native tools so the model is forced
        // to use agentbridge MCP tools. MCP server registration is handled separately
        // via customizeNewSession(), so only the permission block is needed here.
        JsonObject permission = new JsonObject();
        for (String tool : NATIVE_TOOLS_TO_DENY) {
            permission.addProperty(tool, "deny");
        }
        JsonObject config = new JsonObject();
        config.add("permission", permission);
        return Map.of("OPENCODE_CONFIG_CONTENT", new com.google.gson.Gson().toJson(config));
    }

    @Override
    protected String extractSubAgentType(@NotNull JsonObject params, @NotNull String resolvedTitle,
                                         @Nullable JsonObject argumentsObj) {
        // OpenCode sends tool_call with empty rawInput for all tool calls, then fills the actual
        // arguments in the follow-up tool_call_update/in_progress event.
        // The "task" title always means a sub-agent invocation.
        if ("task".equals(resolvedTitle)) {
            // Prefer subagent_type from rawInput when already populated (tool_call_update path)
            JsonObject raw = params.has(KEY_RAW_INPUT) && params.get(KEY_RAW_INPUT).isJsonObject()
                ? params.getAsJsonObject(KEY_RAW_INPUT) : null;
            if (raw != null && raw.has("subagent_type")) {
                return raw.get("subagent_type").getAsString();
            }
            return "general";
        }
        return super.extractSubAgentType(params, resolvedTitle, argumentsObj);
    }

    @Override
    @Nullable
    protected JsonObject parseToolCallArguments(@NotNull JsonObject params) {
        // OpenCode puts tool call arguments in "rawInput" instead of "arguments"
        if (params.has(KEY_RAW_INPUT) && params.get(KEY_RAW_INPUT).isJsonObject()) {
            JsonObject raw = params.getAsJsonObject(KEY_RAW_INPUT);
            if (!raw.entrySet().isEmpty()) {
                return raw;
            }
        }
        return super.parseToolCallArguments(params);
    }

    @Override
    protected String resolveToolId(String protocolTitle) {
        return protocolTitle.replaceFirst("^agentbridge_", "");
    }

    @Override
    protected boolean isMcpToolTitle(@org.jetbrains.annotations.NotNull String protocolTitle) {
        return protocolTitle.startsWith("agentbridge_");
    }

    @Override
    public boolean requiresInlineReferences() {
        return true;
    }

    @Override
    protected boolean supportsAuthenticate() {
        // OpenCode returns -32603 "Authentication not implemented" — skip the call entirely.
        return false;
    }

    @Override
    protected String loadSession(String cwd, String sessionId) throws Exception {
        // OpenCode uses session/resume (not session/load per ACP spec) and does not
        // advertise the loadSession capability. Skip the capability check and use
        // the OpenCode-specific RPC method name.
        String result = sendLoadSessionRequest("session/resume", cwd, sessionId);
        // OpenCode's session/resume restores conversation history from its SQLite database
        // internally — it does not replay history via session/update notifications.
        // Mark as loaded to prevent the injection fallback.
        markSessionHistoryLoadedInternally();
        return result;
    }

    @Override
    protected void customizeNewSession(String cwd, int mcpPort, JsonObject params) {
        // OpenCode requires mcpServers in session/new with type "http" (not "sse" or "local")
        // and needs an empty "headers" array per its Zod schema validation
        JsonObject server = new JsonObject();
        server.addProperty("name", "agentbridge");
        server.addProperty("type", "http");
        server.addProperty("url", "http://127.0.0.1:" + mcpPort + "/mcp");
        server.add("headers", new JsonArray());
        JsonArray servers = new JsonArray();
        servers.add(server);
        params.add("mcpServers", servers);
    }
}
