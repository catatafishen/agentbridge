package com.github.catatafishen.agentbridge.services;

import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link InFlightMcpToolRegistry}.
 *
 * <p>Constructs the registry directly (bypassing project-service lookup) since the
 * per-project instance only stores an in-memory map.
 */
class InFlightMcpToolRegistryTest {

    private final InFlightMcpToolRegistry registry = new InFlightMcpToolRegistry(mock(Project.class));

    @Test
    void cancelAll_completesRegisteredFutures_withCancellationException() {
        CompletableFuture<String> a = new CompletableFuture<>();
        CompletableFuture<String> b = new CompletableFuture<>();
        registry.register("a", a);
        registry.register("b", b);

        registry.cancelAll("agent stopped");

        assertCancelledWith(a, "agent stopped");
        assertCancelledWith(b, "agent stopped");
    }

    @Test
    void cancelAll_emptyRegistry_doesNotThrow() {
        assertDoesNotThrow(() -> registry.cancelAll("test"));
    }

    @Test
    void unregister_removesFuture_soCancelAllIsNoOp() {
        CompletableFuture<String> a = new CompletableFuture<>();
        registry.register("a", a);
        registry.unregister("a");

        registry.cancelAll("agent stopped");

        assertFalse(a.isDone(), "Unregistered future must not be completed by cancelAll");
    }

    @Test
    void cancelAll_doesNotOverwriteAlreadyCompletedFutures() throws Exception {
        CompletableFuture<String> a = new CompletableFuture<>();
        a.complete("real-user-answer");
        registry.register("a", a);

        registry.cancelAll("agent stopped");

        assertEquals("real-user-answer", a.get(1, TimeUnit.SECONDS));
    }

    @Test
    void cancelAll_isIdempotent() {
        CompletableFuture<String> a = new CompletableFuture<>();
        registry.register("a", a);

        registry.cancelAll("first");
        assertDoesNotThrow(() -> registry.cancelAll("second"));

        // First cancellation wins.
        assertCancelledWith(a, "first");
    }

    @Test
    void register_afterCancelAll_immediatelyCancelsLateFuture() {
        registry.cancelAll("agent stopped");

        CompletableFuture<String> late = new CompletableFuture<>();
        registry.register("late", late);

        assertTrue(late.isDone(), "Future registered after cancelAll must be immediately completed");
        assertCancelledWith(late, "agent stopped");
    }

    private static void assertCancelledWith(CompletableFuture<String> future, String expectedReason) {
        // CompletableFuture.completeExceptionally(CancellationException) makes get() throw
        // CancellationException (not wrapped in ExecutionException). The thrown instance is a
        // new "get"-message CancellationException whose cause is the original one we passed in.
        CancellationException ce = assertThrows(CancellationException.class,
            () -> future.get(1, TimeUnit.SECONDS),
            "Future should complete exceptionally with CancellationException");
        Throwable cause = ce.getCause();
        String reason = cause != null ? cause.getMessage() : ce.getMessage();
        assertEquals(expectedReason, reason);
    }
}
