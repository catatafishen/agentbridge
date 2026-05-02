package com.github.catatafishen.agentbridge.psi;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Factory for structured MCP tool error messages.
 * <p>
 * Produces messages in the format: {@code Error [CODE]: message}
 * <p>
 * This format is:
 * <ul>
 *   <li>Backward-compatible — starts with "Error" for existing {@code isError} detection
 *       in {@link com.github.catatafishen.agentbridge.services.McpProtocolHandler}</li>
 *   <li>Machine-readable — agents extract the code via regex {@code \[([A-Z_]+)\]}</li>
 *   <li>Human-readable — the message after the code is natural language</li>
 * </ul>
 * <p>
 * Optionally includes a hint line that tells the agent what to do:
 * <pre>
 * Error [FILE_NOT_FOUND]: /foo/bar.txt does not exist
 * Hint: Check the path and try again. Use find_file to search by name.
 * </pre>
 *
 * @see McpErrorCode
 */
public final class ToolError {

    private static final String ERROR_CODE_PREFIX = "Error [";

    private ToolError() { }

    /**
     * Creates a structured error message with code and description.
     *
     * @param code    the error code category
     * @param message human-readable error description
     * @return formatted error string: {@code Error [CODE]: message}
     */
    @NotNull
    public static String of(@NotNull McpErrorCode code, @NotNull String message) {
        return ERROR_CODE_PREFIX + code.name() + "]: " + message;
    }

    /**
     * Creates a structured error message with an actionable hint.
     *
     * @param code    the error code category
     * @param message human-readable error description
     * @param hint    actionable advice for the agent (what to do next)
     * @return formatted error string with hint on a new line
     */
    @NotNull
    public static String of(@NotNull McpErrorCode code, @NotNull String message, @NotNull String hint) {
        return ERROR_CODE_PREFIX + code.name() + "]: " + message + "\nHint: " + hint;
    }

    /**
     * Extracts the {@link McpErrorCode} from a structured error message, if present.
     *
     * @param errorText the error text (e.g., from a tool response)
     * @return the error code, or null if the text doesn't contain a valid code
     */
    @Nullable
    public static McpErrorCode extractCode(@Nullable String errorText) {
        if (errorText == null || !errorText.startsWith(ERROR_CODE_PREFIX)) return null;
        int end = errorText.indexOf(']');
        if (end < 8) return null; // "Error [" is 7 chars, need at least 1 char code
        String codeName = errorText.substring(ERROR_CODE_PREFIX.length(), end);
        try {
            return McpErrorCode.valueOf(codeName);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Checks whether a tool result text represents an error (structured or legacy).
     * Matches both {@code "Error [CODE]: ..."} and legacy {@code "Error: ..."} formats.
     */
    public static boolean isError(@Nullable String resultText) {
        return resultText != null && resultText.startsWith("Error");
    }
}
