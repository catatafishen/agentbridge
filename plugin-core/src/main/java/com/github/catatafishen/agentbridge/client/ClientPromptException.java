package com.github.catatafishen.agentbridge.client;

/**
 * Thrown when a prompt request fails.
 */
public class ClientPromptException extends Exception {
    public ClientPromptException(String message) {
        super(message);
    }

    public ClientPromptException(String message, Throwable cause) {
        super(message, cause);
    }
}
