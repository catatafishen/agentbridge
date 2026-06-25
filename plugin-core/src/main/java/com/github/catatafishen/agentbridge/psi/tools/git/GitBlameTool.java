package com.github.catatafishen.agentbridge.psi.tools.git;

import com.github.catatafishen.agentbridge.ui.renderers.GitBlameRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("java:S112")
public final class GitBlameTool extends GitTool {

    private static final String PARAM_START_LINE = "start_line";
    private static final String PARAM_END_LINE = "end_line";

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
        return "Show per-line authorship for a file. Returns commit hash, author, and date for each line. " +
            "Use start_line/end_line to blame a specific range. Use get_file_history for commit-level history.";
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
            Param.required("path", TYPE_STRING, "File path to blame"),
            Param.optional(PARAM_START_LINE, TYPE_INTEGER, "Start line number for partial blame"),
            Param.optional(PARAM_END_LINE, TYPE_INTEGER, "End line number for partial blame"),
            Param.optional(PARAM_REPO, TYPE_STRING, REPO_PARAM_DESCRIPTION)
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        if (!args.has("path") || args.get("path").getAsString().isEmpty()) {
            return "Error: 'path' parameter is required";
        }
        String path = args.get("path").getAsString();

        String repoParam = args.has(PARAM_REPO) ? args.get(PARAM_REPO).getAsString() : null;
        String root = resolveRepoRootOrError(repoParam);
        if (root.startsWith("Error")) return root;

        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add("blame");

        int lineStart = -1;
        int lineEnd = -1;
        if (args.has(PARAM_START_LINE)) lineStart = args.get(PARAM_START_LINE).getAsInt();
        else if (args.has("line_start")) lineStart = args.get("line_start").getAsInt();
        if (args.has(PARAM_END_LINE)) lineEnd = args.get(PARAM_END_LINE).getAsInt();
        else if (args.has("line_end")) lineEnd = args.get("line_end").getAsInt();

        if (lineStart > 0 && lineEnd > 0) {
            cmdArgs.add("-L");
            cmdArgs.add(lineStart + "," + lineEnd);
        }

        cmdArgs.add("--");
        cmdArgs.add(path);

        return runGitIn(root, cmdArgs.toArray(String[]::new));
    }

    @Override
    public @NotNull Object resultRenderer() {
        return GitBlameRenderer.INSTANCE;
    }
}
