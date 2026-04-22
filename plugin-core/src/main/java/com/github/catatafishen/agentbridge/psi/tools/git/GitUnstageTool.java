package com.github.catatafishen.agentbridge.psi.tools.git;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

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
        return "Unstage files that were previously staged. Returns result with branch summary "
            + "including staged/unstaged file counts.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Unstage {path}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        JsonObject s = schema(
            Param.optional("path", TYPE_STRING, "Single file path to unstage"),
            Param.optional(PARAM_PATHS, TYPE_ARRAY, "Multiple file paths to unstage"),
            Param.optional(PARAM_REPO, TYPE_STRING, REPO_PARAM_DESCRIPTION)
        );
        addArrayItems(s, PARAM_PATHS);
        return s;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String repoParam = args.has(PARAM_REPO) ? args.get(PARAM_REPO).getAsString() : null;
        String ambiError = requireUnambiguousRepo(repoParam, "git_unstage");
        if (ambiError != null) return ambiError;
        String root = resolveRepoRootOrError(repoParam);
        if (root.startsWith("Error")) return root;

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

        String result = runGitIn(root, cmdArgs.toArray(String[]::new));
        if (result.startsWith("Error")) return result;

        return result + getBranchSummaryIn(root);
    }
}
