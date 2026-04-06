package com.github.catatafishen.agentbridge.acp.model;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Client → Agent: create a new conversation session.
 *
 * @see <a href="https://agentclientprotocol.com/protocol/sessions">ACP Sessions</a>
 */
public record NewSessionRequest(
        String cwd,
        @Nullable List<McpServerConfig> mcpServers,
        @Nullable JsonObject _meta
) {

    /**
     * MCP server configuration passed to the agent during session creation.
     */
    public record McpServerConfig(
            String name,
            String transport,
            @Nullable List<String> command,
            @Nullable String url,
            @Nullable Map<String, String> env
    ) {
        public static McpServerConfig stdio(String name, List<String> command,
                                            @Nullable Map<String, String> env) {
            return new McpServerConfig(name, "stdio", command, null, env);
        }

        public static McpServerConfig http(String name, String url) {
            return new McpServerConfig(name, "http", null, url, null);
        }

        public static McpServerConfig sse(String name, String url) {
            return new McpServerConfig(name, "sse", null, url, null);
        }
    }
}
