package com.github.catatafishen.ideagentforcopilot.psi.tools.git;

import com.github.catatafishen.ideagentforcopilot.ui.renderers.GitLogRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows commit history with configurable format, author, date, and path filters.
 */
@SuppressWarnings("java:S112")
public final class GitLogTool extends GitTool {

    private static final int DEFAULT_MAX_COUNT = 20;

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
        return "Show commit history";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"max_count", TYPE_INTEGER, "Maximum number of commits to show (default: 10)"},
            {"format", TYPE_STRING, "Output format: 'oneline', 'short', 'medium', 'full'"},
            {"author", TYPE_STRING, "Filter commits by author name or email"},
            {"since", TYPE_STRING, "Show commits after this date (e.g., '2024-01-01')"},
            {"path", TYPE_STRING, "Show only commits touching this file"},
            {"branch", TYPE_STRING, "Show commits from this branch (default: current)"}
        });
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add("log");

        int maxCount = args.has("max_count")
            ? args.get("max_count").getAsInt()
            : DEFAULT_MAX_COUNT;
        cmdArgs.add("-n");
        cmdArgs.add(String.valueOf(maxCount));

        String format = args.has("format")
            ? args.get("format").getAsString()
            : "medium";
        cmdArgs.add("--format=" + switch (format) {
            case "oneline" -> "oneline";
            case "short" -> "short";
            case "full" -> "full";
            default -> "medium";
        });

        if (args.has("author") && !args.get("author").getAsString().isEmpty()) {
            cmdArgs.add("--author=" + args.get("author").getAsString());
        }

        if (args.has("since") && !args.get("since").getAsString().isEmpty()) {
            cmdArgs.add("--since=" + args.get("since").getAsString());
        }

        if (args.has("branch") && !args.get("branch").getAsString().isEmpty()) {
            cmdArgs.add(2, args.get("branch").getAsString());
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
