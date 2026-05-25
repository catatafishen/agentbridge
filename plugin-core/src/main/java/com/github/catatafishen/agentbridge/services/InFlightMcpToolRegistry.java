package com.github.catatafishen.agentbridge.services;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks in-flight MCP tool requests that block on a {@link CompletableFuture} (e.g. the
 * {@code prompt_user} tool which awaits a user reply).
 *
 * <p>If the agent process exits while such a tool is still waiting, the tool's pooled
 * thread would block indefinitely (or until the tool's own timeout) and the UI would show
 * an orphan spinning chip that never resolves. {@link #cancelAll(String)} releases all
 * waiters with an error sentinel so the tool returns immediately and the agent-crash UI
 * teardown can proceed cleanly.
 *
 */
@SuppressWarnings("unused") // Used via PlatformApiCompat.getService — IDE doesn't see the reflective lookup
@Service(Service.Level.PROJECT)
public final class InFlightMcpToolRegistry {

    private static final Logger LOG = Logger.getInstance(InFlightMcpToolRegistry.class);

    /**
     * Sentinel value used to signal cancellation due to agent crash.
     */
    public static final String CANCELLATION_SENTINEL = "__agent_crashed__";

    private final Map<String, CompletableFuture<String>> inFlight = new ConcurrentHashMap<>();

    @SuppressWarnings("unused") // Project parameter required by IntelliJ project-level service contract
    public InFlightMcpToolRegistry(@NotNull Project project) {
    }

    public static InFlightMcpToolRegistry getInstance(@NotNull Project project) {
        return PlatformApiCompat.getService(project, InFlightMcpToolRegistry.class);
    }

    /**
     * Register a future that the calling tool is about to block on. The caller MUST call
     * {@link #unregister(String)} in a {@code finally} block.
     */
    public void register(@NotNull String id, @NotNull CompletableFuture<String> future) {
        inFlight.put(id, future);
    }

    public void unregister(@NotNull String id) {
        inFlight.remove(id);
    }

    /**
     * Complete every registered future with {@link #CANCELLATION_SENTINEL} so blocked tool
     * threads return immediately. Idempotent.
     *
     * @param reason human-readable reason (logged; tools may surface it via their own
     *               error message)
     */
    public void cancelAll(@NotNull String reason) {
        if (inFlight.isEmpty()) return;
        int count = inFlight.size();
        // Snapshot keys to avoid CME if a tool's completion handler re-enters unregister().
        for (Map.Entry<String, CompletableFuture<String>> entry : Map.copyOf(inFlight).entrySet()) {
            CompletableFuture<String> future = entry.getValue();
            if (!future.isDone()) {
                future.complete(CANCELLATION_SENTINEL);
            }
        }
        inFlight.clear();
        LOG.info("InFlightMcpToolRegistry: cancelled " + count + " in-flight tool(s) — " + reason);
    }
}
