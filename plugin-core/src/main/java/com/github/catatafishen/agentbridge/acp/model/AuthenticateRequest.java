package com.github.catatafishen.agentbridge.acp.model;

/**
 * Client → Agent: authenticate with a specific method.
 *
 * @see <a href="https://agentclientprotocol.com/protocol/initialization">ACP Authentication</a>
 */
public record AuthenticateRequest(String methodId) {
}
