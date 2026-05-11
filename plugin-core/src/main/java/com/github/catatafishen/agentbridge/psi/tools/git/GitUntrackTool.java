package com.github.catatafishen.agentbridge.psi.tools.git;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Removes files from git's index without deleting them from disk (git rm --cached).
 * This "untracks" files that have been committed — they will appear as untracked after.
 * Typically followed by adding the paths to .gitignore to prevent re-tracking.
 */
@SuppressWarnings("java:S112")
public final class GitUntrackTool extends GitTool {

    private static final String PARAM_PATHS = "paths";

    public GitUntrackTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "git_untrack";
    }

    @Override
    public @NotNull String displayName() {
        return "Git Untrack";
    }

    @Override
    public @NotNull String description() {
        return "Remove files from git's index without deleting them from disk — equivalent to "
            + "'git rm --cached'. Use this to stop tracking files that are already committed. "
            + "After untracking, the file will appear as untracked in git status. You usually want "
            + "to also add the path to .gitignore afterward to prevent it from being re-staged. "
            + "This is NOT the same as git_unstage (which only unstages staged-but-uncommitted "
            + "changes) — git_untrack removes the file from tracking history entirely.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public boolean isDestructive() {
        return true;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Untrack {path} from git index";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        JsonObject s = schema(
            Param.optional("path", TYPE_STRING, "Single file path to untrack"),
            Param.optional(PARAM_PATHS, TYPE_ARRAY, "Multiple file paths to untrack"),
            Param.optional("recursive", TYPE_BOOLEAN,
                "If true, also untrack files inside a directory (adds -r flag). Default: false"),
            Param.optional(PARAM_REPO, TYPE_STRING, REPO_PARAM_DESCRIPTION)
        );
        addArrayItems(s, PARAM_PATHS);
        return s;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        flushAndSave();

        String repoParam = args.has(PARAM_REPO) ? args.get(PARAM_REPO).getAsString() : null;
        String ambiError = requireUnambiguousRepo(repoParam, "git_untrack");
        if (ambiError != null) return ambiError;
        String root = resolveRepoRootOrError(repoParam);
        if (root.startsWith("Error")) return root;

        List<String> paths = collectPaths(args);
        if (paths == null) {
            return "Error: provide 'path' or 'paths' parameter";
        }

        boolean recursive = args.has("recursive") && args.get("recursive").getAsBoolean();

        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add("rm");
        cmdArgs.add("--cached");
        if (recursive) {
            cmdArgs.add("-r");
        }
        cmdArgs.addAll(paths);

        String result = runGitIn(root, cmdArgs.toArray(String[]::new));
        if (result.startsWith("Error")) return result;

        String summary = result.isBlank()
            ? "Untracked from git index: " + String.join(", ", paths)
            : result.trim();

        return summary + "\n\nNote: files still exist on disk. Add paths to .gitignore to prevent re-tracking."
            + getBranchSummaryIn(root);
    }

    private List<String> collectPaths(@NotNull JsonObject args) {
        if (args.has(PARAM_PATHS) && args.get(PARAM_PATHS).isJsonArray()) {
            List<String> result = new ArrayList<>();
            for (var p : args.getAsJsonArray(PARAM_PATHS)) {
                result.add(p.getAsString());
            }
            return result.isEmpty() ? null : result;
        }
        if (args.has("path") && !args.get("path").getAsString().isEmpty()) {
            return List.of(args.get("path").getAsString());
        }
        return null;
    }
}
