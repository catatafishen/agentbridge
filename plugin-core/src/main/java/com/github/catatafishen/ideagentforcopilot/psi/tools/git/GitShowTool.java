package com.github.catatafishen.ideagentforcopilot.psi.tools.git;

import com.github.catatafishen.ideagentforcopilot.ui.renderers.GitShowRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("java:S112")
public final class GitShowTool extends GitTool {

    private static final String PARAM_STAT_ONLY = "stat_only";

    public GitShowTool(Project project) {
        super(project);
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
            {"ref", TYPE_STRING, "Commit SHA, branch, tag, or ref (default: HEAD)"},
            {PARAM_STAT_ONLY, TYPE_BOOLEAN, "If true, show only file stats, not full diff content"},
            {"path", TYPE_STRING, "Limit output to this file path"}
        });
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add("show");

        String ref = args.has("ref") && !args.get("ref").getAsString().isEmpty()
            ? args.get("ref").getAsString()
            : "HEAD";
        cmdArgs.add(ref);

        boolean statOnly = args.has(PARAM_STAT_ONLY)
            && args.get(PARAM_STAT_ONLY).getAsBoolean();
        if (statOnly) {
            cmdArgs.add("--stat");
        }

        if (args.has("path") && !args.get("path").getAsString().isEmpty()) {
            cmdArgs.add("--");
            cmdArgs.add(args.get("path").getAsString());
        }

        String result = runGit(cmdArgs.toArray(String[]::new));
        showFirstCommitInLog(result);
        return result;
    }

    @Override
    public @NotNull Object resultRenderer() {
        return GitShowRenderer.INSTANCE;
    }
}
