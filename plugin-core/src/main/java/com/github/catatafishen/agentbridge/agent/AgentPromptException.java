package com.github.catatafishen.agentbridge.agent;

/**
 * Thrown when a prompt request fails.
 */
public class AgentPromptException extends Exception {
    public AgentPromptException(String message) {
        super(message);
    }

    public AgentPromptException(String message, Throwable cause) {
        super(message, cause);
    }
}
