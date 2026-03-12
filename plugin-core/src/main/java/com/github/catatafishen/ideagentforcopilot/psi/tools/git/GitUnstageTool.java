package com.github.catatafishen.ideagentforcopilot.psi.tools.git;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Unstages files that were previously staged.
 */
@SuppressWarnings("java:S112")
public final class GitUnstageTool extends GitTool {

    private static final String PARAM_PATHS = "paths";

    public GitUnstageTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "git_unstage";
    }

    @Override
    public @NotNull String displayName() {
        return "Git Unstage";
    }

    @Override
    public @NotNull String description() {
        return "Unstage files that were previously staged";
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Unstage {path}";
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        JsonObject s = schema(new Object[][]{
            {"path", TYPE_STRING, "Single file path to unstage"},
            {"paths", TYPE_ARRAY, "Multiple file paths to unstage"}
        });
        addArrayItems(s, "paths");
        return s;
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add("restore");
        cmdArgs.add("--staged");

        if (args.has(PARAM_PATHS) && args.get(PARAM_PATHS).isJsonArray()) {
            var paths = args.getAsJsonArray(PARAM_PATHS);
            for (var p : paths) {
                cmdArgs.add(p.getAsString());
            }
        } else if (args.has("path") && !args.get("path").getAsString().isEmpty()) {
            cmdArgs.add(args.get("path").getAsString());
        } else {
            return "Error: provide 'path' or 'paths' parameter";
        }

        return runGit(cmdArgs.toArray(String[]::new));
    }
}
