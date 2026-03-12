package com.github.catatafishen.ideagentforcopilot.psi.tools.git;

import com.github.catatafishen.ideagentforcopilot.psi.GitToolHandler;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Merges a branch into the current branch.
 */
@SuppressWarnings("java:S112")
public final class GitMergeTool extends GitTool {

    public GitMergeTool(Project project, GitToolHandler git) {
        super(project, git);
    }

    @Override
    public @NotNull String id() {
        return "git_merge";
    }

    @Override
    public @NotNull String displayName() {
        return "Git Merge";
    }

    @Override
    public @NotNull String description() {
        return "Merge a branch into the current branch";
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Merge {branch}";
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        boolean hasAbort = args.has("abort") && args.get("abort").getAsBoolean();
        boolean hasBranch = args.has("branch") && !args.get("branch").getAsString().isEmpty();

        if (!hasBranch && !hasAbort) {
            return "Error: 'branch' parameter is required (or use 'abort' to abort an in-progress merge)";
        }

        git.saveAllDocuments();

        if (hasAbort) {
            return git.runGit("merge", "--abort");
        }

        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add("merge");

        if (args.has("no_ff") && args.get("no_ff").getAsBoolean()) {
            cmdArgs.add("--no-ff");
        }

        if (args.has("ff_only") && args.get("ff_only").getAsBoolean()) {
            cmdArgs.add("--ff-only");
        }

        if (args.has("squash") && args.get("squash").getAsBoolean()) {
            cmdArgs.add("--squash");
        }

        if (args.has("message") && !args.get("message").getAsString().isEmpty()) {
            cmdArgs.add("-m");
            cmdArgs.add(args.get("message").getAsString());
        }

        cmdArgs.add(args.get("branch").getAsString());

        return git.runGit(cmdArgs.toArray(String[]::new));
    }
}
