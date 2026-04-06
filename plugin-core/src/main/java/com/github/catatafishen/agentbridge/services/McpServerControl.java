package com.github.catatafishen.agentbridge.services;

import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Interface for controlling the MCP HTTP server from plugin-core code.
 * Implemented by {@code McpHttpServer} in the standalone-mcp module, registered
 * as a project-level service so plugin-core can look it up without a compile-time
 * dependency on standalone-mcp.
 */
public interface McpServerControl {

    /**
     * Start the MCP HTTP server on the configured port.
     */
    void start() throws java.io.IOException;

    /**
     * Start the MCP HTTP server on the specified port (saves port to settings first).
     */
    void start(int port) throws java.io.IOException;

    /**
     * Stop the MCP HTTP server.
     */
    void stop();

    /**
     * Whether the MCP HTTP server is currently accepting connections.
     */
    boolean isRunning();

    /**
     * The port the MCP HTTP server is listening on, or 0 if not running.
     */
    int getPort();

    /**
     * Look up the McpServerControl service for a project.
     * Returns null if the standalone-mcp module is not loaded.
     */
    static McpServerControl getInstance(@NotNull Project project) {
        return project.getService(McpServerControl.class);
    }
}
