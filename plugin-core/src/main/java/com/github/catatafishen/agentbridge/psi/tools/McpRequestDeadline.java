package com.github.catatafishen.agentbridge.psi.tools;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The ceiling every blocking tool must keep its wait under, and the notice explaining a clamp.
 *
 * <p><b>Why this exists:</b> MCP clients enforce their own per-request deadline that the plugin can
 * neither see nor extend. Measured against the Copilot CLI: a command sleeping 176s returns
 * normally, while 188s dies with {@code MCP error -32001} — the client abandons the request at
 * roughly 180s. When that happens the agent gets a transport error instead of the tool's output,
 * the work done so far is thrown away, and the underlying process is left running with nobody
 * reading it. Worse, the agent cannot tell "your command failed" from "the tool is broken".
 *
 * <p>Clamping below the observed deadline means a slow tool always answers, and the answer says the
 * command timed out — a result the agent can act on.
 *
 * <p><b>Scope of the measurement:</b> 180s is measured for the Copilot CLI only. The other
 * supported clients (OpenCode, Junie, Kiro, Hermes, Vibe) have not been measured and may be
 * stricter. The 10s of headroom also absorbs response formatting and transport cost on top of the
 * command's own runtime.
 *
 * <p><b>The alternative for long work:</b> {@code run_in_terminal} starts a command and returns
 * immediately; {@code read_terminal_output} polls it. That pair is not bound by this deadline
 * because neither call blocks for the command's duration.
 */
public final class McpRequestDeadline {

    /**
     * Longest a blocking tool may wait, in seconds. See the class javadoc for the derivation.
     */
    public static final int MAX_TIMEOUT_SECONDS = 170;

    private McpRequestDeadline() {
    }

    /**
     * Returns the error to return to the caller when {@code requestedSeconds} is not a usable
     * timeout, or {@code null} when it is.
     *
     * <p>A zero or negative timeout is rejected rather than quietly replaced with the default.
     * Substituting silently makes an invalid argument look like it was honoured: the caller asks
     * for {@code timeout: 0}, the command runs for the default 60s, and nothing in the response
     * says the number was ignored. Every blocking tool should call this before {@link #clamp}.
     */
    public static @Nullable String rejectNonPositive(int requestedSeconds) {
        if (requestedSeconds > 0) return null;
        return "Error: timeout must be a positive integer (seconds), got: " + requestedSeconds;
    }

    /**
     * Clamps a requested timeout to {@link #MAX_TIMEOUT_SECONDS}.
     *
     * @param requestedSeconds the caller's requested timeout; must be positive, which
     *                         {@link #rejectNonPositive} is there to establish
     * @throws IllegalArgumentException if {@code requestedSeconds} is not positive — a caller that
     *                                  skipped validation would otherwise get a timeout it never
     *                                  asked for
     */
    public static int clamp(int requestedSeconds) {
        if (requestedSeconds <= 0) {
            throw new IllegalArgumentException(
                "timeout must be positive, got: " + requestedSeconds + " — call rejectNonPositive first");
        }
        return Math.min(requestedSeconds, MAX_TIMEOUT_SECONDS);
    }

    /**
     * Returns the notice to prepend to a tool response when the requested timeout was clamped, or
     * {@code null} when it was honoured as-is.
     *
     * <p>The clamp is always reported. Waiting for less time than asked without saying so would
     * look like the command failed early on its own, which is exactly the kind of invisible wrong
     * behaviour that costs an agent several turns to diagnose.
     */
    public static @Nullable String clampNotice(int requestedSeconds) {
        if (requestedSeconds <= MAX_TIMEOUT_SECONDS) return null;
        return "Note: requested timeout of " + requestedSeconds + "s was reduced to "
            + MAX_TIMEOUT_SECONDS + "s, the longest this tool can wait before the MCP client "
            + "abandons the request (~180s). For longer-running work use run_in_terminal, which "
            + "returns immediately, then poll it with read_terminal_output.";
    }

    /**
     * Prepends {@code notice} to {@code body}, or returns {@code body} unchanged when null.
     */
    public static @NotNull String prependNotice(@Nullable String notice, @NotNull String body) {
        return notice == null ? body : notice + "\n\n" + body;
    }
}
