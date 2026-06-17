package com.github.catatafishen.agentbridge.services;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Set;
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
    /**
     * Currently-executing tool worker threads (the pooled threads on which
     * {@code McpProtocolHandler.callToolWithTimeout} runs each tool's {@code execute()}).
     * A user Stop interrupts these threads to terminate blocking tools such as
     * {@code run_command}, whose worker is parked in {@code RunPanelExecutor.execute}
     * waiting on the child process. Distinct from {@link #inFlight}, which only tracks
     * tools that block on an externally completed future (e.g. {@code prompt_user}).
     */
    private final Set<Thread> workers = ConcurrentHashMap.newKeySet();
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
     * Register the worker thread currently executing a tool's {@code execute()} so a later
     * cancellation can interrupt it. The caller MUST call {@link #unregisterWorker(Thread)}
     * in a {@code finally} block.
     *
     * <p>If {@link #cancelAll(String)} has already latched the registry closed (agent
     * shutdown), the thread is interrupted immediately so a late-arriving tool unwinds at once.
     */
    public void registerWorker(@NotNull Thread worker) {
        workers.add(worker);
        if (cancelled.get()) {
            worker.interrupt();
            workers.remove(worker);
        }
    }

    public void unregisterWorker(@NotNull Thread worker) {
        workers.remove(worker);
    }

    /**
     * Transient cancel for a user-initiated Stop of the current turn: cancels every blocked
     * future and interrupts every executing tool worker thread, but does NOT latch the
     * registry closed. Tool calls registered after this returns proceed normally, so the
     * next prompt works without a reconnect.
     *
     * <p>Use this for the Stop button. Use {@link #cancelAll(String)} only when the agent is
     * actually going away (disconnect / crash), where later registrations must also fail.
     *
     * @param reason human-readable reason (logged; tools may surface it via their own error)
     */
    public void cancelInFlight(@NotNull String reason) {
        int futures = drainFutures(reason);
        int interrupted = interruptWorkers();
        if (futures > 0 || interrupted > 0) {
            LOG.info("InFlightMcpToolRegistry: cancelInFlight cancelled " + futures
                + " future(s) and interrupted " + interrupted + " worker(s) — " + reason);
        }
    }

    /**
     * Completes every registered future exceptionally and clears the map. Returns the count.
     */
    private int drainFutures(@NotNull String reason) {
        if (inFlight.isEmpty()) return 0;
        int count = inFlight.size();
        // Snapshot keys to avoid CME if a tool's completion handler re-enters unregister().
        for (Map.Entry<String, CompletableFuture<String>> entry : Map.copyOf(inFlight).entrySet()) {
            CompletableFuture<String> future = entry.getValue();
            if (!future.isDone()) {
                future.completeExceptionally(new CancellationException(reason));
            }
        }
        inFlight.clear();
        return count;
    }

    /**
     * Interrupts every registered worker thread and clears the set. Returns the count.
     */
    private int interruptWorkers() {
        if (workers.isEmpty()) return 0;
        int count = 0;
        for (Thread worker : Set.copyOf(workers)) {
            worker.interrupt();
            count++;
        }
        workers.clear();
        return count;
    }

    /**
     * Complete every registered future exceptionally with a {@link CancellationException}
     * and interrupt every executing worker thread so blocked tools return immediately.
     * Idempotent. After this returns, any future {@link #register} or {@link #registerWorker}
     * call is also immediately cancelled — use this only on agent shutdown / crash, where
     * late registrations must fail too. For a user Stop of the current turn, use
     * {@link #cancelInFlight(String)} which does not latch the registry closed.
     *
     * @param reason human-readable reason (logged; tools may surface it via their own
     *               error message)
     */
    public void cancelAll(@NotNull String reason) {
        cancelReason = reason;
        // Set the flag first so any concurrent register()/registerWorker() picks it up.
        cancelled.set(true);
        int futures = drainFutures(reason);
        int interrupted = interruptWorkers();
        if (futures > 0 || interrupted > 0) {
            LOG.info("InFlightMcpToolRegistry: cancelled " + futures + " in-flight tool(s) and interrupted "
                + interrupted + " worker(s) — " + reason);
        }
    }

    /**
     * Re-opens the registry after a {@link #cancelAll(String)} latch, allowing subsequent
     * {@link #register} and {@link #registerWorker} calls to proceed normally again.
     *
     * <p><b>Why this exists:</b> {@link #cancelAll(String)} latches {@code cancelled=true} when an
     * agent client stops (see {@code AcpClient.stop}). Because this is a <em>project-level</em>
     * service shared across all agent clients and sessions, switching clients or sessions — which
     * stops the outgoing client and therefore fires {@code cancelAll} — would otherwise leave the
     * latch permanently closed. Every tool call on the new session would then have its worker
     * interrupted immediately at {@link #registerWorker}, failing even read-only tools with
     * "Tool execution interrupted" until the IDE is restarted. Calling this at the start of each
     * new turn clears the latch so the new session's tools run normally.
     *
     * <p>Idempotent and safe to call when the registry is already open.
     */
    public void reopen() {
        if (cancelled.compareAndSet(true, false)) {
            cancelReason = "agent stopped";
            LOG.info("InFlightMcpToolRegistry: re-opened after a prior cancelAll latch");
        }
    }
}
