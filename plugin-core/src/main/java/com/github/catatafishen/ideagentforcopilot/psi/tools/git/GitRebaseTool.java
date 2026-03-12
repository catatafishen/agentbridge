package com.github.catatafishen.ideagentforcopilot.psi.tools.git;

import com.github.catatafishen.ideagentforcopilot.psi.GitToolHandler;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Rebases the current branch onto another.
 */
@SuppressWarnings("java:S112")
public final class GitRebaseTool extends GitTool {

    public GitRebaseTool(Project project, GitToolHandler git) {
        super(project, git);
    }

    @Override
    public @NotNull String id() {
        return "git_rebase";
    }

    @Override
    public @NotNull String displayName() {
        return "Git Rebase";
    }

    @Override
    public @NotNull String description() {
        return "Rebase current branch onto another";
    }

    @Override
    public boolean isDestructive() {
        return true;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Rebase onto {branch}";
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"branch", TYPE_STRING, "Branch to rebase onto"},
            {"onto", TYPE_STRING, "Rebase onto a specific commit (used with --onto)"},
            {"interactive", TYPE_BOOLEAN, "Start an interactive rebase"},
            {"autosquash", TYPE_BOOLEAN, "Automatically squash fixup! and squash! commits (requires interactive)"},
            {"abort", TYPE_BOOLEAN, "Abort an in-progress rebase"},
            {"continue_rebase", TYPE_BOOLEAN, "Continue a paused rebase after resolving conflicts"},
            {"skip", TYPE_BOOLEAN, "Skip the current patch and continue rebase"}
        });
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        git.saveAllDocuments();

        if (args.has("abort") && args.get("abort").getAsBoolean()) {
            return git.runGit("rebase", "--abort");
        }

        if (args.has("continue_rebase") && args.get("continue_rebase").getAsBoolean()) {
            return git.runGit("rebase", "--continue");
        }

        if (args.has("skip") && args.get("skip").getAsBoolean()) {
            return git.runGit("rebase", "--skip");
        }

        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add("rebase");

        if (args.has("interactive") && args.get("interactive").getAsBoolean()) {
            cmdArgs.add("--interactive");
        }

        if (args.has("autosquash") && args.get("autosquash").getAsBoolean()) {
            cmdArgs.add("--autosquash");
        }

        if (args.has("onto") && !args.get("onto").getAsString().isEmpty()) {
            cmdArgs.add("--onto");
            cmdArgs.add(args.get("onto").getAsString());
        }

        if (args.has("branch") && !args.get("branch").getAsString().isEmpty()) {
            cmdArgs.add(args.get("branch").getAsString());
        }

        return git.runGit(cmdArgs.toArray(String[]::new));
    }
}
