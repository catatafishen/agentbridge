package com.github.catatafishen.agentbridge.custommcp;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;

/**
 * Abstraction over the MCP client's tool invocation. Enables testing
 * {@link CustomMcpToolProxy} without requiring a real HTTP connection.
 */
@FunctionalInterface
public interface McpToolCaller {
    @NotNull String callTool(@NotNull String toolName, @NotNull JsonObject args);
}
