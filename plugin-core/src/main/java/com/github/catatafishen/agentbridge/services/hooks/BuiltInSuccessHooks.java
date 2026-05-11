package com.github.catatafishen.agentbridge.services.hooks;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Built-in success-hook annotations for shell tool calls. Platform-independent Java
 * replacement for {@code run-in-terminal-reprimand.sh} and {@code check-stale-naming.sh}.
 *
 * <p>Called by {@link HookPipeline} when no user script produced output, so the agent
 * still receives nudges on all platforms without requiring {@code sh} or Git for Windows.
 *
 * <p>Methods return text to append to the tool output, or {@code null} if no annotation needed.
 */
public final class BuiltInSuccessHooks {

    private static final String GREP_NUDGE =
        "\n\n⚠️ Prefer search_text or search_symbols over shell grep — they search live editor buffers and support semantic lookup.";

    private static final String CAT_NUDGE =
        "\n\n⚠️ Prefer read_file over shell cat/head/tail — it reads live editor buffers, not stale disk content.";

    private static final String FIND_NUDGE =
        "\n\n⚠️ Prefer list_project_files or list_directory_tree over shell find — they respect project structure and exclusions.";

    private static final String LS_NUDGE =
        "\n\n⚠️ Prefer list_project_files or list_directory_tree over shell ls/tree — they respect project structure and exclusions.";

    private static final String TEST_NUDGE =
        "\n\n⚠️ Prefer run_tests over shell test commands — it provides structured pass/fail results with IntelliJ test runner integration.";

    private static final String BUILD_NUDGE =
        "\n\n⚠️ Prefer build_project over shell compile commands — it uses IntelliJ incremental compiler with structured error reporting.";

    private BuiltInSuccessHooks() {
    }

    /**
     * Returns a soft nudge when a {@code run_in_terminal} command has a dedicated MCP
     * tool equivalent. Mirrors the logic of {@code run-in-terminal-reprimand.sh}.
     *
     * @param command the command that was executed (may be null)
     * @param isError whether the tool reported an error (skip nudges on error)
     * @return text to append, or {@code null}
     */
    public static @Nullable String terminalReprimand(@Nullable String command, boolean isError) {
        if (isError || command == null || command.isBlank()) return null;
        String lower = command.toLowerCase(Locale.ROOT).stripLeading();

        if (lower.startsWith("grep ") || lower.startsWith("rg ") || lower.startsWith("ag ")
            || lower.contains("| grep ") || lower.contains("| rg ") || lower.contains("| ag ")) {
            return GREP_NUDGE;
        }
        if (lower.startsWith("cat ") || lower.startsWith("head ") || lower.startsWith("tail ")
            || lower.startsWith("less ") || lower.startsWith("more ") || lower.contains("| cat ")) {
            return CAT_NUDGE;
        }
        if (lower.startsWith("find ") || lower.startsWith("find.")) return FIND_NUDGE;
        if (isLsLike(lower)) return LS_NUDGE;
        if (isTestRunner(lower)) return TEST_NUDGE;
        if (lower.startsWith("./gradlew compile") || lower.startsWith("./gradlew classes")
            || lower.startsWith("gradle compile") || lower.startsWith("mvn compile")) {
            return BUILD_NUDGE;
        }

        return null;
    }

    /**
     * Warns when {@code write_file} writes 100+ lines to an existing file.
     * Mirrors the logic of {@code check-stale-naming.sh}.
     *
     * @param output  the tool's output string (checked for "Written:" prefix)
     * @param content the file content that was written (may be null)
     * @return text to append, or {@code null}
     */
    public static @Nullable String staleNamingCheck(@Nullable String output, @Nullable String content) {
        if (output == null || !output.startsWith("Written:")) return null;
        if (content == null || content.isBlank()) return null;

        int lines = countLines(content);
        if (lines < 100) return null;

        return "\n\n⚠️ **Stale naming check**: this file now has " + lines + " lines. " +
            "Verify that the file name, class names, function names, and comments still accurately " +
            "reflect the current behavior — large rewrites often introduce stale terminology.";
    }

    private static boolean isLsLike(String lower) {
        return lower.equals("ls") || lower.startsWith("ls ") || lower.startsWith("ls\t")
            || lower.equals("dir") || lower.startsWith("dir ") || lower.startsWith("dir\t")
            || lower.equals("tree") || lower.startsWith("tree ") || lower.startsWith("tree\t");
    }

    private static boolean isTestRunner(String lower) {
        return lower.startsWith("npm test") || lower.startsWith("npm run test")
            || lower.startsWith("yarn test") || lower.startsWith("pnpm test")
            || lower.startsWith("pytest") || lower.startsWith("python -m pytest")
            || lower.startsWith("jest") || lower.startsWith("vitest")
            || lower.startsWith("mocha") || lower.startsWith("ava ") || lower.equals("ava")
            || lower.startsWith("jasmine")
            || lower.startsWith("./gradlew test") || lower.startsWith("gradle test")
            || lower.startsWith("./gradlew check") || lower.startsWith("./gradlew build")
            || lower.startsWith("mvn test") || lower.startsWith("mvn verify")
            || lower.startsWith("mvn package") || lower.startsWith("go test");
    }

    private static int countLines(String content) {
        int count = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') count++;
        }
        return count;
    }
}
