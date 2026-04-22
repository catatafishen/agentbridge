package com.github.catatafishen.agentbridge.psi.tools.git;

import com.github.catatafishen.agentbridge.psi.review.AgentEditSession;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("java:S112")
public final class GitRevertTool extends GitTool {

    private static final String PARAM_COMMIT = "commit";
    private static final String PARAM_NO_COMMIT = "no_commit";
    private static final String PARAM_NO_EDIT = "no_edit";

    public GitRevertTool(Project project) {
        super(project);
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
        return "Revert a commit by creating a new inverse commit. Does not delete history — safe for shared branches. " +
            "Use no_commit: true to stage the revert without committing.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required(PARAM_COMMIT, TYPE_STRING, "Commit SHA to revert"),
            Param.optional(PARAM_NO_COMMIT, TYPE_BOOLEAN, "If true, revert changes to working tree without creating a commit"),
            Param.optional(PARAM_NO_EDIT, TYPE_BOOLEAN, "If true, use the default commit message without editing"),
            Param.optional(PARAM_REPO, TYPE_STRING, REPO_PARAM_DESCRIPTION)
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        if (!args.has(PARAM_COMMIT) || args.get(PARAM_COMMIT).getAsString().isEmpty()) {
            return "Error: 'commit' parameter is required";
        }

        String repoParam = args.has(PARAM_REPO) ? args.get(PARAM_REPO).getAsString() : null;
        String ambiError = requireUnambiguousRepo(repoParam, "git_revert");
        if (ambiError != null) return ambiError;
        String root = resolveRepoRootOrError(repoParam);
        if (root.startsWith("Error")) return root;

        String reviewError = AgentEditSession.getInstance(project)
            .awaitReviewCompletion("git revert");
        if (reviewError != null) return reviewError;

        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add("revert");

        if (args.has(PARAM_NO_COMMIT) && args.get(PARAM_NO_COMMIT).getAsBoolean()) {
            cmdArgs.add("--no-commit");
        }

        // Default to --no-edit: git revert without it tries to open $EDITOR which hangs.
        // Only skip it when the caller explicitly passes no_edit: false.
        boolean wantsEditor = args.has(PARAM_NO_EDIT) && !args.get(PARAM_NO_EDIT).getAsBoolean();
        if (!wantsEditor) {
            cmdArgs.add("--no-edit");
        }

        cmdArgs.add(args.get(PARAM_COMMIT).getAsString());

        String result = runGitIn(root, cmdArgs.toArray(String[]::new));
        if (!result.startsWith("Error")) {
            AgentEditSession.getInstance(project).invalidateOnWorktreeChange("git revert");
        }
        return result;
    }
}
