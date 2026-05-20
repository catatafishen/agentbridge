package com.github.catatafishen.agentbridge.client;

/**
 * Thrown when an agent process fails to start or initialize.
 */
public class AgentStartException extends Exception {
    public AgentStartException(String message) {
        super(message);
    }

    public AgentStartException(String message, Throwable cause) {
        super(message, cause);
    }
}
