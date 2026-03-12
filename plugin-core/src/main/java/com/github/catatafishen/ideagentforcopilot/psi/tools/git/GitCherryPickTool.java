package com.github.catatafishen.ideagentforcopilot.psi.tools.git;

import com.github.catatafishen.ideagentforcopilot.psi.GitToolHandler;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies specific commits from another branch onto the current branch.
 */
@SuppressWarnings("java:S112")
public final class GitCherryPickTool extends GitTool {

    private static final String CHERRY_PICK = "cherry-pick";
    private static final String PARAM_COMMITS = "commits";

    public GitCherryPickTool(Project project, GitToolHandler git) {
        super(project, git);
    }

    @Override
    public @NotNull String id() {
        return "git_cherry_pick";
    }

    @Override
    public @NotNull String displayName() {
        return "Git Cherry Pick";
    }

    @Override
    public @NotNull String description() {
        return "Apply specific commits from another branch";
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Cherry-pick {commits}";
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        JsonObject s = schema(new Object[][]{
            {"commits", TYPE_ARRAY, "One or more commit SHAs to cherry-pick"},
            {"no_commit", TYPE_BOOLEAN, "Apply changes without creating commits"},
            {"abort", TYPE_BOOLEAN, "Abort an in-progress cherry-pick"},
            {"continue_pick", TYPE_BOOLEAN, "Continue cherry-pick after resolving conflicts"}
        });
        addArrayItems(s, "commits");
        return s;
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        git.saveAllDocuments();

        if (args.has("abort") && args.get("abort").getAsBoolean()) {
            return git.runGit(CHERRY_PICK, "--abort");
        }

        if (args.has("continue_pick") && args.get("continue_pick").getAsBoolean()) {
            return git.runGit(CHERRY_PICK, "--continue");
        }

        if (!args.has(PARAM_COMMITS) || !args.get(PARAM_COMMITS).isJsonArray()) {
            return "Error: 'commits' parameter is required (JSON array of commit SHAs)";
        }

        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add(CHERRY_PICK);

        if (args.has("no_commit") && args.get("no_commit").getAsBoolean()) {
            cmdArgs.add("--no-commit");
        }

        var commits = args.getAsJsonArray(PARAM_COMMITS);
        for (var commit : commits) {
            cmdArgs.add(commit.getAsString());
        }

        return git.runGit(cmdArgs.toArray(String[]::new));
    }
}
