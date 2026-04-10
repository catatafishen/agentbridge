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
        return "Show working tree status including branch tracking info and stash count. "
            + "Use verbose: true for full output including untracked files.";
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
        return schema(
            Param.optional(PARAM_VERBOSE, TYPE_BOOLEAN, "If true, show full 'git status' output including untracked files")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        flushAndSave();

        boolean verbose = args.has(PARAM_VERBOSE)
            && args.get(PARAM_VERBOSE).getAsBoolean();

        String result;
        if (verbose) {
            result = runGit("status");
        } else {
            result = runGit("status", "--short", "--branch");
        }

        // Append stash count if any
        String stashList = runGitQuiet("stash", "list");
        if (stashList != null && !stashList.isEmpty()) {
            long count = stashList.chars().filter(c -> c == '\n').count();
            if (!stashList.endsWith("\n")) count++;
            if (count > 0) {
                result += "\nStash: " + count + " entr" + (count == 1 ? "y" : "ies");
            }
        }

        return result;
    }

    @Override
    public @NotNull Object resultRenderer() {
        return GitStatusRenderer.INSTANCE;
    }
}
