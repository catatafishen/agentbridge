package com.github.catatafishen.agentbridge.permissions;

/**
 * Categories of tool operations for permission defaults.
 */
public enum ToolCategory {
    /** Read-only operations: read_file, get_file_outline, search_text, etc. */
    READ,
    /** File editing: edit_text, write_file, replace_symbol_body, etc. */
    EDIT,
    /** Code execution: run_command, run_in_terminal, run_tests, etc. */
    EXECUTE,
    /** Git read operations: git_status, git_diff, git_log, git_blame, etc. */
    GIT_READ,
    /** Git write operations: git_commit, git_stage, git_push, git_merge, etc. */
    GIT_WRITE,
    /** Destructive operations: delete_file, git_reset --hard, etc. */
    DESTRUCTIVE,
    /** Operations that don't fit other categories. */
    OTHER
}
