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
 * Goose ACP client.
 * <p>
 * Command: {@code goose acp} (stdio JSON-RPC, standard ACP).
 * Tool title: {@code agentbridge: read file · /src/main.rs} → {@code read_file}
 * MCP: HTTP or stdio via {@code mcpServers} in {@code session/new}
 * References: uses ACP resource blocks ({@code fs/read_text_file})
 * <p>
 * Goose is an open-source AI agent by the Agentic AI Foundation
 * (<a href="https://github.com/aaif-goose/goose">github.com/aaif-goose/goose</a>).
 * It supports any LLM provider via configuration in {@code ~/.goose/} and the
 * {@code goose configure} command.
 * Install: {@code curl -fsSL https://github.com/aaif-goose/goose/releases/download/stable/download_cli.sh | bash}
 * (installs {@code goose} to {@code ~/.local/bin}) or {@code brew install block-goose-cli}.
 * <p>
 * Authentication is handled by Goose itself (config file / {@code goose configure}),
 * so {@code supportsAuthenticate()} returns {@code false} — like Hermes and Vibe.
 * <p>
 * <b>Tool title handling.</b> Goose exposes MCP tools to the model as
 * {@code {extension}__{tool}}, e.g. {@code agentbridge__read_file}. But the ACP
 * {@code tool_call} title is <em>humanized</em> by Goose: {@code agentbridge__read_file}
 * becomes {@code "agentbridge: read file"}, plus a detail suffix
 * {@code " · <arg-value>"} e.g. {@code "agentbridge: read file · /src/main.rs"}.
 * The raw {@code agentbridge__read_file} name is <em>not</em> present in the title
 * (it lives in {@code _meta.goose.toolCall.toolName} and {@code rawInput}, which the
 * plugin's {@code resolveToolId(String)} does not receive). So {@code resolveToolId}
 * must parse the humanized title: strip the {@code "agentbridge: "} prefix, drop the
 * {@code " · <detail>"} suffix, and map spaces back to underscores.
 */
public final class GooseClient extends AcpClient {

    public static final String AGENT_ID = "goose";
    /**
     * Goose humanizes MCP tool titles as {@code "agentbridge: <tool name>"} (spaces
     * instead of underscores), optionally followed by {@code " · <arg-value>"}.
     */
    private static final String TITLE_PREFIX = "agentbridge: ";
    private static final String KEY_MCP_SERVERS = "mcpServers";

    public GooseClient(Project project) {
        super(project);
    }

    @Override
    public String agentId() {
        return AGENT_ID;
    }

    @Override
    public String displayName() {
        return "Goose";
    }

    @Override
    protected List<String> buildCommand(String cwd, int mcpPort) {
        return List.of(AGENT_ID, "acp");
    }

    @Override
    protected Map<String, String> buildEnvironment(int mcpPort, String cwd) {
        // Goose reads its config from ~/.goose/. No extra env vars needed —
        // the MCP server is injected via session/new.
        return Map.of();
    }

    /**
     * Injects the agentbridge MCP server into {@code session/new} so Goose
     * can call IDE tools without any manual MCP configuration.
     */
    @Override
    protected void customizeNewSession(String cwd, int mcpPort, JsonObject params) {
        // Goose reads the standard mcpServers array in session/new and supports both
        // Stdio and HTTP (StreamableHttp) shapes. SSE is rejected. Prefer the HTTP
        // transport when goose advertises mcpCapabilities.http, else fall back to stdio.
        JsonObject server;
        if (advertisesHttpMcp()) {
            server = buildMcpHttpServer("agentbridge", mcpPort);
        } else {
            server = buildMcpStdioServer("agentbridge", mcpPort);
            if (server == null) {
                throw new IllegalStateException(
                    "Cannot configure Goose MCP server — " + describeMcpStdioServerFailure());
            }
        }
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
     * Goose humanizes MCP tool titles as {@code "agentbridge: <tool name>"} with an
     * optional {@code " · <arg-value>"} detail suffix. Parse that back into the bare
     * tool ID (spaces → underscores).
     */
    @Override
    protected String resolveToolId(String protocolTitle) {
        return resolveToolIdStatic(protocolTitle);
    }

    @Override
    protected boolean isMcpToolTitle(@NotNull String protocolTitle) {
        return hasToolPrefix(protocolTitle);
    }

    /**
     * Parses a Goose humanized MCP tool title into the bare tool ID.
     * <p>
     * If the title does not start with {@code "agentbridge: "}, it is returned
     * unchanged (native tools pass through). Otherwise the prefix is stripped, any
     * {@code " · <detail>"} suffix is dropped, and remaining spaces are mapped to
     * underscores: {@code "agentbridge: read file · /src/main.rs"} → {@code read_file}.
     */
    static String resolveToolIdStatic(String protocolTitle) {
        if (!hasToolPrefix(protocolTitle)) {
            return protocolTitle;
        }
        String rest = protocolTitle.substring(TITLE_PREFIX.length());
        int detailIdx = rest.indexOf(" · ");
        if (detailIdx >= 0) {
            rest = rest.substring(0, detailIdx);
        }
        return rest.replace(' ', '_');
    }

    /**
     * Returns {@code true} if the title carries the agentbridge MCP prefix.
     */
    static boolean hasToolPrefix(String protocolTitle) {
        return protocolTitle.startsWith(TITLE_PREFIX);
    }

    @Override
    protected boolean supportsAuthenticate() {
        return false;
    }
}
