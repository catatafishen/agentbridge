package com.github.catatafishen.agentbridge.acp.model;

/**
 * Client → Agent: set the mode for the current session.
 *
 * @see <a href="https://agentclientprotocol.com/protocol/sessions">ACP Sessions</a>
 */
public record SetModeRequest(
        String sessionId,
        String modeSlug
) {}
