package com.github.catatafishen.agentbridge.permissions;

/**
 * Where a tool call reaches on disk, relative to the current project.
 * <p>
 * Some tools (e.g. {@code read_file}, {@code search_text}, {@code attach_external_dir})
 * can target paths either inside or outside the project root. Users may want different
 * permission levels for each — for example, auto-allow reads inside the project but
 * ask before reading arbitrary files on the user's machine.
 * <p>
 * Tools that are intrinsically project-scoped (e.g. {@code write_file}, {@code edit_text})
 * or shell-scoped (e.g. {@code run_command}) should pass {@link #NOT_APPLICABLE} —
 * the resolver then treats them with a single set of rules per {@link ToolCategory}.
 */
public enum PathScope {
    /** Path resolves inside the current project (or an attached external directory treated as project). */
    INSIDE_PROJECT,

    /** Path resolves outside the project — arbitrary location on the user's filesystem. */
    OUTSIDE_PROJECT,

    /** Tool has no meaningful path-scope distinction (writes, shell exec, git, etc.). */
    NOT_APPLICABLE
}
