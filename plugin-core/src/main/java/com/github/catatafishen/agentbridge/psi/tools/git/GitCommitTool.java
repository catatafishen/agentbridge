package com.github.catatafishen.agentbridge.psi.tools.git;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.PsiBridgeService;
import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.github.catatafishen.agentbridge.psi.review.AgentEditSession;
import com.github.catatafishen.agentbridge.ui.renderers.GitCommitRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.OSAgnosticPathUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Commits staged changes with a message.
 */
@SuppressWarnings("java:S112")
public final class GitCommitTool extends GitTool {

    private static final String PARAM_MESSAGE = "message";
    private static final String PARAM_AMEND = "amend";
    private static final String PARAM_AUTHOR = "author";
    private static final String CRLF_SPLIT = "\\r?\\n";
    private static final String NAME_ONLY = "--name-only";

    public GitCommitTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "git_commit";
    }

    @Override
    public @NotNull String displayName() {
        return "Git Commit";
    }

    @Override
    public @NotNull String description() {
        return "Commit staged changes with a message. By default stages ALL changes first "
            + "(modified, deleted, and new untracked files) — equivalent to 'git add -A && git commit'. "
            + "Set all: false to commit only what is already staged. "
            + "Returns the commit result with the list of committed files, branch, tracking status, "
            + "ahead/behind counts, total commits on the branch, and remaining uncommitted changes.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Commit: \"{message}\"";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required(PARAM_MESSAGE, TYPE_STRING, "Commit message (use conventional commit format)"),
            Param.optional(PARAM_AMEND, TYPE_BOOLEAN, "If true, amend the previous commit instead of creating a new one"),
            Param.optional(PARAM_AUTHOR, TYPE_STRING, "Override the commit author (e.g. 'Name <email@example.com>')"),
            Param.optional("all", TYPE_BOOLEAN, "Stage all changes (modified, deleted, and new untracked files) before committing. Default: true. Set false to commit only already-staged changes."),
            Param.optional(PARAM_REPO, TYPE_STRING, REPO_PARAM_DESCRIPTION)
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        flushAndSave();

        String root = prepareCommit(args);
        if (root.startsWith("Error")) return root;
        String message = requiredMessage(args);
        if (message == null) return "Error: 'message' parameter is required";

        boolean commitAll = resolveCommitAll(args);
        boolean isAmend = resolveAmend(args);
        String reviewError = awaitCommitReview(root, commitAll, isAmend);
        if (reviewError != null) return reviewError;

        if (commitAll) runGitIn(root, "add", "-A");
        if (!isAmend && hasNoStagedChanges(root)) return buildNothingToCommitHint(root);

        showVcsToolWindow();
        String result = runGitIn(root, commitCommandArgs(args, message, isAmend));
        if (result.startsWith("Error")) return result;

        showNewCommitInLog(root);
        pruneApprovedReviewRows(root);
        return appendCommitDetails(result, root);
    }

    private String prepareCommit(@NotNull JsonObject args) {
        String repoParam = args.has(PARAM_REPO) ? args.get(PARAM_REPO).getAsString() : null;
        String ambiError = requireUnambiguousRepo(repoParam, "git_commit");
        if (ambiError != null) return ambiError;
        return resolveRepoRootOrError(repoParam);
    }

    private static @Nullable String requiredMessage(@NotNull JsonObject args) {
        if (!args.has(PARAM_MESSAGE) || args.get(PARAM_MESSAGE).getAsString().isEmpty()) return null;
        return args.get(PARAM_MESSAGE).getAsString();
    }

    private String awaitCommitReview(@NotNull String root, boolean commitAll, boolean isAmend) {
        AgentEditSession session = AgentEditSession.getInstance(project);
        if (isAmend) return session.awaitReviewCompletion("git commit --amend");
        Collection<String> filesToCommit = resolveFilesToCommit(commitAll, root);
        return session.awaitReviewForPaths("git commit", filesToCommit);
    }

    private boolean hasNoStagedChanges(@NotNull String root) {
        String staged = runGitInQuiet(root, "diff", "--cached", NAME_ONLY);
        return staged != null && staged.isEmpty();
    }

    private void showVcsToolWindow() {
        if (!ToolLayerSettings.getInstance(project).getFollowAgentFiles()) return;
        EdtUtil.invokeLater(() -> {
            var tw = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                .getToolWindow(com.intellij.openapi.wm.ToolWindowId.VCS);
            if (tw == null) return;
            if (PsiBridgeService.isChatToolWindowActive(project)) {
                tw.show();
            } else {
                tw.activate(null);
            }
        });
    }

    private static String[] commitCommandArgs(@NotNull JsonObject args, @NotNull String message, boolean isAmend) {
        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add("commit");
        if (isAmend) cmdArgs.add("--amend");
        if (args.has(PARAM_AUTHOR) && !args.get(PARAM_AUTHOR).getAsString().isEmpty()) {
            cmdArgs.add("--author");
            cmdArgs.add(args.get(PARAM_AUTHOR).getAsString());
        }
        cmdArgs.add("-m");
        cmdArgs.add(message);
        return cmdArgs.toArray(String[]::new);
    }

    private void pruneApprovedReviewRows(@NotNull String root) {
        try {
            String committedNames = runGitInQuiet(root, "show", NAME_ONLY, "--format=", "HEAD");
            List<String> paths = pathLines(committedNames);
            if (!paths.isEmpty()) {
                AgentEditSession.getInstance(project).removeApprovedForCommit(paths);
            }
        } catch (Exception ignored) {
            // Best-effort: failure to prune the review list must not affect the commit result.
        }
    }

    private static List<String> pathLines(@Nullable String rawPaths) {
        List<String> paths = new ArrayList<>();
        if (rawPaths == null || rawPaths.isBlank()) return paths;
        for (String line : rawPaths.split(CRLF_SPLIT)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) paths.add(trimmed);
        }
        return paths;
    }

    private String appendCommitDetails(@NotNull String result, @NotNull String root) {
        StringBuilder details = new StringBuilder(result);
        appendCommittedFiles(details, root);
        appendDefaultBranchWarning(details, root);
        return details + getBranchContextIn(root);
    }

    private void appendCommittedFiles(@NotNull StringBuilder details, @NotNull String root) {
        String fileStats = runGitInQuiet(root, "show", "--stat", "--format=", "HEAD");
        if (fileStats != null && !fileStats.isBlank()) {
            details.append("\n\nCommitted files:\n").append(fileStats.trim());
        }
    }

    private void appendDefaultBranchWarning(@NotNull StringBuilder details, @NotNull String root) {
        String branch = runGitInQuiet(root, "rev-parse", "--abbrev-ref", "HEAD");
        if ("main".equals(branch) || "master".equals(branch)) {
            details.append("\n\n⚠️ Warning: you committed directly to ").append(branch)
                .append(". Consider using a feature branch instead.");
        }
    }

    /**
     * Resolves the "amend" parameter: defaults to false unless explicitly set to true.
     */
    static boolean resolveAmend(JsonObject args) {
        return args.has(PARAM_AMEND) && args.get(PARAM_AMEND).getAsBoolean();
    }

    private Collection<String> resolveFilesToCommit(boolean commitAll, String root) {
        Set<String> paths = new java.util.HashSet<>();
        String basePath = project.getBasePath();
        String rawPaths = commitAll
            ? runGitInQuiet(root, "status", "--porcelain")
            : runGitInQuiet(root, "diff", "--cached", NAME_ONLY);
        if (rawPaths == null) return paths;

        for (String line : rawPaths.split(CRLF_SPLIT)) {
            String relPath = commitAll ? porcelainPath(line) : stagedPath(line);
            if (relPath != null) paths.add(toAbsolutePath(relPath, basePath));
        }
        return paths;
    }

    private static @Nullable String porcelainPath(@NotNull String line) {
        if (line.length() < 4) return null;
        String filePart = line.substring(3).trim();
        int arrow = filePart.indexOf(" -> ");
        return arrow >= 0 ? filePart.substring(arrow + 4).trim() : filePart;
    }

    private static @Nullable String stagedPath(@NotNull String line) {
        String trimmed = line.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static @NotNull String toAbsolutePath(@NotNull String path, @Nullable String basePath) {
        if (basePath == null || OSAgnosticPathUtil.isAbsolute(path)) return path;
        return basePath + "/" + path;
    }

    private static final int PATH_LIST_LIMIT = 10;

    /**
     * Caps a newline-separated path list so the "nothing to commit" hint stays
     * readable when a sub-directory like {@code .agent-work/} contains hundreds of
     * gitignored files. Anything beyond {@value #PATH_LIST_LIMIT} entries is folded
     * into a single "... and N more files" suffix.
     */
    private static String formatPathList(String rawNewlineSeparated) {
        if (rawNewlineSeparated == null || rawNewlineSeparated.isBlank()) return "";
        // Split on \r?\n and trim each entry — git output on Windows uses CRLF, and a
        // raw "\n"-only split would leave stray \r characters in the rendered hint.
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (String line : rawNewlineSeparated.split(CRLF_SPLIT)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        if (parts.isEmpty()) return "";
        if (parts.size() <= PATH_LIST_LIMIT) {
            return String.join(", ", parts);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < PATH_LIST_LIMIT; i++) {
            if (i > 0) sb.append(", ");
            sb.append(parts.get(i));
        }
        sb.append(", ... and ").append(parts.size() - PATH_LIST_LIMIT).append(" more files");
        return sb.toString();
    }

    /**
     * Builds a detailed hint for the "nothing to commit" case, listing which paths are
     * unstaged/untracked/ignored so the agent knows exactly what to stage (or force-add).
     */
    private String buildNothingToCommitHint(String root) {
        String unstaged = runGitInQuiet(root, "diff", NAME_ONLY);
        String untracked = runGitInQuiet(root, "ls-files", "--others", "--exclude-standard");
        String ignored = runGitInQuiet(root, "ls-files", "--others", "--ignored", "--exclude-standard");

        boolean hasUnstaged = unstaged != null && !unstaged.isEmpty();
        boolean hasUntracked = untracked != null && !untracked.isEmpty();
        boolean hasIgnored = ignored != null && !ignored.isEmpty();

        StringBuilder hint = new StringBuilder("Error: nothing to commit.");
        if (!hasUnstaged && !hasUntracked && !hasIgnored) {
            hint.append(" The working tree is clean.");
            return hint.toString();
        }

        hint.append(" Changes exist but were not staged by --all:");
        if (hasUnstaged) {
            hint.append("\n  Modified (not staged): ").append(formatPathList(unstaged));
        }
        if (hasUntracked) {
            hint.append("\n  Untracked: ").append(formatPathList(untracked));
        }
        if (hasIgnored) {
            hint.append("\n  Gitignored: ").append(formatPathList(ignored))
                .append("\n  (ignored files require explicit `git add -f <path>` — git_stage will not force-add them)");
        }
        hint.append("\nUse git_stage with an explicit path to include specific files, "
            + "or update .gitignore if these should be tracked.");
        return hint.toString();
    }

    /**
     * Resolves the "all" parameter: defaults to true (stage everything) unless explicitly set to false.
     */
    static boolean resolveCommitAll(JsonObject args) {
        return !args.has("all") || args.get("all").getAsBoolean();
    }

    @Override
    public @NotNull Object resultRenderer() {
        return GitCommitRenderer.INSTANCE;
    }
}
