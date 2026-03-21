package com.github.catatafishen.ideagentforcopilot.psi.tools.git;

import com.github.catatafishen.ideagentforcopilot.ui.renderers.GitDiffRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows changes as a diff — staged, unstaged, or against a specific commit.
 */
@SuppressWarnings("java:S112")
public final class GitDiffTool extends GitTool {

    private static final String PARAM_STAGED = "staged";
    private static final String PARAM_COMMIT = "commit";
    private static final String PARAM_STAT_ONLY = "stat_only";

    public GitDiffTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "git_diff";
    }

    @Override
    public @NotNull String displayName() {
        return "Git Diff";
    }

    @Override
    public @NotNull String description() {
        return "Show changes as a diff";
    }

    

    @Override
    public @NotNull String kind() {
        return "read";
    }
@Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
            {PARAM_STAGED, TYPE_BOOLEAN, "If true, show staged (cached) changes only"},
            {PARAM_COMMIT, TYPE_STRING, "Compare against this commit (e.g., 'HEAD~1', branch name)"},
            {"path", TYPE_STRING, "Limit diff to this file path"},
            {PARAM_STAT_ONLY, TYPE_BOOLEAN, "If true, show only file stats (insertions/deletions), not full diff"}
        });
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        flushAndSave();

        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add("diff");

        boolean staged = args.has(PARAM_STAGED)
            && args.get(PARAM_STAGED).getAsBoolean();
        if (staged) {
            cmdArgs.add("--cached");
        }

        if (args.has(PARAM_COMMIT) && !args.get(PARAM_COMMIT).getAsString().isEmpty()) {
            cmdArgs.add(args.get(PARAM_COMMIT).getAsString());
        }

        boolean statOnly = args.has(PARAM_STAT_ONLY)
            && args.get(PARAM_STAT_ONLY).getAsBoolean();
        if (statOnly) {
            cmdArgs.add(1, "--stat");
        }

        if (args.has("path") && !args.get("path").getAsString().isEmpty()) {
            cmdArgs.add("--");
            cmdArgs.add(args.get("path").getAsString());
        }

        return runGit(cmdArgs.toArray(String[]::new));
    }

    @Override
    public @NotNull Object resultRenderer() {
        return GitDiffRenderer.INSTANCE;
    }
}
