package com.github.catatafishen.ideagentforcopilot.psi.tools.git;

import com.github.catatafishen.ideagentforcopilot.ui.renderers.GitBlameRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("java:S112")
public final class GitBlameTool extends GitTool {

    private static final String PARAM_LINE_START = "line_start";
    private static final String PARAM_LINE_END = "line_end";

    public GitBlameTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "git_blame";
    }

    @Override
    public @NotNull String displayName() {
        return "Git Blame";
    }

    @Override
    public @NotNull String description() {
        return "Show per-line authorship for a file";
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
            {"path", TYPE_STRING, "File path to blame"},
            {PARAM_LINE_START, TYPE_INTEGER, "Start line number for partial blame"},
            {PARAM_LINE_END, TYPE_INTEGER, "End line number for partial blame"}
        }, "path");
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        if (!args.has("path") || args.get("path").getAsString().isEmpty()) {
            return "Error: 'path' parameter is required";
        }
        String path = args.get("path").getAsString();

        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add("blame");

        if (args.has(PARAM_LINE_START) && args.has(PARAM_LINE_END)) {
            int lineStart = args.get(PARAM_LINE_START).getAsInt();
            int lineEnd = args.get(PARAM_LINE_END).getAsInt();
            cmdArgs.add("-L");
            cmdArgs.add(lineStart + "," + lineEnd);
        }

        cmdArgs.add("--");
        cmdArgs.add(path);

        return runGit(cmdArgs.toArray(String[]::new));
    }

    @Override
    public @NotNull Object resultRenderer() {
        return GitBlameRenderer.INSTANCE;
    }
}
