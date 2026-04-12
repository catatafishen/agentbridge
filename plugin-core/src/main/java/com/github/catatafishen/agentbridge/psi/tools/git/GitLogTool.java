package com.github.catatafishen.agentbridge.psi.tools.git;

import com.github.catatafishen.agentbridge.ui.renderers.GitLogRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("java:S112")
public final class GitLogTool extends GitTool {

    private static final int DEFAULT_MAX_COUNT = 20;
    private static final String PARAM_MAX_COUNT = "max_count";
    private static final String PARAM_FORMAT = "format";
    private static final String PARAM_AUTHOR = "author";
    private static final String PARAM_SINCE = "since";
    private static final String PARAM_UNTIL = "until";
    private static final String PARAM_BRANCH = "branch";

    public GitLogTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "git_log";
    }

    @Override
    public @NotNull String displayName() {
        return "Git Log";
    }

    @Override
    public @NotNull String description() {
        return "Show commit history. Supports filtering by author, date range, file path, "
            + "and branch. Use max_count to limit results. Default: 20 commits.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.READ;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.optional(PARAM_MAX_COUNT, TYPE_INTEGER, "Maximum number of commits to show (default: 20)"),
            Param.optional(PARAM_FORMAT, TYPE_STRING, "Output format: 'oneline', 'short', 'medium', 'full'"),
            Param.optional(PARAM_AUTHOR, TYPE_STRING, "Filter commits by author name or email"),
            Param.optional(PARAM_SINCE, TYPE_STRING, "Show commits after this date (e.g., '2024-01-01', '2 weeks ago')"),
            Param.optional(PARAM_UNTIL, TYPE_STRING, "Show commits before this date (e.g., '2024-12-31', '1 week ago')"),
            Param.optional("path", TYPE_STRING, "Show only commits touching this file"),
            Param.optional(PARAM_BRANCH, TYPE_STRING, "Show commits from this branch (default: current)")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add("log");

        int maxCount = args.has(PARAM_MAX_COUNT)
            ? args.get(PARAM_MAX_COUNT).getAsInt()
            : DEFAULT_MAX_COUNT;
        cmdArgs.add("-n");
        cmdArgs.add(String.valueOf(maxCount));

        String format = args.has(PARAM_FORMAT)
            ? args.get(PARAM_FORMAT).getAsString()
            : "medium";
        cmdArgs.add("--format=" + switch (format) {
            case "oneline" -> "oneline";
            case "short" -> "short";
            case "full" -> "full";
            default -> "medium";
        });

        if (args.has(PARAM_AUTHOR) && !args.get(PARAM_AUTHOR).getAsString().isEmpty()) {
            cmdArgs.add("--author=" + args.get(PARAM_AUTHOR).getAsString());
        }

        if (args.has(PARAM_SINCE) && !args.get(PARAM_SINCE).getAsString().isEmpty()) {
            cmdArgs.add("--since=" + args.get(PARAM_SINCE).getAsString());
        }

        if (args.has(PARAM_UNTIL) && !args.get(PARAM_UNTIL).getAsString().isEmpty()) {
            cmdArgs.add("--until=" + args.get(PARAM_UNTIL).getAsString());
        }

        if (args.has(PARAM_BRANCH) && !args.get(PARAM_BRANCH).getAsString().isEmpty()) {
            // Insert the branch ref as a positional argument before any path separator.
            // Appending here (rather than inserting at a fixed index) ensures we never
            // split flag-value pairs like '-n 20'.
            cmdArgs.add(args.get(PARAM_BRANCH).getAsString());
        }

        if (args.has("path") && !args.get("path").getAsString().isEmpty()) {
            cmdArgs.add("--");
            cmdArgs.add(args.get("path").getAsString());
        }

        String result = runGit(cmdArgs.toArray(String[]::new));
        showFirstCommitInLog(result);
        return result;
    }

    @Override
    public @NotNull Object resultRenderer() {
        return GitLogRenderer.INSTANCE;
    }
}
