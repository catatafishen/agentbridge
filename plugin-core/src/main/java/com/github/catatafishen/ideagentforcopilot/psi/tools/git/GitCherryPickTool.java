package com.github.catatafishen.ideagentforcopilot.psi.tools.git;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("java:S112")
public final class GitCherryPickTool extends GitTool {

    private static final String CHERRY_PICK = "cherry-pick";
    private static final String PARAM_COMMITS = "commits";
    private static final String PARAM_NO_COMMIT = "no_commit";
    private static final String PARAM_ABORT = "abort";
    private static final String PARAM_CONTINUE_PICK = "continue_pick";

    public GitCherryPickTool(Project project) {
        super(project);
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
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Cherry-pick {commits}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        JsonObject s = schema(new Object[][]{
            {PARAM_COMMITS, TYPE_ARRAY, "One or more commit SHAs to cherry-pick"},
            {PARAM_NO_COMMIT, TYPE_BOOLEAN, "Apply changes without creating commits"},
            {PARAM_ABORT, TYPE_BOOLEAN, "Abort an in-progress cherry-pick"},
            {PARAM_CONTINUE_PICK, TYPE_BOOLEAN, "Continue cherry-pick after resolving conflicts"}
        });
        addArrayItems(s, PARAM_COMMITS);
        return s;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        flushAndSave();

        if (args.has(PARAM_ABORT) && args.get(PARAM_ABORT).getAsBoolean()) {
            return runGit(CHERRY_PICK, "--abort");
        }

        if (args.has(PARAM_CONTINUE_PICK) && args.get(PARAM_CONTINUE_PICK).getAsBoolean()) {
            // --no-edit prevents git from opening $EDITOR for the commit message.
            return runGit(CHERRY_PICK, "--continue", "--no-edit");
        }

        if (!args.has(PARAM_COMMITS) || !args.get(PARAM_COMMITS).isJsonArray()) {
            return "Error: 'commits' parameter is required (JSON array of commit SHAs)";
        }

        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add(CHERRY_PICK);

        if (args.has(PARAM_NO_COMMIT) && args.get(PARAM_NO_COMMIT).getAsBoolean()) {
            cmdArgs.add("--no-commit");
        }

        var commits = args.getAsJsonArray(PARAM_COMMITS);
        for (var commit : commits) {
            cmdArgs.add(commit.getAsString());
        }

        return runGit(cmdArgs.toArray(String[]::new));
    }
}
