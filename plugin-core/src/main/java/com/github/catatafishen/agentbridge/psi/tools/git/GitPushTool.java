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
    private static final String GIT_REV_PARSE = "rev-parse";
    private static final String ABBREV_REF = "--abbrev-ref";
    private static final String DEFAULT_REMOTE = "origin";

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
            + "to detect divergence. Returns push result with remote URL and branch tracking status.";
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
            enriched.addProperty(PARAM_REMOTE, DEFAULT_REMOTE);
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
            return runGit(GIT_REV_PARSE, ABBREV_REF, "HEAD").trim();
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
        String root = preparePush(repoParam);
        if (root.startsWith("Error")) return root;

        boolean forceFlag = args.has(PARAM_FORCE) && args.get(PARAM_FORCE).getAsBoolean();
        String fetchNote = autoFetchIfStaleIn(root);
        String divergenceWarning = divergenceWarning(root, forceFlag);
        PushTarget target = resolvePushTarget(args, root);

        String result = runGitIn(root, pushCommandArgs(args, forceFlag, target));
        if (result.startsWith("Error")) return fetchNote + result + divergenceWarning;
        return buildPushResponse(result, fetchNote, divergenceWarning, target, root);
    }

    private String preparePush(@Nullable String repoParam) {
        String ambiError = requireUnambiguousRepo(repoParam, "git_push");
        if (ambiError != null) return ambiError;
        return resolveRepoRootOrError(repoParam);
    }

    private String divergenceWarning(@NotNull String root, boolean forceFlag) {
        if (forceFlag) return "";
        String behind = runGitInQuiet(root, "rev-list", "--count", "HEAD..@{upstream}");
        if (behind == null || "0".equals(behind)) return "";
        return "\n⚠️ Remote is " + behind
            + " commit(s) ahead of local. Consider pulling first, or use force: true.";
    }

    private PushTarget resolvePushTarget(@NotNull JsonObject args, @NotNull String root) throws Exception {
        String remote = args.has(PARAM_REMOTE) ? args.get(PARAM_REMOTE).getAsString() : null;
        String branch = args.has(PARAM_BRANCH) ? args.get(PARAM_BRANCH).getAsString() : null;
        if (!hasFlag(args, PARAM_SET_UPSTREAM)) return new PushTarget(remote, branch);
        return new PushTarget(
            remote != null ? remote : DEFAULT_REMOTE,
            branch != null ? branch : runGitIn(root, GIT_REV_PARSE, ABBREV_REF, "HEAD").trim()
        );
    }

    private static String[] pushCommandArgs(@NotNull JsonObject args, boolean forceFlag, @NotNull PushTarget target) {
        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add("push");
        if (forceFlag) cmdArgs.add("--force");
        if (hasFlag(args, PARAM_SET_UPSTREAM)) cmdArgs.add("--set-upstream");
        if (target.remote() != null) cmdArgs.add(target.remote());
        if (target.branch() != null) cmdArgs.add(target.branch());
        if (hasFlag(args, "tags")) cmdArgs.add("--tags");
        return cmdArgs.toArray(String[]::new);
    }

    private String buildPushResponse(
        @NotNull String result,
        @NotNull String fetchNote,
        @NotNull String divergenceWarning,
        @NotNull PushTarget target,
        @NotNull String root
    ) {
        StringBuilder ctx = new StringBuilder(result);
        if (!fetchNote.isEmpty()) ctx.insert(0, fetchNote);
        if (!divergenceWarning.isEmpty()) ctx.append(divergenceWarning);
        appendPushContext(ctx, target, root);
        return ctx.toString();
    }

    private void appendPushContext(@NotNull StringBuilder ctx, @NotNull PushTarget target, @NotNull String root) {
        String actualBranch = target.branch() != null ? target.branch()
            : runGitInQuiet(root, GIT_REV_PARSE, ABBREV_REF, "HEAD");
        String actualRemote = target.remote() != null ? target.remote() : DEFAULT_REMOTE;
        ctx.append("\n\n--- Context ---\n");
        ctx.append("Pushed ").append(actualBranch).append(" → ")
            .append(actualRemote).append('/').append(actualBranch).append('\n');

        String remoteUrl = runGitInQuiet(root, PARAM_REMOTE, "get-url", actualRemote);
        if (remoteUrl != null) ctx.append("Remote: ").append(remoteUrl).append('\n');
    }

    private static boolean hasFlag(@NotNull JsonObject args, @NotNull String parameter) {
        return args.has(parameter) && args.get(parameter).getAsBoolean();
    }

    private record PushTarget(@Nullable String remote, @Nullable String branch) {
    }
}
