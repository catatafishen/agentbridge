package com.github.catatafishen.agentbridge.services;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks in-flight MCP tool requests that block on a {@link CompletableFuture} (e.g. the
 * {@code prompt_user} tool which awaits a user reply).
 *
 * <p>If the agent process exits while such a tool is still waiting, the tool's pooled
 * thread would block indefinitely (or until the tool's own timeout) and the UI would show
 * an orphan spinning chip that never resolves. {@link #cancelAll(String)} releases all
 * waiters by failing their futures with a {@link CancellationException} so the tool
 * returns immediately and the agent-crash UI teardown can proceed cleanly.
 *
 * <p>After {@link #cancelAll(String)} has fired, any subsequent {@link #register(String,
 * CompletableFuture)} call also immediately fails the supplied future. This closes a race
 * window where a tool might register a future just after the shutdown snapshot was taken
 * but before {@code clear()} ran, which previously left the late future un-cancelled.
 */
@SuppressWarnings("unused") // Used via PlatformApiCompat.getService — IDE doesn't see the reflective lookup
@Service(Service.Level.PROJECT)
public final class InFlightMcpToolRegistry {

    private static final Logger LOG = Logger.getInstance(InFlightMcpToolRegistry.class);

    private final Map<String, CompletableFuture<String>> inFlight = new ConcurrentHashMap<>();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile String cancelReason = "agent stopped";

    @SuppressWarnings("unused") // Project parameter required by IntelliJ project-level service contract
    public InFlightMcpToolRegistry(@NotNull Project project) {
    }

    public static InFlightMcpToolRegistry getInstance(@NotNull Project project) {
        return PlatformApiCompat.getService(project, InFlightMcpToolRegistry.class);
    }

    /**
     * Register a future that the calling tool is about to block on. The caller MUST call
     * {@link #unregister(String)} in a {@code finally} block.
     *
     * <p>If {@link #cancelAll(String)} has already been invoked, the supplied future is
     * immediately completed exceptionally so the late-arriving tool returns at once.
     */
    public void register(@NotNull String id, @NotNull CompletableFuture<String> future) {
        if (cancelled.get()) {
            if (!future.isDone()) {
                future.completeExceptionally(new CancellationException(cancelReason));
            }
            return;
        }
        inFlight.put(id, future);
        // Double-check: cancelAll may have run between the flag check and the put. If so, we
        // must drain our own registration to honor the cancellation contract.
        if (cancelled.get()) {
            CompletableFuture<String> snapshot = inFlight.remove(id);
            if (snapshot != null && !snapshot.isDone()) {
                snapshot.completeExceptionally(new CancellationException(cancelReason));
            }
        }
    }

    public void unregister(@NotNull String id) {
        inFlight.remove(id);
    }

    /**
     * Complete every registered future exceptionally with a {@link CancellationException}
     * so blocked tool threads return immediately. Idempotent. After this returns, any
     * future {@link #register} call is also immediately cancelled.
     *
     * @param reason human-readable reason (logged; tools may surface it via their own
     *               error message)
     */
    public void cancelAll(@NotNull String reason) {
        cancelReason = reason;
        // Set the flag first so any concurrent register() picks it up.
        cancelled.set(true);
        if (inFlight.isEmpty()) return;
        int count = inFlight.size();
        // Snapshot keys to avoid CME if a tool's completion handler re-enters unregister().
        for (Map.Entry<String, CompletableFuture<String>> entry : Map.copyOf(inFlight).entrySet()) {
            CompletableFuture<String> future = entry.getValue();
            if (!future.isDone()) {
                future.completeExceptionally(new CancellationException(reason));
            }
        }
        inFlight.clear();
        LOG.info("InFlightMcpToolRegistry: cancelled " + count + " in-flight tool(s) — " + reason);
    }
}
