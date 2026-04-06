package com.github.catatafishen.agentbridge.services;

/**
 * How MCP server configuration is injected into the agent process.
 */
public enum McpInjectionMethod {

    /**
     * Write a JSON config file and pass it via {@code --additional-mcp-config @path}.
     * The config template is resolved with placeholders and written to a temp file.
     */
    CONFIG_FLAG,

    /**
     * Set an environment variable whose value is the resolved JSON config template.
     * Used by agents like OpenCode that read config from env vars.
     */
    ENV_VAR,

    /**
     * Write a JSON config file named {@code mcp.json} inside a temporary directory
     * and pass that directory via {@code --mcp-location <dir>}.
     * Used by agents like Junie that discover MCP configs from a folder.
     */
    MCP_LOCATION_FLAG,

    /**
     * Inject via MCP servers array in {@code session/new} request.
     * Used by agents that expect MCP server definitions in the session creation call.
     */
    SESSION_NEW,

    /**
     * No MCP injection — the agent either doesn't support MCP or handles it externally.
     */
    NONE
}
