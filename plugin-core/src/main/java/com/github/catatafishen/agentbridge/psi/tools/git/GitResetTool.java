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
            Param.optional("mode", TYPE_STRING, "Reset mode: 'soft' (keep staged), 'mixed' (default, unstage), 'hard' (discard all changes)"),
            Param.optional("path", TYPE_STRING, "Reset a specific file path (unstages it)")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        flushAndSave();
        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add("reset");

        if (args.has("path") && !args.get("path").getAsString().isEmpty()) {
            addFilePathResetArgs(cmdArgs, args);
        } else {
            addModeResetArgs(cmdArgs, args);
        }

        String result = runGit(cmdArgs.toArray(String[]::new));
        AgentEditSession.getInstance(project).invalidateOnWorktreeChange("git reset");
        return result.isBlank() ? "Reset completed successfully." : result;
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
        String mode = args.has("mode") ? args.get("mode").getAsString() : "mixed";
        switch (mode) {
            case "soft" -> cmdArgs.add("--soft");
            case "hard" -> cmdArgs.add("--hard");
            default -> cmdArgs.add("--mixed");
        }
        if (args.has(PARAM_COMMIT) && !args.get(PARAM_COMMIT).getAsString().isEmpty()) {
            cmdArgs.add(args.get(PARAM_COMMIT).getAsString());
        }
    }
}
