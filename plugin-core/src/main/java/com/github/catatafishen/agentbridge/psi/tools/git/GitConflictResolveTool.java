package com.github.catatafishen.agentbridge.psi.tools.git;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves merge conflicts for a file. Supports accepting one side entirely
 * or providing custom merged content.
 */
@SuppressWarnings("java:S112")
public final class GitConflictResolveTool extends GitTool {

    private static final String PARAM_PATH = "path";
    private static final String PARAM_ACTION = "action";
    private static final String PARAM_CONTENT = "content";

    private static final String ACTION_ACCEPT_OURS = "accept_ours";
    private static final String ACTION_ACCEPT_THEIRS = "accept_theirs";
    private static final String ACTION_CUSTOM = "custom";
    private static final String ERR_PREFIX = "Error";

    public GitConflictResolveTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "git_conflict_resolve";
    }

    @Override
    public @NotNull String displayName() {
        return "Git Conflict Resolve";
    }

    @Override
    public @NotNull String description() {
        return """
            Resolve a merge conflict for a specific file. Actions:
            - 'accept_ours': keep the current branch version entirely
            - 'accept_theirs': keep the incoming branch version entirely
            - 'custom': provide the fully merged content (you must resolve all conflicts yourself)

            After resolution, the file is staged (git add). Use git_conflict_show first to \
            inspect the 3-way diff, then call this tool with the appropriate action.""";
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
        return "Resolve conflict in {path} ({action})";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required(PARAM_PATH, TYPE_STRING,
                "Path to the conflicted file (relative to repo root)"),
            Param.required(PARAM_ACTION, TYPE_STRING,
                "Resolution action: 'accept_ours', 'accept_theirs', or 'custom'"),
            Param.optional(PARAM_CONTENT, TYPE_STRING,
                "The fully merged file content (required when action is 'custom')"),
            Param.optional(PARAM_REPO, TYPE_STRING, REPO_PARAM_DESCRIPTION)
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String path = getRequiredString(args, PARAM_PATH);
        if (path == null) return "Error: 'path' parameter is required.";

        String action = getRequiredString(args, PARAM_ACTION);
        if (action == null) return "Error: 'action' parameter is required.";

        String repoParam = args.has(PARAM_REPO) ? args.get(PARAM_REPO).getAsString() : null;
        String ambiError = requireUnambiguousRepo(repoParam, "git_conflict_resolve");
        if (ambiError != null) return ambiError;
        String root = resolveRepoRootOrError(repoParam);
        if (root.startsWith(ERR_PREFIX)) return root;

        // Verify the file actually has conflicts
        String lsFiles = runGitInQuiet(root, "ls-files", "--unmerged", "--", path);
        if (lsFiles == null || lsFiles.isBlank()) {
            return "Error: '" + path + "' does not have unresolved conflicts. "
                + "Use git_conflicts to list conflicted files.";
        }

        return switch (action) {
            case ACTION_ACCEPT_OURS -> resolveWithCheckout(root, path, "--ours");
            case ACTION_ACCEPT_THEIRS -> resolveWithCheckout(root, path, "--theirs");
            case ACTION_CUSTOM -> resolveWithCustomContent(root, path, args);
            default -> "Error: unknown action '" + action + "'. "
                + "Valid actions: 'accept_ours', 'accept_theirs', 'custom'.";
        };
    }

    private String resolveWithCheckout(@NotNull String root, @NotNull String path, @NotNull String side)
        throws Exception {
        boolean isOurs = "--ours".equals(side);
        String sideName = isOurs ? "ours (current branch)" : "theirs (incoming)";

        if (isChosenSideDeleted(root, path, isOurs)) {
            String rmResult = runGitIn(root, "rm", "--", path);
            if (rmResult != null && rmResult.startsWith(ERR_PREFIX)) return rmResult;
            return "Resolved '" + path + "' by accepting " + sideName + " (deletion).\n"
                + "File has been removed and staged." + getBranchSummaryIn(root);
        }

        String checkoutResult = runGitIn(root, "checkout", side, "--", path);
        if (checkoutResult != null && checkoutResult.startsWith(ERR_PREFIX)) return checkoutResult;

        String addResult = runGitIn(root, "add", "--", path);
        if (addResult != null && addResult.startsWith(ERR_PREFIX)) return addResult;

        return "Resolved '" + path + "' by accepting " + sideName + ".\n"
            + "File has been staged." + getBranchSummaryIn(root);
    }

    /**
     * Checks if the chosen side (ours=stage 2, theirs=stage 3) is a deletion
     * by verifying its stage is absent in the unmerged listing.
     */
    private boolean isChosenSideDeleted(@NotNull String root, @NotNull String path, boolean isOurs) {
        String lsOutput = runGitInQuiet(root, "ls-files", "--unmerged", "--", path);
        if (lsOutput == null || lsOutput.isBlank()) return false;
        int targetStage = isOurs ? 2 : 3;
        for (String line : lsOutput.split("\n")) {
            int tabIdx = line.indexOf('\t');
            if (tabIdx < 2) continue;
            if (line.substring(tabIdx + 1).equals(path) && line.charAt(tabIdx - 1) - '0' == targetStage) {
                return false;
            }
        }
        return true;
    }

    private String resolveWithCustomContent(@NotNull String root, @NotNull String path, @NotNull JsonObject args)
        throws Exception {
        String content = args.has(PARAM_CONTENT) ? args.get(PARAM_CONTENT).getAsString() : null;
        if (content == null || content.isEmpty()) {
            return "Error: 'content' parameter is required when action is 'custom'. "
                + "Provide the fully merged file content.";
        }

        Path repoRoot = Path.of(root).normalize();
        Path filePath = repoRoot.resolve(path).normalize();
        if (!filePath.startsWith(repoRoot)) {
            return "Error: path '" + path + "' escapes the repository root. "
                + "Use a relative path within the repo.";
        }
        try {
            Files.writeString(filePath, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Error: failed to write resolved content to '" + path + "': " + e.getMessage();
        }

        String addResult = runGitIn(root, "add", "--", path);
        if (addResult != null && addResult.startsWith(ERR_PREFIX)) {
            return addResult;
        }

        refreshVcsState();

        return "Resolved '" + path + "' with custom content (" + content.length() + " chars).\n"
            + "File has been staged." + getBranchSummaryIn(root);
    }

    @Nullable
    private static String getRequiredString(@NotNull JsonObject args, @NotNull String key) {
        if (!args.has(key)) return null;
        String value = args.get(key).getAsString();
        return value.isBlank() ? null : value;
    }
}
