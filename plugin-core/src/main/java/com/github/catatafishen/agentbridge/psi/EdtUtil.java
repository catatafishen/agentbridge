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
     * If the operation does not complete within {@value #DEFAULT_INVOKE_AND_WAIT_TIMEOUT_SECONDS}
     * seconds (e.g. because a modal dialog is open), throws a {@link RuntimeException}
     * describing the blocking modal dialog(s).
     *
     * @throws RuntimeException if the operation times out or fails
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

        try {
            future.get(DEFAULT_INVOKE_AND_WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            String detail = describeModalBlocker();
            throw new IllegalStateException(
                "EDT operation timed out after " + DEFAULT_INVOKE_AND_WAIT_TIMEOUT_SECONDS + "s." + detail, e);
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
