package com.github.catatafishen.agentbridge.psi;

/**
 * Machine-readable error codes for MCP tool responses.
 * <p>
 * When a tool returns an error, the response text uses the format:
 * {@code Error [CODE]: Human-readable message}
 * <p>
 * Agents can extract the code with a simple regex {@code \[([A-Z_]+)\]}
 * and use it for automated retry/recovery logic — e.g., retrying after
 * INDEX_NOT_READY, prompting for a missing parameter on MISSING_PARAM,
 * or suggesting an alternative path on FILE_NOT_FOUND.
 * <p>
 * The format maintains backward compatibility — responses still start
 * with "Error" so existing {@code isError} detection works unchanged.
 *
 * @see ToolError
 */
public enum McpErrorCode {

    // ── Parameter validation ─────────────────────────────────────────────

    /** A required parameter was not provided. */
    MISSING_PARAM("Required parameter missing"),

    /** A parameter has an invalid type, format, or value. */
    INVALID_PARAM("Invalid parameter value"),

    // ── File system ──────────────────────────────────────────────────────

    /** The specified file or directory does not exist. */
    FILE_NOT_FOUND("File not found"),

    /** The file cannot be parsed (corrupted, binary, unsupported format). */
    FILE_NOT_PARSEABLE("File cannot be parsed"),

    // ── IDE readiness ────────────────────────────────────────────────────

    /** IDE indexing is still in progress; index-dependent tools cannot run. */
    INDEX_NOT_READY("Indexing in progress"),

    /** The project is still initializing and not yet fully opened. */
    PROJECT_NOT_READY("Project not ready"),

    /** A modal dialog is blocking interactive EDT operations. */
    MODAL_BLOCKING("Modal dialog blocking"),

    /** A project build is already running. */
    BUILD_IN_PROGRESS("Build in progress"),

    /** A popup chooser from a previous tool call is awaiting a response. */
    POPUP_BLOCKING("Popup chooser blocking"),

    // ── Tool infrastructure ──────────────────────────────────────────────

    /** The requested tool is disabled in settings. */
    TOOL_DISABLED("Tool is disabled"),

    /** The operation timed out. */
    TIMEOUT("Operation timed out"),

    /** The project is disposed (closed). */
    PROJECT_DISPOSED("Project disposed"),

    // ── Code intelligence ────────────────────────────────────────────────

    /** A symbol (class, method, field) could not be found. */
    SYMBOL_NOT_FOUND("Symbol not found"),

    /** Multiple matches found where a unique match was expected. */
    AMBIGUOUS_MATCH("Ambiguous match"),

    // ── Debug ────────────────────────────────────────────────────────────

    /** No active debug session or the session is not paused. */
    NO_DEBUG_SESSION("No debug session"),

    // ── Git ──────────────────────────────────────────────────────────────

    /** A git command failed (non-zero exit code). */
    GIT_COMMAND_FAILED("Git command failed"),

    // ── General ──────────────────────────────────────────────────────────

    /** An unexpected internal error occurred. */
    INTERNAL_ERROR("Internal error"),

    /** The operation is not applicable in the current context. */
    NOT_APPLICABLE("Not applicable");

    private final String description;

    McpErrorCode(String description) {
        this.description = description;
    }

    /** Short human-readable description of this error category. */
    public String description() {
        return description;
    }
}
