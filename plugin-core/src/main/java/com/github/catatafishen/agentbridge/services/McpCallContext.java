package com.github.catatafishen.agentbridge.services;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Thread-local that lets quality tools (especially the popup-handling ones) discover the
 * MCP session key of the caller without plumbing it through every tool's signature.
 *
 * <p><b>Why this exists — DO NOT REMOVE without reading
 * {@code .agent-work/popup-interaction-design-2026-04-30.md}.</b>
 *
 * <p>The popup gate logic ({@link com.github.catatafishen.agentbridge.psi.tools.quality.PopupGateLogic})
 * needs to know which MCP session a tool call belongs to so it can correctly count
 * {@code MAX_UNRELATED_CALLS} per owning session. The MCP protocol handler sets this before
 * dispatching {@code bridge.callTool} and clears it in a {@code finally}. Tools that
 * <em>register</em> a pending popup (via
 * {@link com.github.catatafishen.agentbridge.psi.tools.quality.PendingPopupService}) read the
 * session key from here.
 *
 * <p>Outside MCP (e.g. unit tests calling tools directly), the key is {@code null} and the
 * pending-popup tracking falls back to a synthetic {@code "test:thread-N"} key so registration
 * still works but the per-session counter never matches another caller. Time-based expiry still
 * applies.
 */
public final class McpCallContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private McpCallContext() {
    }

    /**
     * Sets the session key for the current thread. Call from {@code McpProtocolHandler.handleToolsCall}
     * before {@code bridge.callTool} and pair with {@link #clear()} in a {@code finally}.
     */
    public static void setCurrent(@NotNull String sessionKey) {
        CURRENT.set(sessionKey);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /**
     * Returns the session key for the current thread or a synthetic fallback derived from the
     * thread name when no MCP context is bound (e.g. unit tests).
     */
    @NotNull
    public static String currentOrFallback() {
        String s = CURRENT.get();
        return s != null ? s : "test:" + Thread.currentThread().getName();
    }

    /**
     * Returns the session key for the current thread, or {@code null} when none is bound.
     */
    @Nullable
    public static String current() {
        return CURRENT.get();
    }
}
