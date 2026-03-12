package com.github.catatafishen.ideagentforcopilot.psi.tools.git;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Pushes commits to a remote repository.
 */
@SuppressWarnings("java:S112")
public final class GitPushTool extends GitTool {

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
        return "Push commits to a remote repository";
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
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"remote", TYPE_STRING, "Remote name (default: origin)"},
            {"branch", TYPE_STRING, "Branch to push (default: current)"},
            {"force", TYPE_BOOLEAN, "Force push"},
            {"set_upstream", TYPE_BOOLEAN, "Set upstream tracking reference"},
            {"tags", TYPE_BOOLEAN, "Push all tags"}
        });
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add("push");

        boolean setUpstream = args.has("set_upstream") && args.get("set_upstream").getAsBoolean();

        if (args.has("force") && args.get("force").getAsBoolean()) {
            cmdArgs.add("--force");
        }
        if (setUpstream) {
            cmdArgs.add("--set-upstream");
        }

        String remote = args.has("remote") ? args.get("remote").getAsString() : null;
        String branch = args.has("branch") ? args.get("branch").getAsString() : null;

        if (setUpstream) {
            if (remote == null) {
                remote = "origin";
            }
            if (branch == null) {
                branch = runGit("rev-parse", "--abbrev-ref", "HEAD").trim();
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

        return runGit(cmdArgs.toArray(String[]::new));
    }
}
