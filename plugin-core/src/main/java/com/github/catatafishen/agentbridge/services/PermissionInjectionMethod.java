package com.github.catatafishen.agentbridge.services;

/**
 * How tool permission configuration is injected into the agent process at startup.
 * This allows the agent itself to enforce ALLOW/ASK/DENY per tool, giving it
 * awareness of permission requirements before calling tools.
 */
public enum PermissionInjectionMethod {

    /**
     * Inject {@code --allow-tool "name"} and {@code --deny-tool "name"} CLI flags.
     * Tools not explicitly listed default to the agent's standard behavior (typically ASK).
     * Used by Copilot CLI.
     */
    CLI_FLAGS,

    /**
     * Merge a {@code "permission": {"tool": "allow|ask|deny"}} block into the
     * JSON config that is passed to the agent (via env var or config file).
     * Used by OpenCode.
     */
    CONFIG_JSON,

    /**
     * No permission injection — permissions are handled entirely by the plugin.
     */
    NONE
}
