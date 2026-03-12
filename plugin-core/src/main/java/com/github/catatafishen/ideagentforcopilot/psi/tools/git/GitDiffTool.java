package com.github.catatafishen.ideagentforcopilot.psi.tools.git;

import com.github.catatafishen.ideagentforcopilot.ui.renderers.GitDiffRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows changes as a diff — staged, unstaged, or against a specific commit.
 */
@SuppressWarnings("java:S112")
public final class GitDiffTool extends GitTool {

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
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"staged", TYPE_BOOLEAN, "If true, show staged (cached) changes only"},
            {"commit", TYPE_STRING, "Compare against this commit (e.g., 'HEAD~1', branch name)"},
            {"path", TYPE_STRING, "Limit diff to this file path"},
            {"stat_only", TYPE_BOOLEAN, "If true, show only file stats (insertions/deletions), not full diff"}
        });
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        flushAndSave();

        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add("diff");

        boolean staged = args.has("staged")
            && args.get("staged").getAsBoolean();
        if (staged) {
            cmdArgs.add("--cached");
        }

        if (args.has("commit") && !args.get("commit").getAsString().isEmpty()) {
            cmdArgs.add(args.get("commit").getAsString());
        }

        boolean statOnly = args.has("stat_only")
            && args.get("stat_only").getAsBoolean();
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
