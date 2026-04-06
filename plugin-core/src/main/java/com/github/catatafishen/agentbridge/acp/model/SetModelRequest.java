package com.github.catatafishen.agentbridge.acp.model;

/**
 * Client → Agent: set the model for the current session.
 *
 * @see <a href="https://agentclientprotocol.com/protocol/sessions">ACP Sessions</a>
 */
public record SetModelRequest(
        String sessionId,
        String modelId
) {}
