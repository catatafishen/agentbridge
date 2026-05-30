package com.github.catatafishen.agentbridge.psi.tools;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.PsiBridgeService;
import com.github.catatafishen.agentbridge.services.AgentTabTracker;
import com.intellij.execution.RunContentExecutor;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.BaseProcessHandler;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessListener;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Shared utility for running processes in the IDE Run panel with output capture and timeout.
 *
 * <p>Used by {@link Tool} subclasses (via {@code executeInRunPanel}) and by
 * {@link com.github.catatafishen.agentbridge.services.hooks.HookExecutor} when
 * {@code showInRunPanel: true} is configured on a hook entry.
 *
 * <p>The chat-focus heuristic (don't steal keyboard focus when the chat window is active)
 * is applied consistently in both callers because the check is performed inside
 * {@link EdtUtil#invokeLater}, at the moment the Run panel tab is actually created.
 */
public final class RunPanelExecutor {

    private static final Logger LOG = Logger.getInstance(RunPanelExecutor.class);

    /**
     * Grace period after OS process exit before force-completing the exit future.
     * Gives reader threads time to drain remaining buffered output before we
     * bypass the normal {@code processTerminated} path.
     */
    static final long READER_GRACE_PERIOD_MS = 2000;

    private RunPanelExecutor() {
    }

    /**
     * Result of a process run via the IDE Run panel.
     *
     * @param exitCode exit code of the process, or {@code -1} on timeout
     * @param output   combined stdout/stderr text collected by the process listener
     * @param timedOut {@code true} if the process was killed due to the timeout
     */
    public record RunResult(int exitCode, String output, boolean timedOut) {
    }

    /**
     * Show an already-created {@link Process} in the IDE Run panel, collect its output,
     * and block until it exits or the timeout elapses.
     *
     * <p>The caller is responsible for writing stdin to {@code process.getOutputStream()}
     * (typically in a separate thread before calling this method) because
     * {@link OSProcessHandler} does not provide direct stdin injection.
     *
     * @param project     project context for the Run panel
     * @param process     the already-started process (stdin may still be open)
     * @param commandLine human-readable command line string shown in the Run panel header
     * @param title       tab title in the Run tool window
     * @param timeoutSec  maximum seconds to wait before force-killing the process
     * @return a {@link RunResult} describing the outcome
     * @throws Exception if the process handler cannot be set up (not thrown for non-zero exits)
     */
    @SuppressWarnings("java:S112")
    public static @NotNull RunResult execute(@NotNull Project project,
                                             @NotNull Process process,
                                             @NotNull String commandLine,
                                             @NotNull String title,
                                             int timeoutSec) throws Exception {
        CompletableFuture<Integer> exitFuture = new CompletableFuture<>();
        // StringBuffer is used instead of StringBuilder because OSProcessHandler drives
        // separate reader threads for stdout and stderr, so onTextAvailable can be called
        // concurrently from two threads. StringBuilder is not thread-safe and concurrent
        // appends can corrupt its internal buffer, causing ArrayIndexOutOfBoundsException.
        StringBuffer output = new StringBuffer();

        OSProcessHandler processHandler = new OSProcessHandler(process, commandLine);
        processHandler.addProcessListener(new ProcessListener() {
            @Override
            public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                output.append(event.getText());
            }

            @Override
            public void processTerminated(@NotNull ProcessEvent event) {
                exitFuture.complete(event.getExitCode());
            }
        });

        // Fallback: detect process exit via Process.onExit() in case processTerminated
        // never fires. This happens when reader threads are stuck (e.g. a child process
        // inherited stdout/stderr and holds the pipe open after the main process exits).
        // OSProcessHandler only fires processTerminated after ALL reader threads finish,
        // which requires EOF on both stdout and stderr. A stuck reader prevents this.
        scheduleProcessExitFallback(process, exitFuture);

        EdtUtil.invokeLater(() -> {
            try {
                // Evaluate chat-active state here on the EDT, not before invokeLater, to avoid
                // a stale capture: RunContentExecutor.run() is the actual UI operation,
                // and the user's focus may have changed between the check and this point.
                //
                // RunContentExecutor exposes TWO independent flags, both defaulting to true:
                //   - withActivateToolWindow → setActivateToolWindowWhenAdded (window activation)
                //   - withFocusToolWindow    → setAutoFocusContent           (tab content focus)
                // Even with activateToolWindow=false, setAutoFocusContent(true) still steals
                // keyboard focus into the new console tab when it is added to an already-visible
                // Run window. Both must be gated together to keep focus on the chat prompt.
                boolean chatActive = PsiBridgeService.isChatToolWindowActive(project);
                new RunContentExecutor(project, processHandler)
                    .withTitle(title)
                    .withActivateToolWindow(!chatActive)
                    .withFocusToolWindow(!chatActive)
                    .run();
            } catch (Exception e) {
                // RunContentExecutor.run() may have already called startNotify() before
                // throwing (e.g. if the Run panel fails to register the content descriptor).
                // Only call startNotify() here if the process was not yet started, so the
                // process output listeners can still receive events and the exit future completes.
                if (!processHandler.isStartNotified()) {
                    processHandler.startNotify();
                }
            }
        });

        AgentTabTracker.getInstance(project).trackTab("Run", title);

        try {
            int exitCode = exitFuture.get(timeoutSec, TimeUnit.SECONDS);
            return new RunResult(exitCode, output.toString(), false);
        } catch (TimeoutException e) {
            processHandler.destroyProcess();
            return new RunResult(-1, output.toString(), true);
        }
    }

    /**
     * Convenience overload: create a process from a {@link GeneralCommandLine} and run it in the
     * IDE Run panel. Equivalent to calling
     * {@link #execute(Project, Process, String, String, int)} with
     * {@code cmd.createProcess()} and {@code cmd.getCommandLineString()}.
     *
     * <p>Use this overload when no stdin injection is needed. For hooks that pipe JSON into
     * stdin, create the process manually and use the other overload.
     *
     * @param project    project context for the Run panel
     * @param cmd        command to run
     * @param title      tab title in the Run tool window
     * @param timeoutSec maximum seconds to wait
     * @return a {@link RunResult} describing the outcome
     * @throws Exception if process creation or Run panel setup fails
     */
    @SuppressWarnings("java:S112")
    public static @NotNull RunResult execute(@NotNull Project project,
                                             @NotNull GeneralCommandLine cmd,
                                             @NotNull String title,
                                             int timeoutSec) throws Exception {
        return execute(project, cmd.createProcess(), cmd.getCommandLineString(), title, timeoutSec);
    }

    static void scheduleProcessExitFallback(Process process, CompletableFuture<Integer> exitFuture) {
        process.onExit().thenRunAsync(
            () -> {
                if (exitFuture.isDone()) return;
                if (exitFuture.complete(process.exitValue())) {
                    LOG.warn("processTerminated did not fire within " + READER_GRACE_PERIOD_MS
                        + "ms after process exit — completed via Process.onExit() fallback");
                }
            },
            CompletableFuture.delayedExecutor(READER_GRACE_PERIOD_MS, TimeUnit.MILLISECONDS,
                AppExecutorUtil.getAppScheduledExecutorService())
        );
    }

    /**
     * Like {@link #scheduleProcessExitFallback(Process, CompletableFuture)} but for any
     * {@link ProcessHandler}. Extracts the underlying {@link Process} from
     * {@link BaseProcessHandler} subclasses (which covers {@link OSProcessHandler},
     * {@code KillableProcessHandler}, {@code KillableColoredProcessHandler}, etc.).
     *
     * <p>If the handler is not a {@code BaseProcessHandler} (e.g. a virtual process handler
     * from the External System framework), no fallback is scheduled and the caller must
     * rely on the standard {@code processTerminated} mechanism.
     *
     * @param handler    the process handler to monitor
     * @param exitFuture the future to complete with the exit code
     */
    public static void scheduleHandlerExitFallback(ProcessHandler handler,
                                                   CompletableFuture<Integer> exitFuture) {
        if (handler instanceof BaseProcessHandler<?> bph) {
            scheduleProcessExitFallback(bph.getProcess(), exitFuture);
        }
    }
}
