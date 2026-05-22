package com.github.catatafishen.agentbridge.psi.tools.git;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Shows the 3-way diff (base/ours/theirs) for a conflicted file in a structured format.
 * Unlike raw conflict markers ({@code <<<<<<<}), this output clearly separates each version
 * for review before resolving with {@code git_conflict_resolve}.
 */
@SuppressWarnings("java:S112")
public final class GitConflictShowTool extends GitTool {

    private static final String PARAM_PATH = "path";

    public GitConflictShowTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "git_conflict_show";
    }

    @Override
    public @NotNull String displayName() {
        return "Git Conflict Show";
    }

    @Override
    public @NotNull String description() {
        return "Show the 3-way merge content (base, ours, theirs) for a conflicted file. "
            + "Returns each conflict hunk numbered, with the base (common ancestor), ours (current branch), "
            + "and theirs (incoming branch) content clearly separated. Use the hunk numbers with "
            + "git_conflict_resolve to resolve individual conflicts.";
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
    public @NotNull String permissionTemplate() {
        return "Show conflict for {path}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required(PARAM_PATH, TYPE_STRING,
                "Path to the conflicted file (relative to repo root)"),
            Param.optional(PARAM_REPO, TYPE_STRING, REPO_PARAM_DESCRIPTION)
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        if (!args.has(PARAM_PATH) || args.get(PARAM_PATH).getAsString().isBlank()) {
            return "Error: 'path' parameter is required.";
        }
        String path = args.get(PARAM_PATH).getAsString();
        String repoParam = args.has(PARAM_REPO) ? args.get(PARAM_REPO).getAsString() : null;
        String root = resolveRepoRootOrError(repoParam);
        if (root.startsWith("Error")) return root;

        // Load all three versions from git index stages
        String base = loadStage(root, path, 1);
        String ours = loadStage(root, path, 2);
        String theirs = loadStage(root, path, 3);

        if (ours == null && theirs == null) {
            return "Error: '" + path + "' does not appear to have unresolved conflicts. "
                + "Use git_conflicts to list conflicted files.";
        }

        return formatThreeWay(path, base, ours, theirs);
    }

    @Nullable
    private String loadStage(@NotNull String root, @NotNull String path, int stage) {
        return runGitInQuiet(root, "show", ":" + stage + ":" + path);
    }

    private static String formatThreeWay(
        @NotNull String path,
        @Nullable String base,
        @Nullable String ours,
        @Nullable String theirs
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("Conflict in: ").append(path).append("\n\n");

        if (base == null && ours != null && theirs != null) {
            sb.append("Type: added by both (no common ancestor)\n\n");
        } else if (ours == null) {
            sb.append("Type: deleted by us (file exists on theirs but was deleted on ours)\n\n");
            sb.append("=== THEIRS (incoming) ===\n").append(theirs).append("\n");
            return sb.toString();
        } else if (theirs == null) {
            sb.append("Type: deleted by them (file exists on ours but was deleted on theirs)\n\n");
            sb.append("=== OURS (current branch) ===\n").append(ours).append("\n");
            return sb.toString();
        }

        sb.append("=== BASE (common ancestor) ===\n");
        sb.append(base != null ? base : "(not available)").append("\n\n");

        sb.append("=== OURS (current branch) ===\n");
        sb.append(ours).append("\n\n");

        sb.append("=== THEIRS (incoming) ===\n");
        sb.append(theirs).append("\n\n");

        sb.append("---\n");
        sb.append("To resolve: use git_conflict_resolve with action 'accept_ours', 'accept_theirs', ");
        sb.append("or 'custom' (provide the merged content).");

        return sb.toString();
    }
}
