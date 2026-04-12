package com.github.catatafishen.agentbridge.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the {@link AgentException} hierarchy — constructors, field accessors,
 * and the deprecated {@link com.github.catatafishen.agentbridge.bridge.AcpException} subclass.
 */
@DisplayName("AgentException")
class AgentExceptionTest {

    // ── Single-arg constructor ───────────────────────────────────────────

    @Nested
    @DisplayName("AgentException(message)")
    class SingleArg {

        @Test
        @DisplayName("message is set")
        void messageIsSet() {
            AgentException ex = new AgentException("boom");
            assertEquals("boom", ex.getMessage());
        }

        @Test
        @DisplayName("cause is null")
        void causeIsNull() {
            AgentException ex = new AgentException("boom");
            assertNull(ex.getCause());
        }

        @Test
        @DisplayName("defaults to recoverable")
        void defaultRecoverable() {
            AgentException ex = new AgentException("boom");
            assertTrue(ex.isRecoverable());
        }

        @Test
        @DisplayName("errorCode defaults to 0")
        void errorCodeZero() {
            AgentException ex = new AgentException("boom");
            assertEquals(0, ex.getErrorCode());
        }

        @Test
        @DisplayName("errorData defaults to null")
        void errorDataNull() {
            AgentException ex = new AgentException("boom");
            assertNull(ex.getErrorData());
        }
    }

    // ── Two-arg constructor (message, cause) ─────────────────────────────

    @Nested
    @DisplayName("AgentException(message, cause)")
    class TwoArg {

        private final RuntimeException cause = new RuntimeException("root");

        @Test
        @DisplayName("message and cause are set")
        void messageAndCauseSet() {
            AgentException ex = new AgentException("wrapper", cause);
            assertEquals("wrapper", ex.getMessage());
            assertSame(cause, ex.getCause());
        }

        @Test
        @DisplayName("null cause is allowed")
        void nullCauseAllowed() {
            AgentException ex = new AgentException("no cause", null);
            assertNull(ex.getCause());
        }

        @Test
        @DisplayName("defaults to recoverable")
        void defaultRecoverable() {
            AgentException ex = new AgentException("wrapper", cause);
            assertTrue(ex.isRecoverable());
        }
    }

    // ── Three-arg constructor (message, cause, recoverable) ──────────────

    @Nested
    @DisplayName("AgentException(message, cause, recoverable)")
    class ThreeArg {

        @Test
        @DisplayName("recoverable=true is stored")
        void recoverableTrue() {
            AgentException ex = new AgentException("oops", null, true);
            assertTrue(ex.isRecoverable());
        }

        @Test
        @DisplayName("recoverable=false is stored")
        void recoverableFalse() {
            AgentException ex = new AgentException("fatal", null, false);
            assertFalse(ex.isRecoverable());
        }

        @Test
        @DisplayName("errorCode still defaults to 0")
        void errorCodeStillZero() {
            AgentException ex = new AgentException("x", null, false);
            assertEquals(0, ex.getErrorCode());
        }
    }

    // ── Full constructor ─────────────────────────────────────────────────

    @Nested
    @DisplayName("AgentException(message, cause, recoverable, errorCode, errorData)")
    class FullConstructor {

        @Test
        @DisplayName("all fields are stored")
        void allFieldsStored() {
            Throwable cause = new IllegalStateException("inner");
            AgentException ex = new AgentException("fail", cause, false, -32600, "{\"detail\":\"bad\"}");

            assertEquals("fail", ex.getMessage());
            assertSame(cause, ex.getCause());
            assertFalse(ex.isRecoverable());
            assertEquals(-32600, ex.getErrorCode());
            assertEquals("{\"detail\":\"bad\"}", ex.getErrorData());
        }

        @Test
        @DisplayName("positive error code")
        void positiveErrorCode() {
            AgentException ex = new AgentException("x", null, true, 42, null);
            assertEquals(42, ex.getErrorCode());
        }

        @Test
        @DisplayName("null errorData is allowed")
        void nullErrorData() {
            AgentException ex = new AgentException("x", null, true, 0, null);
            assertNull(ex.getErrorData());
        }

        @Test
        @DisplayName("empty errorData is stored as-is")
        void emptyErrorData() {
            AgentException ex = new AgentException("x", null, true, 0, "");
            assertEquals("", ex.getErrorData());
        }
    }

    // ── Exception chain ──────────────────────────────────────────────────

    @Nested
    @DisplayName("exception chain")
    class ExceptionChain {

        @Test
        @DisplayName("is a checked Exception")
        void isCheckedException() {
            AgentException ex = new AgentException("test");
            assertInstanceOf(Exception.class, ex);
        }

        @Test
        @DisplayName("is not a RuntimeException")
        void isNotRuntime() {
            assertFalse(RuntimeException.class.isAssignableFrom(AgentException.class));
        }
    }

    // ── AcpException (deprecated subclass) ───────────────────────────────

    @Nested
    @DisplayName("AcpException (deprecated)")
    @SuppressWarnings("deprecation")
    class AcpExceptionTests {

        @Test
        @DisplayName("single-arg constructor defaults to recoverable")
        void singleArgRecoverable() {
            var ex = new com.github.catatafishen.agentbridge.bridge.AcpException("msg");
            assertEquals("msg", ex.getMessage());
            assertTrue(ex.isRecoverable());
            assertEquals(0, ex.getErrorCode());
        }

        @Test
        @DisplayName("two-arg constructor passes cause")
        void twoArgWithCause() {
            var cause = new RuntimeException("root");
            var ex = new com.github.catatafishen.agentbridge.bridge.AcpException("msg", cause);
            assertSame(cause, ex.getCause());
        }

        @Test
        @DisplayName("three-arg constructor honours recoverable flag")
        void threeArgRecoverable() {
            var ex = new com.github.catatafishen.agentbridge.bridge.AcpException("msg", null, false);
            assertFalse(ex.isRecoverable());
        }

        @Test
        @DisplayName("full constructor stores errorCode and errorData")
        void fullConstructor() {
            var ex = new com.github.catatafishen.agentbridge.bridge.AcpException(
                "msg", null, false, -32601, "data");
            assertEquals(-32601, ex.getErrorCode());
            assertEquals("data", ex.getErrorData());
        }

        @Test
        @DisplayName("is an AgentException")
        void isAgentException() {
            var ex = new com.github.catatafishen.agentbridge.bridge.AcpException("x");
            assertInstanceOf(AgentException.class, ex);
        }
    }
}
