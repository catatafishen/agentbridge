package com.github.catatafishen.agentbridge.bridge;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Shows permission prompts one at a time, in arrival order.
 * <p>
 * Agents such as OpenCode run the tool calls of one model step in parallel, and each call
 * that needs approval blocks its own thread on its own prompt. Without this queue every such
 * call shows its approval card (and, with the auto-open setting, its editor diff tab) at the
 * same moment: the tabs cover each other, the preview of a later edit to the same file is
 * computed before the earlier edit is applied, and all prompts time out together.
 * <p>
 * A caller takes a {@link Turn} before it shows its prompt and closes it once the prompt is
 * resolved, so the next prompt appears only then and its timeout starts only then. Only
 * calls that actually ask the user take a turn: auto-allowed calls never wait here, and the
 * tool calls themselves still run in parallel once approved.
 * <p>
 * Covered prompt paths: the AgentBridge MCP Ask gate ({@code PsiBridgeService.askUserPermission})
 * and ACP {@code session/request_permission} ({@code AcpClient.requestPermissionFromUser}).
 * Codex native approvals ({@code CodexApprovalHandler}) do not take a turn yet.
 * <p>
 * Thread-safe. Waiters block a background thread (the MCP request worker or the ACP
 * permission worker); never wait for a turn on the EDT.
 */
@Service(Service.Level.PROJECT)
public final class PermissionPromptQueue {

    /**
     * The right to show one permission prompt. {@link #close()} hands the turn to the next
     * waiter; it is idempotent, so callers may close on several exit paths.
     */
    @FunctionalInterface
    public interface Turn extends AutoCloseable {
        @Override
        void close();
    }

    /** A turn that holds nothing, for callers that have no project to queue in. */
    public static final Turn NO_QUEUE = () -> {
        // Nothing was queued, so there is nothing to hand over.
    };

    private final Object lock = new Object();
    private final Deque<CompletableFuture<Turn>> waiters = new ArrayDeque<>();
    private @Nullable Turn current;

    public static @NotNull PermissionPromptQueue getInstance(@NotNull Project project) {
        return project.getService(PermissionPromptQueue.class);
    }

    /**
     * Blocks until it is the caller's turn to show a prompt.
     *
     * @throws InterruptedException if the thread is interrupted while waiting; the caller's
     *                              place in the queue is given up
     */
    public @NotNull Turn awaitTurn() throws InterruptedException {
        return awaitTurn(new CompletableFuture<>());
    }

    /**
     * Blocks until it is the caller's turn to show a prompt, or until {@code abort} completes
     * (normally or exceptionally), whichever happens first. Use the abort future for requests
     * the agent can withdraw while they wait, such as a cancelled ACP turn.
     *
     * @return the turn, or {@code null} when {@code abort} completed first; in that case the
     * caller's place in the queue is given up and nothing needs closing
     * @throws InterruptedException if the thread is interrupted while waiting; the caller's
     *                              place in the queue is given up
     */
    public @Nullable Turn awaitTurn(@NotNull CompletableFuture<?> abort) throws InterruptedException {
        CompletableFuture<Turn> pending = requestTurn();
        try {
            CompletableFuture.anyOf(pending, abort).get();
        } catch (ExecutionException e) {
            // Only abort can complete exceptionally (pending is only ever completed normally
            // or cancelled by abandon), so this is an abort: handled by the check below.
        } catch (InterruptedException e) {
            abandon(pending);
            throw e;
        }
        if (abort.isDone()) {
            abandon(pending);
            return null;
        }
        return pending.join();
    }

    /** Joins the queue; the future completes with the turn once every earlier turn closed. */
    @NotNull CompletableFuture<Turn> requestTurn() {
        CompletableFuture<Turn> pending = new CompletableFuture<>();
        Turn granted = null;
        synchronized (lock) {
            if (current == null) {
                granted = newTurn();
                current = granted;
            } else {
                waiters.addLast(pending);
            }
        }
        if (granted != null) {
            pending.complete(granted);
        }
        return pending;
    }

    /**
     * Gives up a place in the queue. If the turn was granted in the meantime it is closed,
     * so the next waiter is not left stuck behind a caller that stopped waiting.
     */
    void abandon(@NotNull CompletableFuture<Turn> pending) {
        synchronized (lock) {
            waiters.remove(pending);
        }
        if (!pending.cancel(false) && !pending.isCancelled()) {
            pending.join().close();
        }
    }

    /** Whether a turn is currently held. For tests. */
    boolean isBusy() {
        synchronized (lock) {
            return current != null;
        }
    }

    private @NotNull Turn newTurn() {
        return new Turn() {
            private boolean closed;

            @Override
            public void close() {
                synchronized (lock) {
                    if (closed) return;
                    closed = true;
                }
                handOver(this);
            }
        };
    }

    /**
     * Passes the turn from {@code finished} to the oldest waiter that is still waiting.
     * Futures are completed outside the lock so that code depending on them never runs
     * while the queue is locked; a waiter that cancelled in between is skipped.
     */
    private void handOver(@NotNull Turn finished) {
        Turn releasing = finished;
        while (true) {
            CompletableFuture<Turn> next;
            Turn granted;
            synchronized (lock) {
                if (current != releasing) return;
                next = waiters.pollFirst();
                if (next == null) {
                    current = null;
                    return;
                }
                granted = newTurn();
                current = granted;
            }
            if (next.complete(granted)) return;
            // The waiter cancelled before it could be granted: hand over again.
            releasing = granted;
        }
    }
}
