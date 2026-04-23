package com.github.catatafishen.agentbridge.acp.client.intercept;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused tests for the pure helpers on {@link AcpToolInterceptor}. The redirect
 * classification and synthetic terminal lifecycle are exercised end-to-end through
 * the {@link ShellCommandSplitter} tests and (eventually) manual ACP runs against
 * Copilot/Junie/Kiro/OpenCode in a sandbox — they require a live {@code Project}
 * and {@code PsiBridgeService} which are out of scope for unit tests.
 */
class AcpToolInterceptorTest {

    @Test
    void isMcpErrorRecognizesErrorPrefix() {
        assertTrue(AcpToolInterceptor.isMcpError("Error: file not found"));
        assertTrue(AcpToolInterceptor.isMcpError("Error (exit 1): nope"));
    }

    @Test
    void isMcpErrorReturnsFalseForSuccess() {
        assertFalse(AcpToolInterceptor.isMcpError("file contents here"));
        assertFalse(AcpToolInterceptor.isMcpError(""));
        assertFalse(AcpToolInterceptor.isMcpError(null));
    }

    @Test
    void isMcpErrorIsCaseSensitive() {
        // MCP convention is uppercase "Error" — lowercase "error" must not be treated as failure
        assertFalse(AcpToolInterceptor.isMcpError("error message"));
    }
}
