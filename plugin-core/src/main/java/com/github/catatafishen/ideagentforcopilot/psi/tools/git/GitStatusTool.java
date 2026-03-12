package com.github.catatafishen.ideagentforcopilot.psi.tools.git;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.catatafishen.ideagentforcopilot.ui.renderers.GitStatusRenderer;

/**
 * Shows the working tree status (staged, unstaged, untracked files).
 */
@SuppressWarnings("java:S112")
public final class GitStatusTool extends GitTool {

    public GitStatusTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "git_status";
    }

    @Override
    public @NotNull String displayName() {
        return "Git Status";
    }

    @Override
    public @NotNull String description() {
        return "Show working tree status";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"verbose", TYPE_BOOLEAN, "If true, show full 'git status' output including untracked files"}
        });
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        flushAndSave();

        boolean verbose = args.has("verbose")
            && args.get("verbose").getAsBoolean();

        if (verbose) {
            return runGit("status");
        }
        return runGit("status", "--short", "--branch");
    }

    @Override
    public @NotNull Object resultRenderer() {
        return GitStatusRenderer.INSTANCE;
    }
}
