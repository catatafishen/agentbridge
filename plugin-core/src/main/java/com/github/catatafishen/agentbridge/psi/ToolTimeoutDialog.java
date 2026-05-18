package com.github.catatafishen.agentbridge.psi;

import com.github.catatafishen.agentbridge.settings.ChatInputSettings;
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

public final class ToolTimeoutDialog {

    /**
     * User chose to cancel the operation immediately.
     */
    public static final int CANCEL = 0;

    /**
     * User chose to wait indefinitely (no further timeout).
     */
    public static final int INDEFINITE = Integer.MAX_VALUE;

    /**
     * The tool completed before or during the dialog display.
     * The caller should read the result directly from the future.
     */
    public static final int COMPLETED = -1;

    /**
     * Set to {@code true} when the user picks "Don't ask for this session" in the dialog.
     * Resets when the IDE restarts (in-memory only).
     */
    private static final AtomicBoolean suppressedForSession = new AtomicBoolean(false);

    /**
     * Whether the user chose "Don't ask for this session" during a prior timeout dialog.
     */
    public static boolean isSuppressedForSession() {
        return suppressedForSession.get();
    }

    private ToolTimeoutDialog() {
    }

    /**
     * Shows a dialog and blocks until the user picks an option or {@code toolFuture} completes.
     * Uses default extension options (1 minute and 5 minutes).
     *
     * @see #askForExtension(Project, String, int, CompletableFuture, int, int)
     */
    public static int askForExtension(Project project, String operationDescription,
                                      int elapsedSeconds, CompletableFuture<?> toolFuture)
        throws InterruptedException {
        return askForExtension(project, operationDescription, elapsedSeconds, toolFuture, 60, 300);
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
     * @param elapsedSeconds       seconds already elapsed, shown in the dialog message
     * @param toolFuture           the future representing the running tool call
     * @param ext1Seconds          seconds for the first wait-extension button
     * @param ext2Seconds          seconds for the second wait-extension button
     * @return {@link #CANCEL}, {@link #INDEFINITE}, {@link #COMPLETED},
     * or a positive number of seconds to keep waiting
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public static int askForExtension(Project project, String operationDescription,
                                      int elapsedSeconds, CompletableFuture<?> toolFuture,
                                      int ext1Seconds, int ext2Seconds)
        throws InterruptedException {
        if (toolFuture.isDone()) return COMPLETED;

        String[] options = buildOptions(ext1Seconds, ext2Seconds);

        AtomicInteger choiceIndex = new AtomicInteger(3); // default: "Cancel Now" / ESC
        AtomicBoolean completedByFuture = new AtomicBoolean(false);
        AtomicReference<JDialog> dialogRef = new AtomicReference<>();
        CountDownLatch dialogReady = new CountDownLatch(1);
        CountDownLatch dialogDone = new CountDownLatch(1);

        registerFutureWatcher(toolFuture, dialogRef, dialogReady, completedByFuture);
        scheduleDialogOnEdt(project, operationDescription, elapsedSeconds, options,
            dialogRef, dialogReady, dialogDone, choiceIndex);

        dialogDone.await();
        return completedByFuture.get() ? COMPLETED : mapChoice(choiceIndex.get(), ext1Seconds, ext2Seconds);
    }

    private static String[] buildOptions(int ext1Seconds, int ext2Seconds) {
        return new String[]{
            "Wait " + formatDuration(ext1Seconds) + " More",
            "Wait " + formatDuration(ext2Seconds) + " More",
            "Wait Indefinitely",
            "Cancel Now"
        };
    }

    private static String formatDuration(int seconds) {
        if (seconds < 60) return seconds + (seconds == 1 ? " Second" : " Seconds");
        int minutes = seconds / 60;
        return minutes + (minutes == 1 ? " Minute" : " Minutes");
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
                ApplicationManager.getApplication().invokeLater(dialog::dispose, ModalityState.any());
            }
        });
    }

    private static void scheduleDialogOnEdt(Project project, String operationDescription,
                                            int elapsedSeconds, String[] options,
                                            AtomicReference<JDialog> dialogRef,
                                            CountDownLatch dialogReady,
                                            CountDownLatch dialogDone,
                                            AtomicInteger choiceIndex) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                String message = operationDescription + " is still running after " + elapsedSeconds
                    + " seconds.\nWhat would you like to do?";
                JCheckBox suppressSession = new JCheckBox("Don't ask for this session");
                JCheckBox neverAsk = new JCheckBox("Never ask again");
                Frame frame = WindowManager.getInstance().getFrame(project);
                JOptionPane pane = new JOptionPane(
                    new Object[]{message, suppressSession, neverAsk},
                    JOptionPane.WARNING_MESSAGE,
                    JOptionPane.DEFAULT_OPTION, Messages.getWarningIcon(),
                    options, options[0]);
                JDialog dialog = pane.createDialog(frame, "Operation Still Running: " + operationDescription);
                dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                dialogRef.set(dialog);
                dialogReady.countDown(); // unblock the watcher thread

                dialog.setVisible(true); // enters modal event loop; returns when disposed or clicked
                dialog.dispose(); // free native resources; no-op if already disposed by watcher
                choiceIndex.set(parseChoice(pane, options));

                if (neverAsk.isSelected()) {
                    ChatInputSettings.getInstance().setToolTimeoutDialogEnabled(false);
                } else if (suppressSession.isSelected()) {
                    suppressedForSession.set(true);
                }
            } finally {
                dialogDone.countDown();
            }
        });
    }

    /**
     * Reads the selected option from the pane after the dialog closes.
     * Returns 3 ("Cancel Now") if the dialog was disposed without a user click.
     */
    private static int parseChoice(JOptionPane pane, String[] options) {
        Object selected = pane.getValue();
        if (selected == JOptionPane.UNINITIALIZED_VALUE || !(selected instanceof String s)) {
            return 3;
        }
        for (int i = 0; i < options.length; i++) {
            if (options[i].equals(s)) return i;
        }
        return 3;
    }

    private static int mapChoice(int index, int ext1Seconds, int ext2Seconds) {
        return switch (index) {
            case 0 -> ext1Seconds;
            case 1 -> ext2Seconds;
            case 2 -> INDEFINITE;
            default -> CANCEL;
        };
    }
}
