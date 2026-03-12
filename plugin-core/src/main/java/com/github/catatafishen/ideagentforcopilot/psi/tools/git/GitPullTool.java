package com.github.catatafishen.ideagentforcopilot.psi.tools.git;

import com.github.catatafishen.ideagentforcopilot.psi.GitToolHandler;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Fetches from a remote and integrates changes into the current branch.
 */
@SuppressWarnings("java:S112")
public final class GitPullTool extends GitTool {

    public GitPullTool(Project project, GitToolHandler git) {
        super(project, git);
    }

    @Override
    public @NotNull String id() {
        return "git_pull";
    }

    @Override
    public @NotNull String displayName() {
        return "Git Pull";
    }

    @Override
    public @NotNull String description() {
        return "Fetch and integrate changes into the current branch";
    }

    @Override
    public boolean isOpenWorld() {
        return true;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Pull {remote}/{branch}";
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        git.saveAllDocuments();

        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add("pull");

        if (args.has("rebase") && args.get("rebase").getAsBoolean()) {
            cmdArgs.add("--rebase");
        }

        if (args.has("ff_only") && args.get("ff_only").getAsBoolean()) {
            cmdArgs.add("--ff-only");
        }

        if (args.has("remote") && !args.get("remote").getAsString().isEmpty()) {
            cmdArgs.add(args.get("remote").getAsString());
        }

        if (args.has("branch") && !args.get("branch").getAsString().isEmpty()) {
            cmdArgs.add(args.get("branch").getAsString());
        }

        return git.runGit(cmdArgs.toArray(String[]::new));
    }
}
