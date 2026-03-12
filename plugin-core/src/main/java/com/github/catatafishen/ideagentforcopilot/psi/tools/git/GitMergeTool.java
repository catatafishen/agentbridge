package com.github.catatafishen.ideagentforcopilot.psi.tools.git;

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

    public GitMergeTool(Project project) {
        super(project);
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
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"branch", TYPE_STRING, "Branch to merge into current branch"},
            {"message", TYPE_STRING, "Custom merge commit message"},
            {"no_ff", TYPE_BOOLEAN, "Create a merge commit even for fast-forward merges"},
            {"ff_only", TYPE_BOOLEAN, "Only merge if fast-forward is possible"},
            {"squash", TYPE_BOOLEAN, "Squash all commits into a single commit (requires manual commit after)"},
            {"abort", TYPE_BOOLEAN, "Abort an in-progress merge"}
        });
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        boolean hasAbort = args.has("abort") && args.get("abort").getAsBoolean();
        boolean hasBranch = args.has("branch") && !args.get("branch").getAsString().isEmpty();

        if (!hasBranch && !hasAbort) {
            return "Error: 'branch' parameter is required (or use 'abort' to abort an in-progress merge)";
        }

        flushAndSave();

        if (hasAbort) {
            return runGit("merge", "--abort");
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

        return runGit(cmdArgs.toArray(String[]::new));
    }
}
