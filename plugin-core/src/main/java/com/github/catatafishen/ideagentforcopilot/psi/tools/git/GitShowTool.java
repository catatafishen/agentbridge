package com.github.catatafishen.ideagentforcopilot.psi.tools.git;

import com.github.catatafishen.ideagentforcopilot.psi.GitToolHandler;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows details and diff for a specific commit.
 */
@SuppressWarnings("java:S112")
public final class GitShowTool extends GitTool {

    public GitShowTool(Project project, GitToolHandler git) {
        super(project, git);
    }

    @Override
    public @NotNull String id() {
        return "git_show";
    }

    @Override
    public @NotNull String displayName() {
        return "Git Show";
    }

    @Override
    public @NotNull String description() {
        return "Show details and diff for a specific commit";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"ref", TYPE_STRING, "Commit SHA, branch, tag, or ref (default: HEAD)"},
            {"stat_only", TYPE_BOOLEAN, "If true, show only file stats, not full diff content"},
            {"path", TYPE_STRING, "Limit output to this file path"}
        });
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add("show");

        String ref = args.has("ref") && !args.get("ref").getAsString().isEmpty()
            ? args.get("ref").getAsString()
            : "HEAD";
        cmdArgs.add(ref);

        boolean statOnly = args.has("stat_only")
            && args.get("stat_only").getAsBoolean();
        if (statOnly) {
            cmdArgs.add("--stat");
        }

        if (args.has("path") && !args.get("path").getAsString().isEmpty()) {
            cmdArgs.add("--");
            cmdArgs.add(args.get("path").getAsString());
        }

        String result = git.runGit(cmdArgs.toArray(String[]::new));
        git.showFirstCommitInLog(result);
        return result;
    }
}
