package com.github.catatafishen.agentbridge.client;

/**
 * Thrown when session creation or management fails.
 */
public class ClientSessionException extends Exception {
    public ClientSessionException(String message) {
        super(message);
    }

    public ClientSessionException(String message, Throwable cause) {
        super(message, cause);
    }
}
