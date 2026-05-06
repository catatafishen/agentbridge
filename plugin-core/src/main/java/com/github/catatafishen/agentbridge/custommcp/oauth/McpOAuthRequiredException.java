package com.github.catatafishen.agentbridge.custommcp.oauth;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Thrown by {@link com.github.catatafishen.agentbridge.custommcp.CustomMcpClient} when an MCP server
 * returns HTTP 401, indicating that OAuth authentication is required before the connection
 * can proceed.
 */
public final class McpOAuthRequiredException extends IOException {

    private final @NotNull String serverUrl;

    public McpOAuthRequiredException(@NotNull String serverUrl) {
        super("HTTP 401 — OAuth authentication required for " + serverUrl);
        this.serverUrl = serverUrl;
    }

    /** The URL of the MCP server that rejected the connection. */
    @NotNull
    public String getServerUrl() {
        return serverUrl;
    }
}
