package com.github.catatafishen.agentbridge.client.acp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Mistral Vibe ACP client.
 * <p>
 * Command: {@code vibe-acp}
 * Tool prefix: {@code agentbridge_read_file} → strip {@code agentbridge_}
 * MCP: HTTP via {@code mcpServers} in {@code session/new}
 * References: requires inline (no ACP resource blocks)
 * <p>
 * Mistral Vibe is an open-source AI coding agent by Mistral AI, powered by Devstral models.
 * It supports the Agent Client Protocol (ACP) natively via its {@code vibe-acp} binary.
 * Install: {@code pip install mistral-vibe} or {@code uv tool install mistral-vibe} (Python 3.12+)
 * <p>
 * Authentication is managed by Vibe itself — credentials are stored in {@code ~/.vibe/}
 * and shared across CLI, VS Code, and ACP surfaces.
 *
 * @see <a href="https://docs.mistral.ai/vibe/code/use-vibe-in-other-ides">Vibe ACP docs</a>
 * @see <a href="https://github.com/mistralai/mistral-vibe">Source on GitHub</a>
 */
public final class VibeClient extends AcpClient {

    public static final String AGENT_ID = "vibe";
    /**
     * Vibe names MCP tools as {@code {server_name}_{tool_name}},
     * e.g. {@code agentbridge_read_file}. The prefix to strip is {@code agentbridge_}.
     */
    private static final String TOOL_PREFIX = "agentbridge_";
    private static final String KEY_MCP_SERVERS = "mcpServers";

    public VibeClient(Project project) {
        super(project);
    }

    @Override
    public String agentId() {
        return AGENT_ID;
    }

    @Override
    public String displayName() {
        return "Mistral Vibe";
    }

    @Override
    protected List<String> buildCommand(String cwd, int mcpPort) {
        return List.of("vibe-acp");
    }

    @Override
    protected Map<String, String> buildEnvironment(int mcpPort, String cwd) {
        // Vibe reads its config from ~/.vibe/ and per-project .vibe/ directories.
        // No extra env vars needed — the MCP server is injected via session/new.
        return Map.of();
    }

    /**
     * Injects the agentbridge MCP server into {@code session/new} so Vibe
     * can call IDE tools without any manual MCP configuration.
     */
    @Override
    protected void customizeNewSession(String cwd, int mcpPort, JsonObject params) {
        addMcpServerConfig(mcpPort, params);
    }

    /**
     * Adds the {@code mcpServers} array to session/new params.
     * <p>
     * Vibe supports HTTP MCP servers via its {@code config.toml} or session injection.
     * Shape: {@code {"type": "http", "name": "agentbridge", "url": "..."}}.
     */
    static void addMcpServerConfig(int mcpPort, JsonObject params) {
        JsonObject server = new JsonObject();
        server.addProperty("type", "http");
        server.addProperty("name", "agentbridge");
        // Use 127.0.0.1 explicitly: on some systems "localhost" resolves to IPv6 (::1)
        // while the MCP server binds to IPv4, causing connection failures.
        server.addProperty("url", "http://127.0.0.1:" + mcpPort + "/mcp");
        server.add("headers", new JsonArray());

        JsonArray servers = new JsonArray();
        servers.add(server);
        params.add(KEY_MCP_SERVERS, servers);
    }

    @Override
    protected String loadSession(String cwd, String sessionId)
            throws InterruptedException, ExecutionException, TimeoutException {
        String result = sendLoadSessionRequest("session/resume", cwd, sessionId);
        markSessionHistoryLoadedInternally();
        return result;
    }

    /**
     * Vibe names MCP tools as {@code {server_name}_{tool_name}},
     * e.g. {@code agentbridge_read_file}. Strip the prefix to get the bare tool ID.
     */
    @Override
    protected String resolveToolId(String protocolTitle) {
        return stripToolPrefix(protocolTitle);
    }

    @Override
    protected boolean isMcpToolTitle(@NotNull String protocolTitle) {
        return hasToolPrefix(protocolTitle);
    }

    /**
     * Strips the {@code agentbridge_} prefix from a Vibe MCP tool title.
     */
    static String stripToolPrefix(String protocolTitle) {
        return protocolTitle.replaceFirst("^" + TOOL_PREFIX, "");
    }

    /**
     * Returns {@code true} if the title carries the agentbridge MCP prefix.
     */
    static boolean hasToolPrefix(String protocolTitle) {
        return protocolTitle.startsWith(TOOL_PREFIX);
    }

    @Override
    public boolean requiresInlineReferences() {
        return true;
    }

    @Override
    protected boolean supportsAuthenticate() {
        return false;
    }
}
