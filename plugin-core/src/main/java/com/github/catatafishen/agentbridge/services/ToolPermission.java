package com.github.catatafishen.agentbridge.services;

/** Per-tool permission mode. */
public enum ToolPermission {
    /** Auto-approve without asking the user. */
    ALLOW,
    /** Show a permission request bubble in the chat and wait for user input. */
    ASK,
    /** Auto-deny with a guidance message telling the agent to use an alternative. */
    DENY
}
