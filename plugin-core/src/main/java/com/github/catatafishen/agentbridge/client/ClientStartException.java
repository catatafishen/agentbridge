package com.github.catatafishen.agentbridge.client;

/**
 * Thrown when an agent process fails to start or initialize.
 */
public class ClientStartException extends Exception {
    public ClientStartException(String message) {
        super(message);
    }

    public ClientStartException(String message, Throwable cause) {
        super(message, cause);
    }
}
