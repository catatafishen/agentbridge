package com.github.catatafishen.agentbridge.psi;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;

import java.util.concurrent.CountDownLatch;

/**
 * Shows an interactive dialog when an MCP tool operation runs longer than expected,
 * letting the user decide how long to keep waiting.
 *
 * <p>Any tool with a long-running blocking operation (process waits, network calls,
 * IDE API calls) can call {@link #askForExtension} after its initial timeout expires
 * rather than killing the operation silently.
 *
 * <p>Must be called from a background thread — blocks until the user dismisses the
 * dialog via {@code invokeLater}.
 */
public final class ToolTimeoutDialog {

    /**
     * Sentinel returned when the user wants to cancel the operation immediately.
     */
    public static final int CANCEL = 0;

    /**
     * Sentinel returned when the user wants to wait with no further timeout.
     */
    public static final int INDEFINITE = Integer.MAX_VALUE;

    private static final String[] OPTIONS = {
        "Wait 1 More Minute",
        "Wait 5 More Minutes",
        "Wait Indefinitely",
        "Cancel Now"
    };

    private ToolTimeoutDialog() {
    }

    /**
     * Shows a dialog and blocks the calling thread until the user picks an option.
     *
     * @param project              the project for dialog parenting
     * @param operationDescription a brief human-readable name for what is running
     *                             (e.g. {@code "git rebase"}, {@code "build_project"})
     * @param elapsedSeconds       seconds already elapsed, shown in the dialog message
     * @return {@link #CANCEL} (0) to stop, {@link #INDEFINITE} to wait forever,
     * or a positive number of seconds to keep waiting
     * @throws InterruptedException if the calling thread is interrupted while waiting
     *                              for the user to respond
     */
    public static int askForExtension(Project project, String operationDescription, int elapsedSeconds)
        throws InterruptedException {
        int[] choice = {3}; // default to "Cancel Now" (covers ESC / dialog close)
        CountDownLatch latch = new CountDownLatch(1);

        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                choice[0] = Messages.showDialog(
                    project,
                    operationDescription + " is still running after " + elapsedSeconds + " seconds.\n"
                        + "What would you like to do?",
                    "Operation Still Running: " + operationDescription,
                    OPTIONS,
                    0,
                    Messages.getWarningIcon()
                );
            } finally {
                latch.countDown();
            }
        });

        latch.await();

        return switch (choice[0]) {
            case 0 -> 60;
            case 1 -> 300;
            case 2 -> INDEFINITE;
            default -> CANCEL;
        };
    }
}
