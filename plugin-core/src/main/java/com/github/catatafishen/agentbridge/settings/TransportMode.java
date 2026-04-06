package com.github.catatafishen.agentbridge.settings;

/**
 * Transport mode for the MCP HTTP server.
 *
 * <ul>
 *   <li>{@link #STREAMABLE_HTTP} — POST /mcp with JSON-RPC request/response (default)</li>
 *   <li>{@link #SSE} — GET /sse opens an event stream; POST /message sends requests,
 *       responses arrive via the SSE stream</li>
 * </ul>
 */
public enum TransportMode {
    STREAMABLE_HTTP("Streamable HTTP"),
    SSE("SSE (Server-Sent Events)");

    private final String displayName;

    TransportMode(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
