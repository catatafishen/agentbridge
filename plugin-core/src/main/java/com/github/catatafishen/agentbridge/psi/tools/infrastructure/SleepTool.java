package com.github.catatafishen.agentbridge.psi.tools.infrastructure;

import com.github.catatafishen.agentbridge.psi.tools.McpRequestDeadline;
import com.github.catatafishen.agentbridge.services.InFlightMcpToolRegistry;
import com.github.catatafishen.agentbridge.ui.BroadcastChatPanel;
import com.github.catatafishen.agentbridge.ui.SleepControls;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Pauses for a number of seconds so an agent does not have to improvise a wait with a shell
 * {@code sleep}, a polling loop or a terminal command.
 *
 * <p>While it runs, the chat shows a countdown with "+10s", "+30s" and "Skip" buttons. Whatever the
 * human does, the agent sees one thing only: the sleep finished. That is deliberate — if the human
 * can see the awaited work is already done, ending the wait is best for both sides, and the agent
 * should not have to reason about why a wait ended early.
 */
public final class SleepTool extends InfrastructureTool {

    private static final String PARAM_SECONDS = "seconds";

    public SleepTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "sleep";
    }

    @Override
    public @NotNull String displayName() {
        return "Sleep";
    }

    @Override
    public @NotNull String description() {
        return "Pause (wait, delay) for a number of seconds, then continue. Use this whenever you need to wait "
            + "before checking something again — a build, deploy, CI run, background process, rate limit or "
            + "retry delay — instead of running `sleep`, a delay or a polling loop in a shell, terminal or "
            + "script. Takes `seconds` (1-" + McpRequestDeadline.MAX_TIMEOUT_SECONDS + "; for a longer wait, "
            + "call it again). It runs no command, so it needs no shell. Returns a short completion message "
            + "when the wait is over. Waiting does not mean the thing you waited for is done: check its state "
            + "afterwards.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.OTHER;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public boolean managesOwnTimeout() {
        return true;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Sleep for {seconds} seconds";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required(PARAM_SECONDS, TYPE_INTEGER,
                "How many seconds to wait (1-" + McpRequestDeadline.MAX_TIMEOUT_SECONDS + "). "
                    + "Larger values are reduced to the maximum; call sleep again to keep waiting.")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        int requested;
        try {
            requested = args.get(PARAM_SECONDS).getAsInt();
        } catch (RuntimeException e) {
            return err("seconds must be a whole number, got: " + args.get(PARAM_SECONDS));
        }
        if (requested <= 0) {
            return err("seconds must be a positive integer, got: " + requested);
        }

        int seconds = Math.min(requested, McpRequestDeadline.MAX_TIMEOUT_SECONDS);
        SleepDeadline deadline = new SleepDeadline(
            System.currentTimeMillis(), seconds, McpRequestDeadline.MAX_TIMEOUT_SECONDS);
        CompletableFuture<String> skipSignal = new CompletableFuture<>();
        String reqId = UUID.randomUUID().toString();

        // Registered so that Stop / an agent crash releases this thread at once instead of leaving
        // it parked until the deadline (same mechanism as prompt_user).
        InFlightMcpToolRegistry registry = InFlightMcpToolRegistry.getInstance(project);
        registry.register(reqId, skipSignal);
        BroadcastChatPanel panel = BroadcastChatPanel.getInstance(project);
        if (panel != null) {
            panel.showSleepRequest(reqId, deadline.deadlineMs(), controlsFor(deadline, skipSignal));
        }

        try {
            awaitDeadline(deadline, skipSignal);
            return McpRequestDeadline.prependNotice(clampNotice(requested), completionMessage(seconds));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return err("sleep interrupted");
        } catch (CancellationException e) {
            String reason = e.getMessage();
            return err(reason == null || reason.isBlank() ? "sleep cancelled" : reason);
        } finally {
            registry.unregister(reqId);
            if (panel != null) {
                panel.endSleepRequest(reqId);
            }
        }
    }

    private static @NotNull SleepControls controlsFor(
        @NotNull SleepDeadline deadline, @NotNull CompletableFuture<String> skipSignal) {
        return new SleepControls() {
            @Override
            public long getMaxDeadlineEpochMs() {
                return deadline.maxDeadlineMs();
            }

            @Override
            public long extend(int seconds) {
                return deadline.extend(seconds);
            }

            @Override
            public void skip() {
                skipSignal.complete("skipped");
            }
        };
    }

    /**
     * Blocks until {@code deadline} passes or {@code skipSignal} completes normally.
     *
     * <p>Re-checks the deadline after every wake-up, because the user may have extended it while
     * this thread was parked.
     *
     * @throws CancellationException if {@code skipSignal} was failed (Stop / agent shutdown); the
     *                               message is the reason
     * @throws InterruptedException  if the worker thread is interrupted
     */
    static void awaitDeadline(@NotNull SleepDeadline deadline, @NotNull CompletableFuture<String> skipSignal)
        throws InterruptedException {
        long remaining = deadline.remainingMs(System.currentTimeMillis());
        while (remaining > 0 && !awaitSkip(skipSignal, remaining)) {
            remaining = deadline.remainingMs(System.currentTimeMillis());
        }
    }

    /**
     * Parks for at most {@code remainingMs} waiting for the skip signal.
     *
     * @return {@code true} if the sleep was skipped, {@code false} if the wait timed out (the
     * deadline may have been extended meanwhile, so the caller re-checks it)
     */
    private static boolean awaitSkip(@NotNull CompletableFuture<String> skipSignal, long remainingMs)
        throws InterruptedException {
        try {
            skipSignal.get(remainingMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            return false;
        } catch (CancellationException e) {
            throw withRealReason(e);
        } catch (ExecutionException e) {
            throw unwrapFailure(e);
        }
    }

    /**
     * {@code Future.get()} wraps a future that failed with a {@code CancellationException} in a new
     * one with a generic message; the reason the registry gave (e.g. "agent stopped") is its cause's.
     */
    private static @NotNull CancellationException withRealReason(@NotNull CancellationException e) {
        Throwable cause = e.getCause();
        String reason = cause != null ? cause.getMessage() : e.getMessage();
        return reason == null ? e : new CancellationException(reason);
    }

    private static @NotNull RuntimeException unwrapFailure(@NotNull ExecutionException e) {
        if (e.getCause() instanceof CancellationException cancelled) {
            return cancelled;
        }
        return new IllegalStateException("sleep wait failed", e.getCause());
    }

    static @NotNull String completionMessage(int seconds) {
        return "Sleep complete (requested " + seconds + "s).";
    }

    /**
     * The notice to prepend when the requested length was reduced, or {@code null} when it was
     * honoured. Reported every time: sleeping less than asked without saying so would look like the
     * wait ended early for no reason.
     */
    static @Nullable String clampNotice(int requestedSeconds) {
        if (requestedSeconds <= McpRequestDeadline.MAX_TIMEOUT_SECONDS) {
            return null;
        }
        return "Note: requested " + requestedSeconds + "s was reduced to " + McpRequestDeadline.MAX_TIMEOUT_SECONDS
            + "s, the longest a single sleep can last (MCP clients abandon a request after roughly 180s). "
            + "Call sleep again to keep waiting.";
    }
}
