package com.github.catatafishen.agentbridge.psi;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;

import java.awt.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Utility for dispatching operations to the EDT with modal-dialog awareness.
 * <p>
 * Uses {@code ModalityState.defaultModalityState()} to ensure operations run in a
 * write-safe context (required by {@code TransactionGuard} for VFS/PSI changes).
 * <p>
 * {@link #invokeAndWait(Runnable)} includes a timeout so that a modal dialog
 * can never cause an MCP tool to block indefinitely — it will time out and
 * report which dialog is blocking.
 */
public final class EdtUtil {

    private static final int DEFAULT_INVOKE_AND_WAIT_TIMEOUT_SECONDS = 30;
    /**
     * How often to check for blocking modal dialogs during invokeAndWait polling.
     */
    private static final long MODAL_POLL_INTERVAL_MS = 500;
    /**
     * How long a modal must be continuously visible before we abort the wait.
     */
    private static final long MODAL_FAIL_AFTER_MS = 1500;

    private EdtUtil() {
    }

    /**
     * Dispatch a runnable to the EDT using the default (write-safe) modality state.
     */
    public static void invokeLater(Runnable runnable) {
        ApplicationManager.getApplication().invokeLater(runnable, ModalityState.defaultModalityState());
    }

    /**
     * Run a runnable on the EDT and block until it completes, with a timeout.
     * <p>
     * Polls every {@value #MODAL_POLL_INTERVAL_MS}ms. If a modal dialog is detected
     * and remains open for {@value #MODAL_FAIL_AFTER_MS}ms, the wait is aborted
     * immediately with a descriptive error — far sooner than the overall
     * {@value #DEFAULT_INVOKE_AND_WAIT_TIMEOUT_SECONDS}-second backstop.
     * This surfaces the blocking dialog to the agent quickly so it can use
     * {@code interact_with_modal} to respond.
     *
     * @throws RuntimeException if the operation times out, is blocked by a modal, or fails
     */
    public static void invokeAndWait(Runnable runnable) {
        if (ApplicationManager.getApplication().isDispatchThread()) {
            runnable.run();
            return;
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                runnable.run();
                future.complete(null);
            } catch (Exception t) {
                future.completeExceptionally(t);
            }
        }, ModalityState.defaultModalityState());

        pollUntilDone(future);
    }

    /**
     * Polls {@code future} in short intervals, aborting early if a blocking modal dialog
     * is detected. Throws {@link IllegalStateException} on timeout, modal block, or failure.
     */
    private static void pollUntilDone(CompletableFuture<Void> future) {
        long modalFirstSeenMs = 0;
        long deadlineMs = System.currentTimeMillis() + (long) DEFAULT_INVOKE_AND_WAIT_TIMEOUT_SECONDS * 1000;

        while (System.currentTimeMillis() < deadlineMs) {
            try {
                future.get(MODAL_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
                return; // completed successfully
            } catch (TimeoutException ignored) {
                modalFirstSeenMs = checkModalTimeout(modalFirstSeenMs);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException re) throw re;
                if (cause instanceof Error err) throw err;
                throw new IllegalStateException(cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("EDT operation interrupted", e);
            }
        }

        String detail = describeModalBlocker();
        throw new IllegalStateException(
            "EDT operation timed out after " + DEFAULT_INVOKE_AND_WAIT_TIMEOUT_SECONDS + "s." + detail);
    }

    /**
     * Updates and checks the modal-first-seen timestamp.
     * Returns 0 if no modal is present (reset), or throws if the modal has been
     * blocking long enough to give up.
     */
    private static long checkModalTimeout(long modalFirstSeenMs) {
        String modalDetail = describeModalBlocker();
        if (modalDetail.isEmpty()) {
            return 0; // no modal — reset the timer
        }
        if (modalFirstSeenMs == 0) {
            return System.currentTimeMillis(); // modal just appeared — start timer
        }
        if (System.currentTimeMillis() - modalFirstSeenMs >= MODAL_FAIL_AFTER_MS) {
            throw new IllegalStateException(
                "EDT blocked by modal dialog." + modalDetail
                    + " Use the interact_with_modal tool to respond to the dialog.", null);
        }
        return modalFirstSeenMs; // modal present but grace period not expired yet
    }

    /**
     * Build a human-readable description of all visible modal dialogs.
     *
     * @return e.g. {@code " Modal dialog blocking: 'Settings', 'Confirm'"} or empty string if none
     */
    public static String describeModalBlocker() {
        StringBuilder sb = new StringBuilder();
        for (Window window : Window.getWindows()) {
            if (window instanceof Dialog dialog && dialog.isModal() && dialog.isVisible()) {
                if (sb.isEmpty()) {
                    sb.append(" Modal dialog blocking: ");
                } else {
                    sb.append(", ");
                }
                String title = dialog.getTitle();
                sb.append("'").append(title != null && !title.isEmpty() ? title : "(untitled)").append("'");
            }
        }
        return sb.toString();
    }
}
