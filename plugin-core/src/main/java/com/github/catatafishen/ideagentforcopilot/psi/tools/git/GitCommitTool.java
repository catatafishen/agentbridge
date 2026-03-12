package com.github.catatafishen.ideagentforcopilot.psi.tools.git;

import com.github.catatafishen.ideagentforcopilot.ui.renderers.GitCommitRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Commits staged changes with a message.
 */
@SuppressWarnings("java:S112")
public final class GitCommitTool extends GitTool {

    public GitCommitTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "git_commit";
    }

    @Override
    public @NotNull String displayName() {
        return "Git Commit";
    }

    @Override
    public @NotNull String description() {
        return "Commit staged changes with a message";
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Commit: \"{message}\"";
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"message", TYPE_STRING, "Commit message (use conventional commit format)"},
            {"amend", TYPE_BOOLEAN, "If true, amend the previous commit instead of creating a new one"},
            {"all", TYPE_BOOLEAN, "If true, automatically stage all modified and deleted files"}
        }, "message");
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        flushAndSave();

        if (!args.has("message") || args.get("message").getAsString().isEmpty()) {
            return "Error: 'message' parameter is required";
        }

        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add("commit");

        if (args.has("amend") && args.get("amend").getAsBoolean()) {
            cmdArgs.add("--amend");
        }

        if (args.has("all") && args.get("all").getAsBoolean()) {
            cmdArgs.add("--all");
        }

        cmdArgs.add("-m");
        cmdArgs.add(args.get("message").getAsString());

        String result = runGit(cmdArgs.toArray(String[]::new));
        showNewCommitInLog();
        return result;
    }

    @Override
    public @NotNull Object resultRenderer() {
        return GitCommitRenderer.INSTANCE;
    }
}
