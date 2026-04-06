package com.github.catatafishen.agentbridge.bridge;

/**
 * Transport layer used to communicate with an AI agent.
 */
public enum TransportType {

    /**
     * JSON-RPC 2.0 over stdin/stdout to a locally-installed CLI subprocess (ACP protocol).
     * Used by GitHub Copilot, OpenCode, and similar tool-wrapped agents.
     */
    ACP,

    /**
     * Subprocess calls to the {@code claude} CLI binary in {@code --print} mode with
     * {@code --output-format stream-json}. Uses the Claude subscription stored by the CLI
     * ({@code ~/.claude/.credentials.json}) — no Anthropic API key required.
     * Requires {@code claude} to be installed and logged in ({@code claude auth login}).
     */
    CLAUDE_CLI,

    /**
     * Long-lived subprocess running {@code codex app-server} with bidirectional JSON-RPC 2.0
     * over stdio. Requires {@code codex} to be installed and authenticated ({@code codex login}).
     * Supports streaming text, graceful tool-approval denial, and multi-turn threads.
     */
    CODEX_APP_SERVER
}
