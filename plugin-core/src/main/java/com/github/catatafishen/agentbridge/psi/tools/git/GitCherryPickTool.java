package com.github.catatafishen.agentbridge.psi.tools.git;

import com.github.catatafishen.agentbridge.psi.review.AgentEditSession;
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
        return "Apply specific commits from another branch onto the current branch. " +
            "Use no_commit: true to apply changes without committing. If conflicts occur, resolve them and use continue_pick: true.";
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
        JsonObject s = schema(
            Param.optional(PARAM_COMMITS, TYPE_ARRAY, "One or more commit SHAs to cherry-pick"),
            Param.optional(PARAM_NO_COMMIT, TYPE_BOOLEAN, "Apply changes without creating commits"),
            Param.optional(PARAM_ABORT, TYPE_BOOLEAN, "Abort an in-progress cherry-pick"),
            Param.optional(PARAM_CONTINUE_PICK, TYPE_BOOLEAN, "Continue cherry-pick after resolving conflicts"),
            Param.optional(PARAM_REPO, TYPE_STRING, REPO_PARAM_DESCRIPTION)
        );
        addArrayItems(s, PARAM_COMMITS);
        return s;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        flushAndSave();

        String repoParam = args.has(PARAM_REPO) ? args.get(PARAM_REPO).getAsString() : null;
        String ambiError = requireUnambiguousRepo(repoParam, "git_cherry_pick");
        if (ambiError != null) return ambiError;
        String root = resolveRepoRootOrError(repoParam);
        if (root.startsWith("Error")) return root;

        if (args.has(PARAM_ABORT) && args.get(PARAM_ABORT).getAsBoolean()) {
            return runGitIn(root, CHERRY_PICK, "--abort");
        }

        if (args.has(PARAM_CONTINUE_PICK) && args.get(PARAM_CONTINUE_PICK).getAsBoolean()) {
            // --no-edit prevents git from opening $EDITOR for the commit message.
            return runGitIn(root, CHERRY_PICK, "--continue", "--no-edit");
        }

        if (!args.has(PARAM_COMMITS) || !args.get(PARAM_COMMITS).isJsonArray()) {
            return "Error: 'commits' parameter is required (JSON array of commit SHAs)";
        }

        String reviewError = AgentEditSession.getInstance(project)
            .awaitReviewCompletion("git cherry-pick");
        if (reviewError != null) return reviewError;

        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add(CHERRY_PICK);

        if (args.has(PARAM_NO_COMMIT) && args.get(PARAM_NO_COMMIT).getAsBoolean()) {
            cmdArgs.add("--no-commit");
        }

        var commits = args.getAsJsonArray(PARAM_COMMITS);
        for (var commit : commits) {
            cmdArgs.add(commit.getAsString());
        }

        String result = runGitIn(root, cmdArgs.toArray(String[]::new));
        if (!result.startsWith("Error")) {
            AgentEditSession.getInstance(project).invalidateOnWorktreeChange("cherry-pick");
        }
        return result;
    }
}
