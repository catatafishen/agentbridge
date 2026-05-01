package com.github.catatafishen.agentbridge.psi.tools.git;

import com.github.catatafishen.agentbridge.psi.review.AgentEditSession;
import com.github.catatafishen.agentbridge.ui.renderers.GitStashRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("java:S112")
public final class GitStashTool extends GitTool {

    private static final String CMD_STASH = "stash";
    private static final String PARAM_ACTION = "action";
    private static final String PARAM_MESSAGE = "message";
    private static final String PARAM_INDEX = "index";
    private static final String PARAM_INCLUDE_UNTRACKED = "include_untracked";
    private static final String ACTION_APPLY = "apply";

    public GitStashTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "git_stash";
    }

    @Override
    public @NotNull String displayName() {
        return "Git Stash";
    }

    @Override
    public @NotNull String description() {
        return "Push, pop, apply, list, or drop stashed changes. Stash saves uncommitted work temporarily without committing. " +
            "Use include_untracked: true to also stash new files.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "{action} stash";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.optional(PARAM_ACTION, TYPE_STRING, "Action: 'list' (default), 'push', 'pop', 'apply', 'drop'"),
            Param.optional(PARAM_MESSAGE, TYPE_STRING, "Stash message (for push action)"),
            Param.optional(PARAM_INDEX, TYPE_STRING, "Stash index (for pop/apply/drop, e.g., 'stash@{0}')"),
            Param.optional(PARAM_INCLUDE_UNTRACKED, TYPE_BOOLEAN, "For push: include untracked files"),
            Param.optional(PARAM_REPO, TYPE_STRING, REPO_PARAM_DESCRIPTION)
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String repoParam = args.has(PARAM_REPO) ? args.get(PARAM_REPO).getAsString() : null;
        String root = resolveRepoRootOrError(repoParam);
        if (root.startsWith("Error")) return root;

        String action = args.has(PARAM_ACTION)
            ? args.get(PARAM_ACTION).getAsString()
            : "list";

        return switch (action) {
            case "list" -> runGitIn(root, CMD_STASH, "list");
            case "push", "save" -> pushStash(args, repoParam, root);
            case "pop" -> applyOrPopStash(args, repoParam, root, "pop");
            case ACTION_APPLY -> applyOrPopStash(args, repoParam, root, ACTION_APPLY);
            case "drop" -> dropStash(args, repoParam, root);
            default -> "Error: unknown action '" + action + "'. Use: list, push, pop, apply, drop";
        };
    }

    private String pushStash(@NotNull JsonObject args, @Nullable String repoParam, @NotNull String root) throws Exception {
        String ambiError = requireUnambiguousRepo(repoParam, "git_stash push");
        if (ambiError != null) return ambiError;
        return runGitIn(root, pushCommandArgs(args));
    }

    private static String[] pushCommandArgs(@NotNull JsonObject args) {
        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add(CMD_STASH);
        cmdArgs.add("push");
        addMessage(args, cmdArgs);
        if (args.has(PARAM_INCLUDE_UNTRACKED) && args.get(PARAM_INCLUDE_UNTRACKED).getAsBoolean()) {
            cmdArgs.add("--include-untracked");
        }
        return cmdArgs.toArray(String[]::new);
    }

    private static void addMessage(@NotNull JsonObject args, @NotNull List<String> cmdArgs) {
        if (args.has(PARAM_MESSAGE) && !args.get(PARAM_MESSAGE).getAsString().isEmpty()) {
            cmdArgs.add("-m");
            cmdArgs.add(args.get(PARAM_MESSAGE).getAsString());
        }
    }

    private String applyOrPopStash(
        @NotNull JsonObject args,
        @Nullable String repoParam,
        @NotNull String root,
        @NotNull String action
    ) throws Exception {
        String ambiError = requireUnambiguousRepo(repoParam, "git_stash " + action);
        if (ambiError != null) return ambiError;
        String reviewError = AgentEditSession.getInstance(project).awaitReviewCompletion("stash " + action);
        if (reviewError != null) return reviewError;

        String result = runStashWithOptionalRef(args, root, action);
        AgentEditSession.getInstance(project).invalidateOnWorktreeChange("stash " + action);
        return result;
    }

    private String dropStash(@NotNull JsonObject args, @Nullable String repoParam, @NotNull String root) throws Exception {
        String ambiError = requireUnambiguousRepo(repoParam, "git_stash drop");
        if (ambiError != null) return ambiError;
        return runStashWithOptionalRef(args, root, "drop");
    }

    private String runStashWithOptionalRef(
        @NotNull JsonObject args,
        @NotNull String root,
        @NotNull String action
    ) throws Exception {
        String index = stashRef(args);
        return index != null
            ? runGitIn(root, CMD_STASH, action, index)
            : runGitIn(root, CMD_STASH, action);
    }

    private static @Nullable String stashRef(JsonObject args) {
        if (args.has(PARAM_INDEX) && !args.get(PARAM_INDEX).getAsString().isEmpty()) {
            String idx = args.get(PARAM_INDEX).getAsString();
            // Accept both "0" (numeric index) and "stash@{0}" (full ref) without double-wrapping.
            return idx.startsWith("stash@{") ? idx : "stash@{" + idx + "}";
        }
        return null;
    }

    @Override
    public @NotNull Object resultRenderer() {
        return GitStashRenderer.INSTANCE;
    }
}
