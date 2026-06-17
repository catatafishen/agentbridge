package com.github.catatafishen.agentbridge.services;

import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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

    @Test
    void cancelInFlight_completesRegisteredFutures_withCancellationException() {
        CompletableFuture<String> a = new CompletableFuture<>();
        registry.register("a", a);

        registry.cancelInFlight("stopped by user");

        assertCancelledWith(a, "stopped by user");
    }

    @Test
    void cancelInFlight_doesNotLatchClosed_soLaterRegistrationsProceed() {
        registry.cancelInFlight("stopped by user");

        // Unlike cancelAll, a transient turn-cancel must NOT auto-cancel a later registration —
        // the next prompt's tool calls have to work without a reconnect.
        CompletableFuture<String> later = new CompletableFuture<>();
        registry.register("later", later);

        assertFalse(later.isDone(), "cancelInFlight must not latch the registry closed");
    }

    @Test
    void cancelInFlight_emptyRegistry_doesNotThrow() {
        assertDoesNotThrow(() -> registry.cancelInFlight("test"));
    }

    @Test
    void cancelInFlight_interruptsRegisteredWorker() throws Exception {
        assertWorkerInterruptedBy(() -> registry.cancelInFlight("stopped by user"));
    }

    @Test
    void cancelAll_interruptsRegisteredWorker() throws Exception {
        assertWorkerInterruptedBy(() -> registry.cancelAll("agent stopped"));
    }

    @Test
    void registerWorker_afterCancelAll_immediatelyInterrupts() {
        registry.cancelAll("agent stopped");
        registry.registerWorker(Thread.currentThread());
        // Thread.interrupted() also clears the flag so it does not leak into other tests.
        assertTrue(Thread.interrupted(), "worker registered after cancelAll must be interrupted");
    }

    @Test
    void reopen_afterCancelAll_allowsLaterFutureToProceed() {
        registry.cancelAll("agent stopped");
        registry.reopen();

        CompletableFuture<String> later = new CompletableFuture<>();
        registry.register("later", later);

        assertFalse(later.isDone(),
            "After reopen, a future registered for a new turn must not be auto-cancelled");
    }

    @Test
    void reopen_afterCancelAll_allowsLaterWorkerToRunUninterrupted() {
        registry.cancelAll("agent stopped");
        registry.reopen();

        registry.registerWorker(Thread.currentThread());

        assertFalse(Thread.interrupted(),
            "After reopen, a worker registered for a new turn must not be interrupted");
    }

    @Test
    void reopen_whenAlreadyOpen_isNoOp() {
        assertDoesNotThrow(registry::reopen);

        CompletableFuture<String> later = new CompletableFuture<>();
        registry.register("later", later);

        assertFalse(later.isDone(), "reopen on an already-open registry must not affect registrations");
    }

    @Test
    void registerWorker_afterCancelInFlight_doesNotInterrupt() {
        registry.cancelInFlight("stopped by user");
        registry.registerWorker(Thread.currentThread());
        assertFalse(Thread.interrupted(),
            "cancelInFlight must not latch — a worker registered afterward runs normally");
    }

    /**
     * Spawns a worker that registers itself and blocks, then runs {@code cancelAction} and
     * asserts the worker was interrupted and unblocked.
     */
    private void assertWorkerInterruptedBy(Runnable cancelAction) throws InterruptedException {
        CountDownLatch registered = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        // Released only by an interrupt — the worker blocks here until the cancel interrupts it.
        CountDownLatch blockUntilInterrupted = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean(false);
        Thread worker = new Thread(() -> {
            registry.registerWorker(Thread.currentThread());
            registered.countDown();
            try {
                blockUntilInterrupted.await();
            } catch (InterruptedException e) {
                interrupted.set(true);
            } finally {
                registry.unregisterWorker(Thread.currentThread());
                finished.countDown();
            }
        }, "test-tool-worker");
        worker.start();
        assertTrue(registered.await(2, TimeUnit.SECONDS), "worker did not register in time");

        cancelAction.run();

        assertTrue(finished.await(2, TimeUnit.SECONDS), "worker did not unblock after cancel");
        assertTrue(interrupted.get(), "registered worker thread must be interrupted by the cancel");
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
