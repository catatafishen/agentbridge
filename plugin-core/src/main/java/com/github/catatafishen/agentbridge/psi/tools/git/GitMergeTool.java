package com.github.catatafishen.agentbridge.psi.tools.git;

import com.github.catatafishen.agentbridge.psi.review.AgentEditSession;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Merges a branch into the current branch.
 */
@SuppressWarnings("java:S112")
public final class GitMergeTool extends GitTool {

    private static final String PARAM_BRANCH = "branch";
    private static final String PARAM_MESSAGE = "message";
    private static final String PARAM_NO_FF = "no_ff";
    private static final String PARAM_FF_ONLY = "ff_only";
    private static final String PARAM_SQUASH = "squash";
    private static final String PARAM_ABORT = "abort";

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
        return "Merge a branch into the current branch. Auto-fetches from origin when merging "
            + "a remote branch (origin/*). Returns merge result with branch context.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Merge {branch}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.optional(PARAM_BRANCH, TYPE_STRING, "Branch to merge into current branch"),
            Param.optional(PARAM_MESSAGE, TYPE_STRING, "Custom merge commit message"),
            Param.optional(PARAM_NO_FF, TYPE_BOOLEAN, "Create a merge commit even for fast-forward merges"),
            Param.optional(PARAM_FF_ONLY, TYPE_BOOLEAN, "Only merge if fast-forward is possible"),
            Param.optional(PARAM_SQUASH, TYPE_BOOLEAN, "Squash all commits into a single commit (requires manual commit after)"),
            Param.optional(PARAM_ABORT, TYPE_BOOLEAN, "Abort an in-progress merge"),
            Param.optional(PARAM_REPO, TYPE_STRING, REPO_PARAM_DESCRIPTION)
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        boolean hasAbort = args.has(PARAM_ABORT) && args.get(PARAM_ABORT).getAsBoolean();
        boolean hasBranch = args.has(PARAM_BRANCH) && !args.get(PARAM_BRANCH).getAsString().isEmpty();

        if (!hasBranch && !hasAbort) {
            return "Error: 'branch' parameter is required (or use 'abort' to abort an in-progress merge)";
        }

        String repoParam = args.has(PARAM_REPO) ? args.get(PARAM_REPO).getAsString() : null;
        String ambiError = requireUnambiguousRepo(repoParam, "git_merge");
        if (ambiError != null) return ambiError;
        String root = resolveRepoRootOrError(repoParam);
        if (root.startsWith("Error")) return root;

        flushAndSave();

        if (hasAbort) {
            return runGitIn(root, "merge", "--abort");
        }

        String branchArg = args.get(PARAM_BRANCH).getAsString();

        // Auto-fetch when merging a remote branch
        String fetchNote = autoFetchForRemoteRefIn(branchArg, root);

        String reviewError = AgentEditSession.getInstance(project)
            .awaitReviewCompletion("git merge '" + branchArg + "'");
        if (reviewError != null) return reviewError;

        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add("merge");

        if (args.has(PARAM_NO_FF) && args.get(PARAM_NO_FF).getAsBoolean()) {
            cmdArgs.add("--no-ff");
        }

        if (args.has(PARAM_FF_ONLY) && args.get(PARAM_FF_ONLY).getAsBoolean()) {
            cmdArgs.add("--ff-only");
        }

        if (args.has(PARAM_SQUASH) && args.get(PARAM_SQUASH).getAsBoolean()) {
            cmdArgs.add("--squash");
        }

        if (args.has(PARAM_MESSAGE) && !args.get(PARAM_MESSAGE).getAsString().isEmpty()) {
            cmdArgs.add("-m");
            cmdArgs.add(args.get(PARAM_MESSAGE).getAsString());
        }

        cmdArgs.add(branchArg);

        String result = runGitIn(root, cmdArgs.toArray(String[]::new));
        if (result.startsWith("Error")) return fetchNote + result;

        AgentEditSession.getInstance(project).invalidateOnWorktreeChange("git merge");
        return fetchNote + result + getBranchSummaryIn(root);
    }
}
