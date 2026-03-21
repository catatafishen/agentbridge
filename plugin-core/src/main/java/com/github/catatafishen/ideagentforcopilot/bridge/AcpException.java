package com.github.catatafishen.ideagentforcopilot.bridge;

import com.github.catatafishen.ideagentforcopilot.agent.AgentException;

/**
 * @deprecated Use {@link AgentException}
 */
@Deprecated
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
