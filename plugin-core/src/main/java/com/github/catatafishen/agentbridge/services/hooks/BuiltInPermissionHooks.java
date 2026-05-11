package com.github.catatafishen.agentbridge.services.hooks;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Built-in permission checks for shell tool calls. Platform-independent Java
 * replacement for {@code run-command-abuse.sh} and {@code run-in-terminal-abort.sh}.
 *
 * <p>Checked by {@link HookPipeline} before any script-based hooks, so they work
 * on all platforms without requiring {@code sh} or Git for Windows in PATH.
 *
 * <p>Returns a denial reason string, or {@code null} if the command is allowed.
 */
public final class BuiltInPermissionHooks {

    private static final String GIT_DENY =
        "git commands are not allowed via %s (causes IntelliJ buffer desync). " +
        "Use the dedicated git tools instead: git_status, git_diff, git_log, git_commit, " +
        "git_stage, git_unstage, git_branch, git_stash, git_show, git_blame, git_push, " +
        "git_remote, git_fetch, git_pull, git_merge, git_rebase, git_cherry_pick, git_tag, git_reset.";

    private static final String CAT_DENY =
        "cat/head/tail/less/more are not allowed via run_command (reads stale disk files). " +
        "Use read_file to read live editor buffers instead.";

    private static final String SED_DENY =
        "sed is not allowed via %s (bypasses IntelliJ editor buffers). " +
        "Use edit_text with old_str/new_str for file editing instead.";

    private static final String FIND_DENY =
        "find commands are not allowed via run_command. " +
        "Use list_project_files or list_directory_tree to find files instead.";

    private static final String GRADLE_DENY =
        "Gradle compile tasks are not allowed via run_command. " +
        "Use build_project to compile via IntelliJ incremental compiler instead.";

    private BuiltInPermissionHooks() {
    }

    /**
     * Checks a {@code run_command} invocation for disallowed patterns.
     * Mirrors the logic of {@code run-command-abuse.sh}.
     *
     * @param command the command string (may be null)
     * @return denial reason, or {@code null} if allowed
     */
    public static @Nullable String checkRunCommand(@Nullable String command) {
        if (command == null || command.isBlank()) return null;
        String lower = command.toLowerCase(Locale.ROOT);

        if (isGitCommand(lower)) return String.format(GIT_DENY, "run_command");
        if (isCatLike(lower)) return CAT_DENY;
        if (isSed(lower)) return String.format(SED_DENY, "run_command");
        if (lower.startsWith("find ") || lower.startsWith("find\t")) return FIND_DENY;
        if (isGradleCompileOnly(lower)) return GRADLE_DENY;

        return null;
    }

    /**
     * Checks a {@code run_in_terminal} invocation for hard-blocked patterns.
     * Mirrors the logic of {@code run-in-terminal-abort.sh}.
     *
     * @param command the command string (may be null)
     * @return denial reason, or {@code null} if allowed
     */
    public static @Nullable String checkRunInTerminal(@Nullable String command) {
        if (command == null || command.isBlank()) return null;
        String lower = command.toLowerCase(Locale.ROOT);

        if (isGitCommand(lower)) return String.format(GIT_DENY, "run_in_terminal");
        if (isSed(lower)) return String.format(SED_DENY, "run_in_terminal");

        return null;
    }

    private static boolean isGitCommand(String lower) {
        return lower.startsWith("git ") || lower.equals("git")
            || lower.contains("&& git ") || lower.contains("; git ") || lower.contains("| git ");
    }

    private static boolean isCatLike(String lower) {
        return lower.startsWith("cat ") || lower.startsWith("head ") || lower.startsWith("tail ")
            || lower.startsWith("less ") || lower.startsWith("more ")
            || lower.contains("| cat ") || lower.contains("&& cat ") || lower.contains("; cat ");
    }

    private static boolean isSed(String lower) {
        return lower.startsWith("sed ") || lower.startsWith("sed\t")
            || lower.contains("| sed") || lower.contains("&& sed") || lower.contains("; sed");
    }

    /**
     * Gradle compile-only tasks (compilejava, compilekotlin, classes, testclasses)
     * that do NOT also run tests, build, check, or assemble.
     */
    private static boolean isGradleCompileOnly(String lower) {
        if (!lower.contains("gradlew") && !lower.contains("gradle ")) return false;
        boolean isCompileTask = lower.contains("compilejava") || lower.contains("compilekotlin")
            || lower.contains(":classes") || lower.contains(":testclasses");
        if (!isCompileTask) return false;
        return !lower.contains("test") && !lower.contains("check")
            && !lower.contains("build") && !lower.contains("assemble");
    }
}
