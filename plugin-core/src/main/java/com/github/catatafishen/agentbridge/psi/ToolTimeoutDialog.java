package com.github.catatafishen.agentbridge.psi;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.WindowManager;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shows an interactive dialog when an MCP tool operation runs longer than expected,
 * letting the user decide how long to keep waiting.
 *
 * <p>If the monitored future completes while the dialog is visible, the dialog
 * is dismissed automatically and {@link #COMPLETED} is returned — the user does
 * not need to interact at all. This prevents the dialog from blocking an
 * already-finished operation when the user is away from the keyboard.
 *
 * <p>Must be called from a background thread — blocks until the user dismisses the
 * dialog or the future completes.
 */
public final class ToolTimeoutDialog {

    /** User chose to cancel the operation immediately. */
    public static final int CANCEL = 0;

    /** User chose to wait indefinitely (no further timeout). */
    public static final int INDEFINITE = Integer.MAX_VALUE;

    /**
     * The tool completed before or during the dialog display.
     * The caller should read the result directly from the future.
     */
    public static final int COMPLETED = -1;

    static final String[] OPTIONS = {
        "Wait 1 More Minute",
        "Wait 5 More Minutes",
        "Wait Indefinitely",
        "Cancel Now"
    };

    private ToolTimeoutDialog() {
    }

    /**
     * Shows a dialog and blocks until the user picks an option or {@code toolFuture} completes.
     *
     * <p>If {@code toolFuture} is already done when this method is called, returns
     * {@link #COMPLETED} immediately without showing the dialog. If it completes
     * while the dialog is visible, the dialog is auto-dismissed and {@link #COMPLETED}
     * is returned.
     *
     * @param project              the project for dialog parenting
     * @param operationDescription a brief human-readable name for what is running
     *                             (e.g. {@code "git rebase"}, {@code "build_project"})
     * @param elapsedSeconds       seconds already elapsed, shown in the dialog message
     * @param toolFuture           the future representing the running tool call, used to
     *                             auto-dismiss the dialog when the tool finishes
     * @return {@link #CANCEL} to stop, {@link #INDEFINITE} to wait forever,
     * {@link #COMPLETED} if the tool finished while the dialog was up,
     * or a positive number of seconds to keep waiting
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public static int askForExtension(Project project, String operationDescription,
                                      int elapsedSeconds, CompletableFuture<?> toolFuture)
        throws InterruptedException {
        if (toolFuture.isDone()) return COMPLETED;

        AtomicInteger choiceIndex = new AtomicInteger(3); // default: "Cancel Now" / ESC
        AtomicBoolean completedByFuture = new AtomicBoolean(false);
        AtomicReference<JDialog> dialogRef = new AtomicReference<>();
        CountDownLatch dialogReady = new CountDownLatch(1);
        CountDownLatch dialogDone = new CountDownLatch(1);

        registerFutureWatcher(toolFuture, dialogRef, dialogReady, completedByFuture);
        scheduleDialogOnEdt(project, operationDescription, elapsedSeconds, dialogRef, dialogReady, dialogDone, choiceIndex);

        dialogDone.await();
        return completedByFuture.get() ? COMPLETED : mapChoice(choiceIndex.get());
    }

    /**
     * Registers a {@code whenComplete} callback on {@code toolFuture} that disposes the dialog
     * (via {@code ModalityState.any()}) as soon as the tool finishes.
     */
    private static void registerFutureWatcher(CompletableFuture<?> toolFuture,
                                              AtomicReference<JDialog> dialogRef,
                                              CountDownLatch dialogReady,
                                              AtomicBoolean completedByFuture) {
        toolFuture.whenComplete((result, ex) -> {
            try {
                // Wait for the dialog to be set before trying to close it.
                if (!dialogReady.await(5, TimeUnit.SECONDS)) {
                    // EDT may still be pending (e.g., future completed just before invokeLater ran).
                    // If the tool is actually done, flag it and wait longer for the dialog to appear.
                    if (!toolFuture.isDone()) return;
                    completedByFuture.set(true);
                    if (!dialogReady.await(25, TimeUnit.SECONDS)) return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            JDialog dialog = dialogRef.get();
            if (dialog != null && dialog.isDisplayable()) {
                completedByFuture.set(true);
                // ModalityState.any() lets this fire even inside the modal event loop.
                ApplicationManager.getApplication().invokeLater(dialog::dispose, ModalityState.any());
            }
        });
    }

    /** Schedules dialog creation and display on the EDT via {@code invokeLater}. */
    private static void scheduleDialogOnEdt(Project project, String operationDescription,
                                            int elapsedSeconds,
                                            AtomicReference<JDialog> dialogRef,
                                            CountDownLatch dialogReady,
                                            CountDownLatch dialogDone,
                                            AtomicInteger choiceIndex) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                String message = operationDescription + " is still running after " + elapsedSeconds
                    + " seconds.\nWhat would you like to do?";
                Frame frame = WindowManager.getInstance().getFrame(project);
                JOptionPane pane = new JOptionPane(
                    message, JOptionPane.WARNING_MESSAGE,
                    JOptionPane.DEFAULT_OPTION, Messages.getWarningIcon(),
                    OPTIONS, OPTIONS[0]);
                JDialog dialog = pane.createDialog(frame, "Operation Still Running: " + operationDescription);
                dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                dialogRef.set(dialog);
                dialogReady.countDown(); // unblock the watcher thread

                dialog.setVisible(true); // enters modal event loop; returns when disposed or clicked
                dialog.dispose(); // free native resources; no-op if already disposed by watcher
                choiceIndex.set(parseChoice(pane));
            } finally {
                dialogDone.countDown();
            }
        });
    }

    /**
     * Reads the selected option from the pane after the dialog closes.
     * Returns 3 ("Cancel Now") if the dialog was disposed without a user click.
     */
    private static int parseChoice(JOptionPane pane) {
        Object selected = pane.getValue();
        if (selected == JOptionPane.UNINITIALIZED_VALUE || !(selected instanceof String s)) {
            return 3;
        }
        for (int i = 0; i < OPTIONS.length; i++) {
            if (OPTIONS[i].equals(s)) return i;
        }
        return 3;
    }

    private static int mapChoice(int index) {
        return switch (index) {
            case 0 -> 60;
            case 1 -> 300;
            case 2 -> INDEFINITE;
            default -> CANCEL;
        };
    }
}
