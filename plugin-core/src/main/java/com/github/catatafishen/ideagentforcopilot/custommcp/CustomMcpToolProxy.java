package com.github.catatafishen.ideagentforcopilot.custommcp;

import com.github.catatafishen.ideagentforcopilot.services.ToolDefinition;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Proxies a single tool from an external MCP server into the local {@link ToolRegistry}.
 * The proxy tool ID is namespaced with the server prefix to avoid collisions.
 * The server's usage instructions are appended to the tool description so the AI sees them.
 */
public final class CustomMcpToolProxy implements ToolDefinition {

    private final String id;
    private final String originalToolName;
    private final String displayName;
    private final String description;
    @Nullable
    private final JsonObject inputSchema;
    private final CustomMcpClient client;

    public CustomMcpToolProxy(
        @NotNull String serverPrefix,
        @NotNull CustomMcpClient client,
        @NotNull CustomMcpClient.ToolInfo toolInfo,
        @NotNull String serverInstructions
    ) {
        this.originalToolName = toolInfo.name();
        this.id = serverPrefix + "_" + toolInfo.name();
        this.displayName = toolInfo.name();
        this.inputSchema = toolInfo.inputSchema();
        this.client = client;

        String base = toolInfo.description();
        if (!serverInstructions.isBlank()) {
            this.description = base.isBlank() ? serverInstructions : base + "\n\n" + serverInstructions;
        } else {
            this.description = base;
        }
    }

    @Override
    public @NotNull String id() {
        return id;
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EXECUTE;
    }

    @Override
    public @NotNull String displayName() {
        return displayName;
    }

    @Override
    public @NotNull String description() {
        return description;
    }

    @Override
    public @NotNull ToolRegistry.Category category() {
        return ToolRegistry.Category.CUSTOM_MCP;
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return inputSchema;
    }

    @Override
    public boolean hasExecutionHandler() {
        return true;
    }

    @Override
    public boolean isOpenWorld() {
        return true;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        return client.callTool(originalToolName, args);
    }
}
