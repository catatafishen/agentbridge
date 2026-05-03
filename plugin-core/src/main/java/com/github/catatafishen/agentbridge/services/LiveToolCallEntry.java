package com.github.catatafishen.agentbridge.services;

import com.github.catatafishen.agentbridge.services.hooks.HookStageResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Immutable snapshot of a single MCP tool call for the live tool-use panel.
 * Captures both input and output for inspection, unlike {@link ToolCallRecord}
 * which only stores sizing and duration for statistics.
 *
 * @param callId      unique monotonic ID for reliable completion matching (not affected by list eviction)
 * @param toolName    canonical MCP tool id (e.g. "read_file")
 * @param displayName human-readable tool name (e.g. "Read File"); falls back to toolName if unavailable
 * @param input       raw JSON arguments as a string (pretty-printed for readability)
 * @param output      raw response text (may be truncated at 8K for memory)
 * @param timestamp   when the call started
 * @param durationMs  wall-clock execution time; -1 while still running
 * @param success     true if completed without error; null while running
 * @param category    legacy field carrying the tool kind wire value (e.g. "read", "edit")
 * @param hasHooks    whether this tool call has active hook configuration
 * @param hookStages  ordered list of hook stage results captured during execution (empty if no hooks fired)
 */
public record LiveToolCallEntry(
    long callId,
    @NotNull String toolName,
    @NotNull String displayName,
    @NotNull String input,
    @NotNull String output,
    @NotNull Instant timestamp,
    long durationMs,
    @Nullable Boolean success,
    @Nullable String category,
    boolean hasHooks,
    @NotNull List<HookStageResult> hookStages
) {
    static final int MAX_IO_CHARS = 8_000;
    private static final AtomicLong ID_SEQ = new AtomicLong();

    /**
     * Creates an in-progress entry (no output yet).
     */
    public static LiveToolCallEntry started(@NotNull String toolName,
                                            @NotNull String displayName,
                                            @NotNull String input,
                                            @Nullable String category,
                                            boolean hasHooks) {
        return new LiveToolCallEntry(
            ID_SEQ.incrementAndGet(), toolName, displayName,
            truncate(input), "", Instant.now(), -1, null, category, hasHooks,
            List.of());
    }

    /**
     * Returns a completed copy with the given output and timing.
     */
    public LiveToolCallEntry completed(@NotNull String output, long durationMs, boolean success) {
        return new LiveToolCallEntry(
            callId, toolName, displayName, input, truncate(output),
            timestamp, durationMs, success, category, hasHooks, hookStages);
    }

    /**
     * Returns a copy with the given hook stage results.
     */
    public LiveToolCallEntry withHookStages(@NotNull List<HookStageResult> stages) {
        return new LiveToolCallEntry(
            callId, toolName, displayName, input, output,
            timestamp, durationMs, success, category, hasHooks, List.copyOf(stages));
    }

    /**
     * Whether this entry is still in-flight.
     */
    public boolean isRunning() {
        return success == null;
    }

    private static String truncate(String s) {
        if (s == null || s.length() <= MAX_IO_CHARS) return s != null ? s : "";
        return s.substring(0, MAX_IO_CHARS) + "\n[…truncated]";
    }
}
