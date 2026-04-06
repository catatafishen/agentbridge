package com.github.catatafishen.agentbridge.psi.tools.git;

import com.github.catatafishen.agentbridge.ui.renderers.GitStatusRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

@SuppressWarnings("java:S112")
public final class GitStatusTool extends GitTool {

    private static final String PARAM_VERBOSE = "verbose";

    public GitStatusTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "git_status";
    }

    @Override
    public @NotNull String displayName() {
        return "Git Status";
    }

    @Override
    public @NotNull String description() {
        return "Show working tree status";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.READ;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
            {PARAM_VERBOSE, TYPE_BOOLEAN, "If true, show full 'git status' output including untracked files"}
        });
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        flushAndSave();

        boolean verbose = args.has(PARAM_VERBOSE)
            && args.get(PARAM_VERBOSE).getAsBoolean();

        if (verbose) {
            return runGit("status");
        }
        return runGit("status", "--short", "--branch");
    }

    @Override
    public @NotNull Object resultRenderer() {
        return GitStatusRenderer.INSTANCE;
    }
}
