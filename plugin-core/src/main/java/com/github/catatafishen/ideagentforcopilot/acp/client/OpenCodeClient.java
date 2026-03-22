package com.github.catatafishen.ideagentforcopilot.acp.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;

import java.util.List;

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
    protected void customizeNewSession(String cwd, int mcpPort, JsonObject params) {
        // OpenCode requires mcpServers in session/new with type "http" (not "sse" or "local")
        // and needs an empty "headers" array per its Zod schema validation
        JsonObject server = new JsonObject();
        server.addProperty("name", "agentbridge");
        server.addProperty("type", "http");
        server.addProperty("url", "http://127.0.0.1:" + mcpPort);
        server.add("headers", new JsonArray());
        JsonArray servers = new JsonArray();
        servers.add(server);
        params.add("mcpServers", servers);
    }
}
