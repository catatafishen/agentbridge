package com.github.catatafishen.agentbridge.psi.tools.git;

import com.github.catatafishen.agentbridge.services.PermissionTemplateUtil;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("java:S112")
public final class GitPushTool extends GitTool {

    private static final String PARAM_REMOTE = "remote";
    private static final String PARAM_BRANCH = "branch";
    private static final String PARAM_FORCE = "force";
    private static final String PARAM_SET_UPSTREAM = "set_upstream";

    public GitPushTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "git_push";
    }

    @Override
    public @NotNull String displayName() {
        return "Git Push";
    }

    @Override
    public @NotNull String description() {
        return "Push commits to a remote repository. Auto-fetches from origin before pushing "
            + "to detect divergence. Returns push result with remote URL, branch tracking status, "
            + "and a hint to create a PR if pushing a feature branch.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EXECUTE;
    }

    @Override
    public boolean isDestructive() {
        return true;
    }

    @Override
    public boolean isOpenWorld() {
        return true;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Push to {remote} ({branch})";
    }

    @Override
    public @Nullable String resolvePermissionQuestion(@Nullable JsonObject args) {
        JsonObject enriched = args != null ? args.deepCopy() : new JsonObject();
        if (!enriched.has(PARAM_REMOTE)) {
            enriched.addProperty(PARAM_REMOTE, "origin");
        }
        if (!enriched.has(PARAM_BRANCH)) {
            String branch = detectCurrentBranch();
            enriched.addProperty(PARAM_BRANCH, branch != null ? branch : "current branch");
        }
        String resolved = PermissionTemplateUtil.substituteArgs(permissionTemplate(), enriched);
        return PermissionTemplateUtil.stripPlaceholders(resolved);
    }

    @Nullable
    private String detectCurrentBranch() {
        try {
            return runGit("rev-parse", "--abbrev-ref", "HEAD").trim();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.optional(PARAM_REMOTE, TYPE_STRING, "Remote name (default: origin)"),
            Param.optional(PARAM_BRANCH, TYPE_STRING, "Branch to push (default: current)"),
            Param.optional(PARAM_FORCE, TYPE_BOOLEAN, "Force push"),
            Param.optional(PARAM_SET_UPSTREAM, TYPE_BOOLEAN, "Set upstream tracking reference"),
            Param.optional("tags", TYPE_BOOLEAN, "Push all tags"),
            Param.optional(PARAM_REPO, TYPE_STRING, REPO_PARAM_DESCRIPTION)
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String repoParam = args.has(PARAM_REPO) ? args.get(PARAM_REPO).getAsString() : null;
        String ambiError = requireUnambiguousRepo(repoParam, "git_push");
        if (ambiError != null) return ambiError;
        String root = resolveRepoRootOrError(repoParam);
        if (root.startsWith("Error")) return root;

        boolean forceFlag = args.has(PARAM_FORCE) && args.get(PARAM_FORCE).getAsBoolean();

        // Auto-fetch to detect remote divergence before pushing
        String fetchNote = autoFetchIfStaleIn(root);

        // Pre-push divergence check (skip for force-push)
        String divergenceWarning = "";
        if (!forceFlag) {
            String behind = runGitInQuiet(root, "rev-list", "--count", "HEAD..@{upstream}");
            if (behind != null && !"0".equals(behind)) {
                divergenceWarning = "\n⚠️ Remote is " + behind
                    + " commit(s) ahead of local. Consider pulling first, or use force: true.";
            }
        }

        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add("push");

        boolean setUpstream = args.has(PARAM_SET_UPSTREAM) && args.get(PARAM_SET_UPSTREAM).getAsBoolean();

        if (forceFlag) {
            cmdArgs.add("--force");
        }
        if (setUpstream) {
            cmdArgs.add("--set-upstream");
        }

        String remote = args.has(PARAM_REMOTE) ? args.get(PARAM_REMOTE).getAsString() : null;
        String branch = args.has(PARAM_BRANCH) ? args.get(PARAM_BRANCH).getAsString() : null;

        if (setUpstream) {
            if (remote == null) {
                remote = "origin";
            }
            if (branch == null) {
                branch = runGitIn(root, "rev-parse", "--abbrev-ref", "HEAD").trim();
            }
        }

        if (remote != null) {
            cmdArgs.add(remote);
        }
        if (branch != null) {
            cmdArgs.add(branch);
        }
        if (args.has("tags") && args.get("tags").getAsBoolean()) {
            cmdArgs.add("--tags");
        }

        String result = runGitIn(root, cmdArgs.toArray(String[]::new));

        if (result.startsWith("Error")) return fetchNote + result + divergenceWarning;

        // Append context
        StringBuilder ctx = new StringBuilder(result);
        if (!fetchNote.isEmpty()) ctx.insert(0, fetchNote);
        if (!divergenceWarning.isEmpty()) ctx.append(divergenceWarning);

        ctx.append("\n\n--- Context ---\n");
        String actualBranch = branch != null ? branch
            : runGitInQuiet(root, "rev-parse", "--abbrev-ref", "HEAD");
        String actualRemote = remote != null ? remote : "origin";
        ctx.append("Pushed ").append(actualBranch).append(" → ")
            .append(actualRemote).append('/').append(actualBranch).append('\n');

        String remoteUrl = runGitInQuiet(root, "remote", "get-url", actualRemote);
        if (remoteUrl != null) {
            ctx.append("Remote: ").append(remoteUrl).append('\n');
        }

        if (actualBranch != null && !"main".equals(actualBranch) && !"master".equals(actualBranch)) {
            ctx.append("Tip: create a PR with `gh pr create` if ready for review\n");
        }

        return ctx.toString();
    }
}
