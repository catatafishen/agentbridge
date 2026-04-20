package com.github.catatafishen.agentbridge.psi.tools.git;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.PsiBridgeService;
import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.github.catatafishen.agentbridge.psi.review.AgentEditSession;
import com.github.catatafishen.agentbridge.ui.renderers.GitCommitRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
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
            Param.optional("all", TYPE_BOOLEAN, "Stage all changes (modified, deleted, and new untracked files) before committing. Default: true. Set false to commit only already-staged changes.")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        flushAndSave();

        if (!args.has(PARAM_MESSAGE) || args.get(PARAM_MESSAGE).getAsString().isEmpty()) {
            return "Error: 'message' parameter is required";
        }

        boolean commitAll = resolveCommitAll(args);

        // Compute which files will be committed, then only gate on those paths.
        // This prevents unrelated PENDING review items from blocking the commit.
        Collection<String> filesToCommit = resolveFilesToCommit(commitAll);
        String reviewError = AgentEditSession.getInstance(project)
            .awaitReviewForPaths("git commit", filesToCommit);
        if (reviewError != null) return reviewError;

        if (commitAll) {
            // Stage all changes including new untracked files (equivalent to git add -A)
            runGit("add", "-A");
        }

        boolean isAmend = resolveAmend(args);

        // Pre-commit check: verify there are staged changes (skip for amend — message-only amends are valid)
        if (!isAmend) {
            String staged = runGitQuiet("diff", "--cached", "--name-only");
            if (staged != null && staged.isEmpty()) {
                return buildNothingToCommitHint();
            }
        }

        // Show VCS tool window in follow mode without stealing focus from chat prompt
        if (ToolLayerSettings.getInstance(project).getFollowAgentFiles()) {
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

        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add("commit");

        if (isAmend) {
            cmdArgs.add("--amend");
        }

        if (args.has(PARAM_AUTHOR) && !args.get(PARAM_AUTHOR).getAsString().isEmpty()) {
            cmdArgs.add("--author");
            cmdArgs.add(args.get(PARAM_AUTHOR).getAsString());
        }

        cmdArgs.add("-m");
        cmdArgs.add(args.get(PARAM_MESSAGE).getAsString());

        String result = runGit(cmdArgs.toArray(String[]::new));
        showNewCommitInLog();

        if (result.startsWith("Error")) return result;

        // Prune approved review rows for files that are now part of this commit.
        // Run on EDT-safe pool: AgentEditSession mutations + listeners are EDT-safe.
        try {
            String committedNames = runGitQuiet("show", "--name-only", "--format=", "HEAD");
            if (committedNames != null && !committedNames.isBlank()) {
                java.util.List<String> paths = new java.util.ArrayList<>();
                for (String line : committedNames.split("\\r?\\n")) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) paths.add(trimmed);
                }
                if (!paths.isEmpty()) {
                    var session = com.github.catatafishen.agentbridge.psi.PlatformApiCompat
                        .getService(project, com.github.catatafishen.agentbridge.psi.review.AgentEditSession.class);
                    if (session != null) session.removeApprovedForCommit(paths);
                }
            }
        } catch (Throwable ignored) {
            // Best-effort: failure to prune the review list must not affect the commit result.
        }

        // Append committed file list so agent can verify what was included
        String fileStats = runGitQuiet("show", "--stat", "--format=", "HEAD");
        if (fileStats != null && !fileStats.isBlank()) {
            result += "\n\nCommitted files:\n" + fileStats.trim();
        }

        // Warn if committing directly to default branch
        String branch = runGitQuiet("rev-parse", "--abbrev-ref", "HEAD");
        if ("main".equals(branch) || "master".equals(branch)) {
            result += "\n\n⚠️ Warning: you committed directly to " + branch
                + ". Consider using a feature branch instead.";
        }

        return result + getBranchContext();
    }

    /**
     * Resolves the "amend" parameter: defaults to false unless explicitly set to true.
     */
    static boolean resolveAmend(JsonObject args) {
        return args.has(PARAM_AMEND) && args.get(PARAM_AMEND).getAsBoolean();
    }

    /**
     * Determines which files will be part of the commit, resolved to absolute paths.
     * For {@code commitAll=true}: all modified, deleted, and untracked files from
     * {@code git status --porcelain}. For staged-only: files from
     * {@code git diff --cached --name-only}.
     */
    private Collection<String> resolveFilesToCommit(boolean commitAll) {
        String basePath = project.getBasePath();
        Set<String> paths = new java.util.HashSet<>();
        if (commitAll) {
            String status = runGitQuiet("status", "--porcelain");
            if (status != null) {
                for (String line : status.split("\\r?\\n")) {
                    if (line.length() < 4) continue;
                    // porcelain format: XY <path> or XY <orig> -> <path>
                    String filePart = line.substring(3);
                    int arrow = filePart.indexOf(" -> ");
                    String relPath = arrow >= 0 ? filePart.substring(arrow + 4) : filePart;
                    paths.add(toAbsolutePath(relPath.trim(), basePath));
                }
            }
        } else {
            String staged = runGitQuiet("diff", "--cached", "--name-only");
            if (staged != null) {
                for (String line : staged.split("\\r?\\n")) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        paths.add(toAbsolutePath(trimmed, basePath));
                    }
                }
            }
        }
        return paths;
    }

    private static @NotNull String toAbsolutePath(@NotNull String path, @Nullable String basePath) {
        if (basePath == null || path.startsWith("/")) return path;
        return basePath + "/" + path;
    }

    /**
     * Builds a detailed hint for the "nothing to commit" case, listing which paths are
     * unstaged/untracked/ignored so the agent knows exactly what to stage (or force-add).
     */
    private String buildNothingToCommitHint() {
        String unstaged = runGitQuiet("diff", "--name-only");
        String untracked = runGitQuiet("ls-files", "--others", "--exclude-standard");
        String ignored = runGitQuiet("ls-files", "--others", "--ignored", "--exclude-standard");

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
            hint.append("\n  Modified (not staged): ").append(unstaged.replace("\n", ", "));
        }
        if (hasUntracked) {
            hint.append("\n  Untracked: ").append(untracked.replace("\n", ", "));
        }
        if (hasIgnored) {
            hint.append("\n  Gitignored: ").append(ignored.replace("\n", ", "))
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
