package com.github.catatafishen.agentbridge.client;

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
 * Tests for the {@link ClientException} hierarchy — constructors and field accessors.
 */
@DisplayName("ClientException")
class ClientExceptionTest {

    // ── Single-arg constructor ───────────────────────────────────────────

    @Nested
    @DisplayName("ClientException(message)")
    class SingleArg {

        @Test
        @DisplayName("message is set")
        void messageIsSet() {
            ClientException ex = new ClientException("boom");
            assertEquals("boom", ex.getMessage());
        }

        @Test
        @DisplayName("cause is null")
        void causeIsNull() {
            ClientException ex = new ClientException("boom");
            assertNull(ex.getCause());
        }

        @Test
        @DisplayName("defaults to recoverable")
        void defaultRecoverable() {
            ClientException ex = new ClientException("boom");
            assertTrue(ex.isRecoverable());
        }

        @Test
        @DisplayName("errorCode defaults to 0")
        void errorCodeZero() {
            ClientException ex = new ClientException("boom");
            assertEquals(0, ex.getErrorCode());
        }

        @Test
        @DisplayName("errorData defaults to null")
        void errorDataNull() {
            ClientException ex = new ClientException("boom");
            assertNull(ex.getErrorData());
        }
    }

    // ── Two-arg constructor (message, cause) ─────────────────────────────

    @Nested
    @DisplayName("ClientException(message, cause)")
    class TwoArg {

        private final RuntimeException cause = new RuntimeException("root");

        @Test
        @DisplayName("message and cause are set")
        void messageAndCauseSet() {
            ClientException ex = new ClientException("wrapper", cause);
            assertEquals("wrapper", ex.getMessage());
            assertSame(cause, ex.getCause());
        }

        @Test
        @DisplayName("null cause is allowed")
        void nullCauseAllowed() {
            ClientException ex = new ClientException("no cause", null);
            assertNull(ex.getCause());
        }

        @Test
        @DisplayName("defaults to recoverable")
        void defaultRecoverable() {
            ClientException ex = new ClientException("wrapper", cause);
            assertTrue(ex.isRecoverable());
        }
    }

    // ── Three-arg constructor (message, cause, recoverable) ──────────────

    @Nested
    @DisplayName("ClientException(message, cause, recoverable)")
    class ThreeArg {

        @Test
        @DisplayName("recoverable=true is stored")
        void recoverableTrue() {
            ClientException ex = new ClientException("oops", null, true);
            assertTrue(ex.isRecoverable());
        }

        @Test
        @DisplayName("recoverable=false is stored")
        void recoverableFalse() {
            ClientException ex = new ClientException("fatal", null, false);
            assertFalse(ex.isRecoverable());
        }

        @Test
        @DisplayName("errorCode still defaults to 0")
        void errorCodeStillZero() {
            ClientException ex = new ClientException("x", null, false);
            assertEquals(0, ex.getErrorCode());
        }
    }

    // ── Full constructor ─────────────────────────────────────────────────

    @Nested
    @DisplayName("ClientException(message, cause, recoverable, errorCode, errorData)")
    class FullConstructor {

        @Test
        @DisplayName("all fields are stored")
        void allFieldsStored() {
            Throwable cause = new IllegalStateException("inner");
            ClientException ex = new ClientException("fail", cause, false, -32600, "{\"detail\":\"bad\"}");

            assertEquals("fail", ex.getMessage());
            assertSame(cause, ex.getCause());
            assertFalse(ex.isRecoverable());
            assertEquals(-32600, ex.getErrorCode());
            assertEquals("{\"detail\":\"bad\"}", ex.getErrorData());
        }

        @Test
        @DisplayName("positive error code")
        void positiveErrorCode() {
            ClientException ex = new ClientException("x", null, true, 42, null);
            assertEquals(42, ex.getErrorCode());
        }

        @Test
        @DisplayName("null errorData is allowed")
        void nullErrorData() {
            ClientException ex = new ClientException("x", null, true, 0, null);
            assertNull(ex.getErrorData());
        }

        @Test
        @DisplayName("empty errorData is stored as-is")
        void emptyErrorData() {
            ClientException ex = new ClientException("x", null, true, 0, "");
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
            ClientException ex = new ClientException("test");
            assertInstanceOf(Exception.class, ex);
        }

        @Test
        @DisplayName("is not a RuntimeException")
        void isNotRuntime() {
            assertFalse(RuntimeException.class.isAssignableFrom(ClientException.class));
        }
    }

    // ── AcpException (removed in 0.8) ────────────────────────────────────
    // The deprecated AcpException subclass was removed; ClientException is the
    // canonical type. No replacement test needed — ClientException is covered
    // by the tests above.
}
