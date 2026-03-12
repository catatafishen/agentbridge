package com.github.catatafishen.ideagentforcopilot.psi.tools.git;

import com.github.catatafishen.ideagentforcopilot.psi.GitToolHandler;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Resets HEAD to a specific commit.
 */
@SuppressWarnings("java:S112")
public final class GitResetTool extends GitTool {

    public GitResetTool(Project project, GitToolHandler git) {
        super(project, git);
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
        return "Reset HEAD to a specific commit";
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
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"commit", TYPE_STRING, "Target commit (default: HEAD)"},
            {"mode", TYPE_STRING, "Reset mode: 'soft' (keep staged), 'mixed' (default, unstage), 'hard' (discard all changes)"},
            {"path", TYPE_STRING, "Reset a specific file path (unstages it)"}
        });
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        git.saveAllDocuments();

        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add("reset");

        if (args.has("path") && !args.get("path").getAsString().isEmpty()) {
            String commit = args.has("commit") ? args.get("commit").getAsString() : null;
            if (commit != null && !commit.isEmpty()) {
                cmdArgs.add(commit);
            }
            cmdArgs.add("--");
            cmdArgs.add(args.get("path").getAsString());
        } else {
            String mode = args.has("mode") ? args.get("mode").getAsString() : "mixed";
            switch (mode) {
                case "soft" -> cmdArgs.add("--soft");
                case "hard" -> cmdArgs.add("--hard");
                default -> cmdArgs.add("--mixed");
            }

            if (args.has("commit") && !args.get("commit").getAsString().isEmpty()) {
                cmdArgs.add(args.get("commit").getAsString());
            }
        }

        String result = git.runGit(cmdArgs.toArray(String[]::new));
        return result.isBlank() ? "Reset completed successfully." : result;
    }
}
