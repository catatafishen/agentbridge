package com.github.catatafishen.agentbridge.bridge;

import com.github.catatafishen.agentbridge.agent.AgentException;

/**
 * @deprecated Use {@link AgentException}
 */
@Deprecated(since = "0.7", forRemoval = true)
public class AcpException extends AgentException {
    public AcpException(String message) {
        super(message, null, true, 0, null);
    }

    public AcpException(String message, Throwable cause) {
        super(message, cause, true, 0, null);
    }

    public AcpException(String message, Throwable cause, boolean recoverable) {
        super(message, cause, recoverable, 0, null);
    }

    public AcpException(String message, Throwable cause, boolean recoverable, int errorCode, String errorData) {
        super(message, cause, recoverable, errorCode, errorData);
    }
}
