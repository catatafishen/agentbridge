package com.github.catatafishen.agentbridge.permissions;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Detects when agents use shell tools ({@code run_command}, {@code run_in_terminal})
 * for operations that have dedicated MCP tools. Returns a denial reason with the
 * suggested MCP tool, or {@code null} if no abuse detected.
 * <p>
 * Thread-safe. Stateless.
 */
public final class AbuseDetector {

    private static final List<AbuseRule> RULES = List.of(
            new AbuseRule(
                    "git",
                    Pattern.compile("^\\s*(?:git|/usr/bin/git)\\s+.*"),
                    "Don't use git via shell. Use dedicated git tools: " +
                            "git_status, git_diff, git_log, git_commit, git_stage, " +
                            "git_push, git_pull, git_branch, etc."
            ),
            new AbuseRule(
                    "cat",
                    Pattern.compile("^\\s*(?:cat|head|tail|less|more)\\s+.*"),
                    "Use read_file instead — reads from live editor buffers, " +
                            "not stale files on disk."
            ),
            new AbuseRule(
                    "grep",
                    Pattern.compile("^\\s*(?:grep|rg|ag|ack)\\s+.*"),
                    "Use search_text or search_symbols instead — " +
                            "searches across all project files using IntelliJ's index."
            ),
            new AbuseRule(
                    "sed",
                    Pattern.compile("^\\s*(?:sed|awk)\\s+.*"),
                    "Use edit_text or replace_symbol_body instead — " +
                            "edits go through IntelliJ's Document API with undo support."
            ),
            new AbuseRule(
                    "find",
                    Pattern.compile("^\\s*(?:find|fd|locate)\\s+.*"),
                    "Use list_project_files instead — " +
                            "supports glob patterns, size/date filters, and sorting."
            ),
            new AbuseRule(
                    "ls",
                    Pattern.compile("^\\s*(?:ls|dir)\\s+.*"),
                    "Use list_project_files instead — " +
                            "provides structured output with file metadata."
            )
    );

    private static final List<String> SHELL_TOOL_IDS = List.of(
            "run_command", "run_in_terminal"
    );

    /**
     * Check a tool call for abuse patterns.
     *
     * @param toolId    the canonical tool ID (e.g. "run_command")
     * @param arguments the raw arguments JSON string (may contain "command" field)
     * @return abuse result with reason and suggestion, or null if no abuse
     */
    public @Nullable AbuseResult check(String toolId, @Nullable String arguments) {
        if (!isShellTool(toolId)) {
            return null;
        }

        String command = extractCommand(arguments);
        if (command == null || command.isBlank()) {
            return null;
        }

        for (AbuseRule rule : RULES) {
            if (rule.pattern().matcher(command).matches()) {
                return new AbuseResult(rule.category(), rule.reason());
            }
        }

        return null;
    }

    private boolean isShellTool(String toolId) {
        return SHELL_TOOL_IDS.contains(toolId);
    }

    /**
     * Extract the command string from tool arguments.
     * Handles both JSON {"command": "..."} and raw string formats.
     */
    private @Nullable String extractCommand(@Nullable String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return null;
        }

        // Try to extract "command" field from JSON
        int cmdIdx = arguments.indexOf("\"command\"");
        if (cmdIdx < 0) {
            return arguments.trim();
        }

        int colonIdx = arguments.indexOf(':', cmdIdx);
        if (colonIdx < 0) {
            return null;
        }

        int quoteStart = arguments.indexOf('"', colonIdx + 1);
        if (quoteStart < 0) {
            return null;
        }

        int quoteEnd = findClosingQuote(arguments, quoteStart + 1);
        if (quoteEnd < 0) {
            return null;
        }

        return arguments.substring(quoteStart + 1, quoteEnd);
    }

    private int findClosingQuote(String s, int start) {
        for (int i = start; i < s.length(); i++) {
            if (s.charAt(i) == '"' && (i == 0 || s.charAt(i - 1) != '\\')) {
                return i;
            }
        }
        return -1;
    }

    private record AbuseRule(String category, Pattern pattern, String reason) {}
}
