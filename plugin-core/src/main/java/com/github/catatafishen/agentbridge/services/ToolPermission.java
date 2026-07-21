package com.github.catatafishen.agentbridge.services;

import org.jetbrains.annotations.NotNull;

/** Per-tool permission mode. Ordered by severity: {@link #ALLOW} &lt; {@link #ASK} &lt; {@link #DENY}. */
public enum ToolPermission {
    /** Auto-approve without asking the user. */
    ALLOW,
    /** Show a permission request bubble in the chat and wait for user input. */
    ASK,
    /** Auto-deny with a guidance message telling the agent to use an alternative. */
    DENY;

    /**
     * Returns the more restrictive of this and {@code other} (higher severity wins).
     * Used to escalate a tool's permission when a path falls outside the project.
     */
    public @NotNull ToolPermission stricterOf(@NotNull ToolPermission other) {
        return this.ordinal() >= other.ordinal() ? this : other;
    }
}
