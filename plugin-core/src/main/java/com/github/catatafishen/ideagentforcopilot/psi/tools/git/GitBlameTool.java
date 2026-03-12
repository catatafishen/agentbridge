package com.github.catatafishen.ideagentforcopilot.psi.tools.git;

import com.github.catatafishen.ideagentforcopilot.psi.GitToolHandler;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows per-line authorship for a file, optionally restricted to a line range.
 */
@SuppressWarnings("java:S112")
public final class GitBlameTool extends GitTool {

    public GitBlameTool(Project project, GitToolHandler git) {
        super(project, git);
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
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"path", TYPE_STRING, "File path to blame"},
            {"line_start", TYPE_INTEGER, "Start line number for partial blame"},
            {"line_end", TYPE_INTEGER, "End line number for partial blame"}
        }, "path");
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        if (!args.has("path") || args.get("path").getAsString().isEmpty()) {
            return "Error: 'path' parameter is required";
        }
        String path = args.get("path").getAsString();

        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add("blame");

        if (args.has("line_start") && args.has("line_end")) {
            int lineStart = args.get("line_start").getAsInt();
            int lineEnd = args.get("line_end").getAsInt();
            cmdArgs.add("-L");
            cmdArgs.add(lineStart + "," + lineEnd);
        }

        cmdArgs.add("--");
        cmdArgs.add(path);

        return git.runGit(cmdArgs.toArray(String[]::new));
    }
}
