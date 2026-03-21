package com.github.catatafishen.ideagentforcopilot.psi.tools.git;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@SuppressWarnings("java:S112")
public final class GetFileHistoryTool extends GitTool {

    private static final int DEFAULT_MAX_COUNT = 20;
    private static final String PARAM_MAX_COUNT = "max_count";

    public GetFileHistoryTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "get_file_history";
    }

    @Override
    public @NotNull String displayName() {
        return "Get File History";
    }

    @Override
    public @NotNull String description() {
        return "Get git commit history for a file, including renames";
    }

    

    @Override
    public @NotNull String kind() {
        return "read";
    }
@Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
            {"path", TYPE_STRING, "Path to the file to get history for (absolute or project-relative)"},
            {PARAM_MAX_COUNT, TYPE_INTEGER, "Maximum number of commits to show (default: 20)"}
        }, "path");
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        if (!args.has("path") || args.get("path").getAsString().isEmpty()) {
            return "Error: 'path' parameter is required";
        }
        String path = args.get("path").getAsString();

        String maxCount = String.valueOf(
            args.has(PARAM_MAX_COUNT)
                ? args.get(PARAM_MAX_COUNT).getAsInt()
                : DEFAULT_MAX_COUNT);

        return runGit("log", "--follow",
            "--format=%H %ai %an%n  %s",
            "-n", maxCount, "--", path);
    }
}
