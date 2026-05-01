package com.github.catatafishen.agentbridge.psi.tools.git;

import com.github.catatafishen.agentbridge.psi.review.AgentEditSession;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Resets HEAD to a specific commit.
 */
@SuppressWarnings("java:S112")
public final class GitResetTool extends GitTool {

    private static final String PARAM_COMMIT = "commit";
    private static final String PARAM_MODE = "mode";
    private static final String MODE_MIXED = "mixed";

    public GitResetTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "git_reset";
    }

    @Override
    public @NotNull String displayName() {
        return "Git Reset";
    }

    @Override
    public @NotNull String description() {
        return "Reset HEAD to a specific commit. Modes: 'soft' keeps changes staged, "
            + "'mixed' (default) unstages changes, 'hard' discards all changes. "
            + "Can also reset a specific file path (unstages it).";
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
    public @NotNull String permissionTemplate() {
        return "{mode} reset to {commit}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.optional(PARAM_COMMIT, TYPE_STRING, "Target commit (default: HEAD)"),
            Param.optional(PARAM_MODE, TYPE_STRING, "Reset mode: 'soft' (keep staged), 'mixed' (default, unstage), 'hard' (discard all changes)"),
            Param.optional("path", TYPE_STRING, "Reset a specific file path (unstages it)"),
            Param.optional(PARAM_REPO, TYPE_STRING, REPO_PARAM_DESCRIPTION)
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        flushAndSave();

        String repoParam = args.has(PARAM_REPO) ? args.get(PARAM_REPO).getAsString() : null;
        String root = validateAndResolveRoot(repoParam);
        if (root.startsWith("Error")) return root;

        boolean hardReset = isHardModeReset(args);
        String reviewError = awaitHardResetReviewIfNeeded(hardReset);
        if (reviewError != null) return reviewError;

        List<String> cmdArgs = buildResetArgs(args);
        String result = runGitIn(root, cmdArgs.toArray(String[]::new));
        if (!result.isBlank() && result.startsWith("Error")) return result;
        invalidateAfterHardReset(hardReset);
        return result.isBlank() ? "Reset completed successfully." : result;
    }

    private String validateAndResolveRoot(String repoParam) {
        String ambiError = requireUnambiguousRepo(repoParam, "git_reset");
        if (ambiError != null) return ambiError;
        return resolveRepoRootOrError(repoParam);
    }

    private List<String> buildResetArgs(JsonObject args) {
        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add("reset");
        if (hasPath(args)) {
            addFilePathResetArgs(cmdArgs, args);
        } else {
            addModeResetArgs(cmdArgs, args);
        }
        return cmdArgs;
    }

    private boolean hasPath(JsonObject args) {
        return args.has("path") && !args.get("path").getAsString().isEmpty();
    }

    private boolean isHardModeReset(JsonObject args) {
        return !hasPath(args) && "hard".equals(getMode(args));
    }

    private String getMode(JsonObject args) {
        return args.has(PARAM_MODE) ? args.get(PARAM_MODE).getAsString() : MODE_MIXED;
    }

    private String awaitHardResetReviewIfNeeded(boolean hardReset) {
        if (!hardReset) return null;
        return AgentEditSession.getInstance(project).awaitReviewCompletion("git reset --hard");
    }

    private void invalidateAfterHardReset(boolean hardReset) {
        if (hardReset) {
            AgentEditSession.getInstance(project).invalidateOnWorktreeChange("git reset --hard");
        }
    }

    private void addFilePathResetArgs(List<String> cmdArgs, JsonObject args) {
        String commit = args.has(PARAM_COMMIT) ? args.get(PARAM_COMMIT).getAsString() : null;
        if (commit != null && !commit.isEmpty()) {
            cmdArgs.add(commit);
        }
        cmdArgs.add("--");
        cmdArgs.add(args.get("path").getAsString());
    }

    private void addModeResetArgs(List<String> cmdArgs, JsonObject args) {
        String mode = args.has(PARAM_MODE) ? args.get(PARAM_MODE).getAsString() : MODE_MIXED;
        switch (mode) {
            case "soft" -> cmdArgs.add("--soft");
            case "hard" -> cmdArgs.add("--hard");
            default -> cmdArgs.add("--" + MODE_MIXED);
        }
        if (args.has(PARAM_COMMIT) && !args.get(PARAM_COMMIT).getAsString().isEmpty()) {
            cmdArgs.add(args.get(PARAM_COMMIT).getAsString());
        }
    }
}
