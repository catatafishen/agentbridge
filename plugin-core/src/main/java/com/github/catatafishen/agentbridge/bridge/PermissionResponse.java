package com.github.catatafishen.agentbridge.bridge;

/**
 * Four-way response to a tool permission ASK prompt.
 */
public enum PermissionResponse {
    /**
     * Deny the tool call.
     */
    DENY,
    /**
     * Allow this single invocation only.
     */
    ALLOW_ONCE,
    /**
     * Allow this tool for the remainder of the session.
     */
    ALLOW_SESSION,
    /**
     * Allow this tool permanently (persisted across restarts).
     */
    ALLOW_ALWAYS
}
