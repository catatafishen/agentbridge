package com.github.catatafishen.agentbridge.agent;

/**
 * Thrown when session creation or management fails.
 */
public class AgentSessionException extends Exception {
    public AgentSessionException(String message) {
        super(message);
    }

    public AgentSessionException(String message, Throwable cause) {
        super(message, cause);
    }
}
