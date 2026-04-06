package com.github.catatafishen.agentbridge.acp.model;

import org.jetbrains.annotations.Nullable;

/**
 * Authentication method advertised by an agent during initialization.
 *
 * @see <a href="https://agentclientprotocol.com/protocol/initialization#agent-capabilities">ACP Auth</a>
 */
public record AuthMethod(
        String id,
        String name,
        @Nullable String description
) {}
