package com.github.catatafishen.agentbridge.psi;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Structured result from a tool execution, carrying both the text content and an explicit
 * {@link #isError()} flag.
 *
 * <p>Replaces the previous convention of inferring errors from {@code resultText.startsWith("Error")},
 * which was fragile and broke whenever text was prepended to the content (e.g., usage warnings
 * from {@link com.github.catatafishen.agentbridge.psi.tools.Tool}).</p>
 *
 * <p><b>Creating instances:</b></p>
 * <ul>
 *   <li>{@link #success(String)} — tool completed normally</li>
 *   <li>{@link #error(String)} — tool failed; content is the error message</li>
 *   <li>{@link #of(String)} — legacy boundary: wraps a raw string result by inferring
 *       {@code isError} from {@link ToolError#isError(String)} (use only at the
 *       {@code execute(JsonObject)} → {@code execute(JsonObject, String)} boundary)</li>
 * </ul>
 */
public record ToolResult(@Nullable String content, boolean isError) {

    /**
     * Creates a successful result. Content may be null if the tool has no output.
     */
    public static @NotNull ToolResult success(@Nullable String content) {
        return new ToolResult(content, false);
    }

    /**
     * Creates an error result. The content is the error message shown to the agent.
     */
    public static @NotNull ToolResult error(@NotNull String content) {
        return new ToolResult(content, true);
    }

    /**
     * Wraps a legacy String result, inferring {@code isError} via {@link ToolError#isError(String)}.
     * Use only at the boundary where a single-arg {@code execute(JsonObject)} String is promoted
     * to a {@code ToolResult}. Prefer {@link #success} or {@link #error} for new code.
     */
    public static @NotNull ToolResult of(@Nullable String content) {
        return new ToolResult(content, ToolError.isError(content));
    }

    /**
     * Returns the content, or an empty string if {@code content} is null.
     */
    public @NotNull String contentOrEmpty() {
        return java.util.Objects.requireNonNullElse(content, "");
    }

    /**
     * Returns a new {@code ToolResult} with the same {@code isError} flag and the given suffix
     * appended to the content. If the suffix is null or empty, returns {@code this}.
     */
    public @NotNull ToolResult withAppended(@Nullable String suffix) {
        if (suffix == null || suffix.isEmpty()) return this;
        return new ToolResult(content != null ? content + suffix : suffix, isError);
    }
}
