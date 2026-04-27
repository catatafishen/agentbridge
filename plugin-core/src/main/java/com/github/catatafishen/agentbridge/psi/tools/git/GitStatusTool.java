package com.github.catatafishen.agentbridge.psi.tools.git;

import com.github.catatafishen.agentbridge.ui.renderers.GitStatusRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

@SuppressWarnings("java:S112")
public final class GitStatusTool extends GitTool {

    private static final String PARAM_VERBOSE = "verbose";

    public GitStatusTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "git_status";
    }

    @Override
    public @NotNull String displayName() {
        return "Git Status";
    }

    @Override
    public @NotNull String description() {
        return "Show working tree status including branch tracking info and stash count. "
            + "Use verbose: true for full output including untracked files. "
            + "When the project has multiple git repositories, returns an aggregate summary "
            + "for all repos unless a specific 'repo' path is provided.";
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
            Param.optional(PARAM_VERBOSE, TYPE_BOOLEAN, "If true, show full 'git status' output including untracked files"),
            Param.optional(PARAM_REPO, TYPE_STRING, REPO_PARAM_DESCRIPTION)
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        flushAndSave();

        boolean verbose = args.has(PARAM_VERBOSE) && args.get(PARAM_VERBOSE).getAsBoolean();
        String repoParam = args.has(PARAM_REPO) ? args.get(PARAM_REPO).getAsString() : null;

        if (isMultiRepo() && repoParam == null) {
            return aggregateMultiRepoStatus(verbose);
        }

        String root = resolveRepoRootOrError(repoParam);
        if (root.startsWith("Error")) return root;

        return statusForRoot(root, verbose);
    }

    private String statusForRoot(String root, boolean verbose) throws Exception {
        String result;
        if (verbose) {
            result = runGitIn(root, "status");
        } else {
            result = runGitIn(root, "status", "--short", "--branch");
        }

        String stashList = runGitInQuiet(root, "stash", "list");
        if (stashList != null && !stashList.isEmpty()) {
            long count = stashList.chars().filter(c -> c == '\n').count();
            if (!stashList.endsWith("\n")) count++;
            if (count > 0) {
                result += "\nStash: " + count + " entr" + (count == 1 ? "y" : "ies");
            }
        }

        return result;
    }

    private String aggregateMultiRepoStatus(boolean verbose) throws Exception {
        java.util.List<String> roots;
        try {
            roots = com.github.catatafishen.agentbridge.psi.PlatformApiCompat.getDetectedGitRoots(project);
        } catch (NoClassDefFoundError e) {
            roots = java.util.Collections.emptyList();
        }
        String basePath = project.getBasePath();
        StringBuilder sb = new StringBuilder();
        for (String absRoot : roots) {
            String relRoot = toRelativePath(absRoot, basePath);
            sb.append("=== ").append(relRoot).append(" ===\n");
            sb.append(statusForRoot(absRoot, verbose)).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    @Override
    public @NotNull Object resultRenderer() {
        return GitStatusRenderer.INSTANCE;
    }
}
