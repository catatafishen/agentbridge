package com.github.catatafishen.ideagentforcopilot.psi.tools.git;

import com.github.catatafishen.ideagentforcopilot.psi.GitToolHandler;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Reverts a commit by creating a new commit that undoes its changes.
 */
@SuppressWarnings("java:S112")
public final class GitRevertTool extends GitTool {

    public GitRevertTool(Project project, GitToolHandler git) {
        super(project, git);
    }

    @Override
    public @NotNull String id() {
        return "git_revert";
    }

    @Override
    public @NotNull String displayName() {
        return "Git Revert";
    }

    @Override
    public @NotNull String description() {
        return "Revert a commit by creating a new commit";
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"commit", TYPE_STRING, "Commit SHA to revert"},
            {"no_commit", TYPE_BOOLEAN, "If true, revert changes to working tree without creating a commit"},
            {"no_edit", TYPE_BOOLEAN, "If true, use the default commit message without editing"}
        }, "commit");
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        if (!args.has("commit") || args.get("commit").getAsString().isEmpty()) {
            return "Error: 'commit' parameter is required";
        }

        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add("revert");

        if (args.has("no_commit") && args.get("no_commit").getAsBoolean()) {
            cmdArgs.add("--no-commit");
        }

        if (args.has("no_edit") && args.get("no_edit").getAsBoolean()) {
            cmdArgs.add("--no-edit");
        }

        cmdArgs.add(args.get("commit").getAsString());

        return git.runGit(cmdArgs.toArray(String[]::new));
    }
}
