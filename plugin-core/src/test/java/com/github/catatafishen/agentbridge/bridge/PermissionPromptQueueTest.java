package com.github.catatafishen.agentbridge.bridge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link PermissionPromptQueue}: permission prompts from parallel tool calls must be
 * shown one at a time, in arrival order, and a request that stops waiting must never leave the
 * requests behind it stuck.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class PermissionPromptQueueTest {

    private final PermissionPromptQueue queue = new PermissionPromptQueue();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    @DisplayName("first request gets the turn immediately")
    void firstRequestIsGrantedImmediately() {
        CompletableFuture<PermissionPromptQueue.Turn> first = queue.requestTurn();

        assertTrue(first.isDone());
        assertTrue(queue.isBusy());
    }

    @Test
    @DisplayName("second prompt waits until the first prompt is resolved")
    void secondRequestWaitsForFirstToClose() {
        CompletableFuture<PermissionPromptQueue.Turn> first = queue.requestTurn();
        CompletableFuture<PermissionPromptQueue.Turn> second = queue.requestTurn();

        assertFalse(second.isDone());
        first.join().close();
        assertTrue(second.isDone());
    }

    @Test
    @DisplayName("turns are handed out in arrival order")
    void turnsAreGrantedInArrivalOrder() {
        CompletableFuture<PermissionPromptQueue.Turn> first = queue.requestTurn();
        CompletableFuture<PermissionPromptQueue.Turn> second = queue.requestTurn();
        CompletableFuture<PermissionPromptQueue.Turn> third = queue.requestTurn();

        first.join().close();
        assertTrue(second.isDone());
        assertFalse(third.isDone());

        second.join().close();
        assertTrue(third.isDone());
    }

    @Test
    @DisplayName("closing a turn twice does not release the next caller's turn")
    void closeIsIdempotent() {
        CompletableFuture<PermissionPromptQueue.Turn> first = queue.requestTurn();
        CompletableFuture<PermissionPromptQueue.Turn> second = queue.requestTurn();
        CompletableFuture<PermissionPromptQueue.Turn> third = queue.requestTurn();

        first.join().close();
        first.join().close();

        assertTrue(second.isDone());
        assertFalse(third.isDone());
    }

    @Test
    @DisplayName("queue is free again once the last turn closes")
    void queueIsFreeAfterLastTurnCloses() {
        queue.requestTurn().join().close();

        assertFalse(queue.isBusy());
        assertTrue(queue.requestTurn().isDone());
    }

    @Test
    @DisplayName("an abandoned waiter is skipped and the next waiter gets the turn")
    void abandonedWaiterIsSkipped() {
        CompletableFuture<PermissionPromptQueue.Turn> first = queue.requestTurn();
        CompletableFuture<PermissionPromptQueue.Turn> abandoned = queue.requestTurn();
        CompletableFuture<PermissionPromptQueue.Turn> third = queue.requestTurn();

        queue.abandon(abandoned);
        first.join().close();

        assertTrue(abandoned.isCancelled());
        assertTrue(third.isDone());
    }

    @Test
    @DisplayName("abandoning a request that was already granted releases its turn")
    void abandoningAGrantedTurnReleasesIt() {
        CompletableFuture<PermissionPromptQueue.Turn> first = queue.requestTurn();
        CompletableFuture<PermissionPromptQueue.Turn> second = queue.requestTurn();

        queue.abandon(first);

        assertTrue(second.isDone());
    }

    @Test
    @DisplayName("awaitTurn returns at once when no prompt is showing")
    void awaitTurnReturnsImmediatelyWhenFree() throws Exception {
        PermissionPromptQueue.Turn turn = queue.awaitTurn();

        assertNotNull(turn);
        turn.close();
        assertFalse(queue.isBusy());
    }

    @Test
    @DisplayName("awaitTurn blocks until the earlier prompt is resolved")
    void awaitTurnBlocksUntilEarlierTurnCloses() throws Exception {
        PermissionPromptQueue.Turn first = queue.awaitTurn();
        Callable<PermissionPromptQueue.Turn> awaitTurn = queue::awaitTurn;
        Future<PermissionPromptQueue.Turn> waiter = executor.submit(awaitTurn);

        assertThrows(TimeoutException.class,
            () -> waiter.get(200, TimeUnit.MILLISECONDS));
        first.close();

        assertNotNull(waiter.get(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("a request cancelled while queued returns no turn and frees its place")
    void abortWhileQueuedReturnsNullAndFreesPlace() throws Exception {
        PermissionPromptQueue.Turn first = queue.awaitTurn();
        CompletableFuture<Object> abort = new CompletableFuture<>();
        Future<PermissionPromptQueue.Turn> cancelled = executor.submit(() -> queue.awaitTurn(abort));
        CompletableFuture<PermissionPromptQueue.Turn> next = queue.requestTurn();

        abort.complete(null);
        assertNull(cancelled.get(5, TimeUnit.SECONDS));

        first.close();
        // The cancelled request may have joined after `next`; either way the turn reaches
        // `next` and is not held by the request that stopped waiting.
        assertTrue(next.isDone());
    }

    @Test
    @DisplayName("an abort that fails exceptionally also withdraws the request")
    void exceptionalAbortReturnsNull() throws Exception {
        PermissionPromptQueue.Turn first = queue.awaitTurn();
        CompletableFuture<Object> abort = new CompletableFuture<>();
        abort.completeExceptionally(new IllegalStateException("client stopped"));

        assertNull(queue.awaitTurn(abort));
        first.close();
        assertFalse(queue.isBusy());
    }

    @Test
    @DisplayName("an abort that is already complete wins even when the queue is free")
    void alreadyCompletedAbortReturnsNullWhenFree() throws Exception {
        assertNull(queue.awaitTurn(CompletableFuture.completedFuture(null)));
        assertFalse(queue.isBusy());
    }

    @Test
    @DisplayName("an interrupted waiter gives up its place")
    void interruptedWaiterGivesUpItsPlace() throws Exception {
        PermissionPromptQueue.Turn first = queue.awaitTurn();
        CompletableFuture<Throwable> failure = new CompletableFuture<>();
        Thread waiter = new Thread(() -> {
            try {
                queue.awaitTurn();
                failure.complete(null);
            } catch (Exception e) {
                failure.complete(e);
            }
        });
        waiter.start();
        waiter.interrupt();
        waiter.join(5_000);

        assertInstanceOf(InterruptedException.class, failure.get(5, TimeUnit.SECONDS));
        first.close();
        assertFalse(queue.isBusy());
    }

    @Test
    @DisplayName("the no-queue turn can be closed any number of times")
    void noQueueTurnIsHarmless() {
        assertDoesNotThrow(() -> {
            PermissionPromptQueue.NO_QUEUE.close();
            PermissionPromptQueue.NO_QUEUE.close();
        });
    }
}
