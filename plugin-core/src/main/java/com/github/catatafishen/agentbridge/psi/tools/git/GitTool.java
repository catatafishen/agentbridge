package com.github.catatafishen.agentbridge.psi.tools.git;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.github.catatafishen.agentbridge.psi.PsiBridgeService;
import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.github.catatafishen.agentbridge.psi.tools.file.FileTool;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.github.catatafishen.agentbridge.ui.renderers.GitOperationRenderer;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.psi.PsiDocumentManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Abstract base for git tools. Provides git process execution, VCS refresh,
 * branch context enrichment, auto-fetch throttling, and IDE follow-along helpers.
 *
 * <p>Multi-repo support: when a project contains more than one git repository,
 * tools accept an optional {@code repo} parameter (relative path from the project root,
 * e.g. {@code "backend"}) to select the target repository. Read operations default to
 * the primary repository when no selector is given; write operations require an explicit
 * selector and return an actionable error when the project is ambiguous.
 */
@SuppressWarnings("java:S112") // generic exceptions caught at JSON-RPC dispatch level
public abstract class GitTool extends Tool {

    private static final Set<String> WRITE_COMMANDS = Set.of(
        "add", "branch", "checkout", "cherry-pick", "commit", "fetch", "merge",
        "pull", "push", "rebase", "remote", "reset", "restore",
        "revert", "stash", "switch", "tag"
    );

    static final Pattern FULL_HASH_PATTERN =
        Pattern.compile("\\b[0-9a-f]{40}\\b");
    static final Pattern COMMIT_LINE_PATTERN =
        Pattern.compile("^commit ([0-9a-f]{40})$", Pattern.MULTILINE);

    // ── Multi-repo selectors ─────────────────────────────────

    /**
     * Shared parameter name for the optional repository selector.
     */
    protected static final String PARAM_REPO = "repo";

    /**
     * Description for the optional {@code repo} parameter in tool schemas.
     * Only shown/needed for multi-repo projects; benign extra field for single-repo.
     */
    static final String REPO_PARAM_DESCRIPTION =
        "Optional: relative path of the git repository root to target (e.g. 'backend'). "
            + "Only needed when the project contains multiple git repositories. "
            + "Use git_status with no parameters to discover available repositories.";

    // ── Auto-fetch throttling (per-repo) ─────────────────────

    protected static final long FETCH_THROTTLE_MS = 60_000;

    /**
     * Per-repo auto-fetch timestamps, keyed by absolute repository root path.
     */
    private static final ConcurrentHashMap<String, AtomicLong> lastFetchTimes =
        new ConcurrentHashMap<>();

    protected GitTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull ToolRegistry.Category category() {
        return ToolRegistry.Category.GIT;
    }

    @Override
    public @NotNull Object resultRenderer() {
        return GitOperationRenderer.INSTANCE;
    }

    /**
     * Git write tools (commit, push, merge, etc.) are denied for sub-agents
     * because sub-agents cannot receive guidance via session/message and
     * would bypass the main agent's VCS workflow.
     */
    @Override
    public boolean denyForSubAgent() {
        return !isReadOnly();
    }

    // ── Multi-repo helpers ───────────────────────────────────

    protected boolean isMultiRepo() {
        try {
            return PlatformApiCompat.getDetectedGitRoots(project).size() > 1;
        } catch (NoClassDefFoundError e) {
            return false;
        }
    }

    protected List<String> listRepoRoots() {
        try {
            String basePath = project.getBasePath();
            return PlatformApiCompat.getDetectedGitRoots(project).stream()
                .map(p -> toRelativePath(p, basePath))
                .collect(Collectors.toList());
        } catch (NoClassDefFoundError e) {
            return Collections.emptyList();
        }
    }

    @NotNull
    protected String resolveRepoRootOrError(@Nullable String repoParam) {
        List<String> roots;
        try {
            roots = PlatformApiCompat.getDetectedGitRoots(project);
        } catch (NoClassDefFoundError e) {
            roots = Collections.emptyList();
        }

        if (repoParam != null && !repoParam.isEmpty()) {
            String basePath = project.getBasePath();
            // Accept both relative (e.g. "backend") and absolute paths
            String absParam = (basePath != null && !new File(repoParam).isAbsolute())
                ? new File(basePath, repoParam).getAbsolutePath().replace("\\", "/")
                : repoParam.replace("\\", "/");

            for (String root : roots) {
                if (root.equals(absParam)) return root;
            }
            String available = roots.isEmpty() ? "none"
                : roots.stream()
                  .map(r -> "'" + toRelativePath(r, project.getBasePath()) + "'")
                  .collect(Collectors.joining(", "));
            return "Error: repository '" + repoParam + "' not found. Available: " + available
                + ". Use git_status to list repositories.";
        }

        if (roots.isEmpty()) {
            String basePath = project.getBasePath();
            return basePath != null ? basePath : "Error: no project base path";
        }

        if (roots.size() == 1) {
            return roots.getFirst();
        }

        // Multiple repos: prefer the one rooted at basePath, otherwise use first.
        String basePath = project.getBasePath();
        if (basePath != null) {
            for (String root : roots) {
                if (root.equals(basePath)) return root;
            }
        }
        return roots.getFirst();
    }

    /**
     * For write operations: returns an error string when the project has multiple repositories
     * and no {@code repo} selector was given; returns null when it is safe to proceed.
     *
     * @param repoParam the value of the {@code repo} parameter (may be null)
     * @param action    human-readable action name for the error message
     */
    @Nullable
    protected String requireUnambiguousRepo(@Nullable String repoParam, @NotNull String action) {
        if (repoParam != null && !repoParam.isEmpty()) return null;
        if (!isMultiRepo()) return null;

        String repoList = listRepoRoots().stream()
            .map(r -> "'" + r + "'")
            .collect(Collectors.joining(", "));
        return "Error: project has multiple git repositories (" + repoList + "). "
            + "Specify which repository to use with the 'repo' parameter for '"
            + action + "'. Use git_status to see all repositories.";
    }

    // ── Branch context enrichment ────────────────────────────

    /**
     * Gathers current branch state metadata for appending to tool responses.
     * Runs ~5 lightweight git commands, each fail-safe (omitted on error).
     * Typical latency: &lt;200ms total.
     *
     * @return formatted context block, or empty string if branch detection fails
     */
    protected String getBranchContext() {
        return getBranchContextIn(resolveRepoRootOrError(null));
    }

    /**
     * Root-aware variant of {@link #getBranchContext()}.
     * Use when the repo root has already been resolved for the current tool call.
     */
    protected String getBranchContextIn(@NotNull String rootDir) {
        if (rootDir.startsWith("Error")) return "";
        StringBuilder ctx = new StringBuilder();

        String branch = runGitInQuiet(rootDir, "rev-parse", "--abbrev-ref", "HEAD");
        if (branch == null) return "";

        ctx.append("\n\n--- Context ---\n");
        ctx.append("On branch: ").append(branch).append('\n');

        String tracking = runGitInQuiet(rootDir, "rev-parse", "--abbrev-ref", "@{upstream}");
        if (tracking != null) {
            ctx.append("Tracking: ").append(tracking);
            appendAheadBehindIn(ctx, rootDir, tracking);
            ctx.append('\n');
        } else {
            ctx.append("Tracking: none (no upstream set — use git_push with set_upstream: true)\n");
        }

        // Divergence from default branch
        String defaultBranch = detectDefaultBranchIn(rootDir);
        if (defaultBranch != null && !defaultBranch.equals(branch)) {
            String count = runGitInQuiet(rootDir, "rev-list", "--count", defaultBranch + "..HEAD");
            if (count != null && !"0".equals(count)) {
                ctx.append("Branch has ").append(count)
                    .append(" commit(s) since ").append(defaultBranch).append('\n');
            }
        }

        // Working tree status
        String porcelain = runGitInQuiet(rootDir, "status", "--porcelain");
        if (porcelain != null) {
            if (porcelain.isEmpty()) {
                ctx.append("Working tree: clean\n");
            } else {
                ctx.append("Working tree: ").append(formatPorcelainStatus(porcelain)).append('\n');
            }
        }

        // Stash count
        String stashList = runGitInQuiet(rootDir, "stash", "list");
        if (stashList != null && !stashList.isEmpty()) {
            long count = countStashEntries(stashList);
            if (count > 0) {
                ctx.append("Stash: ").append(count).append(" entr").append(count == 1 ? "y" : "ies").append('\n');
            }
        }

        return ctx.toString();
    }

    /**
     * Returns a compact one-line branch summary (for tools that want less verbosity).
     */
    protected String getBranchSummary() {
        return getBranchSummaryIn(resolveRepoRootOrError(null));
    }

    /**
     * Root-aware variant of {@link #getBranchSummary()}.
     */
    protected String getBranchSummaryIn(@NotNull String rootDir) {
        if (rootDir.startsWith("Error")) return "";
        String branch = runGitInQuiet(rootDir, "rev-parse", "--abbrev-ref", "HEAD");
        if (branch == null) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("\nBranch: ").append(branch);

        String tracking = runGitInQuiet(rootDir, "rev-parse", "--abbrev-ref", "@{upstream}");
        if (tracking != null) {
            appendAheadBehindIn(sb, rootDir, tracking);
        }
        return sb.toString();
    }

    private void appendAheadBehindIn(@NotNull StringBuilder sb, @NotNull String rootDir, @NotNull String tracking) {
        String ahead = runGitInQuiet(rootDir, "rev-list", "--count", tracking + "..HEAD");
        String behind = runGitInQuiet(rootDir, "rev-list", "--count", "HEAD.." + tracking);
        if (ahead != null && behind != null) {
            sb.append(" (ahead ").append(ahead).append(", behind ").append(behind).append(')');
        }
    }

    /**
     * Parses git {@code status --porcelain} output into a human-readable summary.
     * Pure function — no IDE dependency.
     */
    static String formatPorcelainStatus(String porcelain) {
        int staged = 0;
        int modified = 0;
        int untracked = 0;
        for (String line : porcelain.split("\n")) {
            if (line.length() < 2) continue;
            char index = line.charAt(0);
            char worktree = line.charAt(1);
            if (line.startsWith("??")) {
                untracked++;
            } else {
                if (index != ' ' && index != '?') staged++;
                if (worktree != ' ' && worktree != '?') modified++;
            }
        }

        List<String> parts = new ArrayList<>();
        if (staged > 0) parts.add(staged + " staged");
        if (modified > 0) parts.add(modified + " modified");
        if (untracked > 0) parts.add(untracked + " untracked");
        return String.join(", ", parts);
    }

    /**
     * Counts the number of stash entries from {@code git stash list} output. Pure function.
     */
    static long countStashEntries(String stashList) {
        if (stashList.isEmpty()) return 0;
        long count = stashList.chars().filter(c -> c == '\n').count();
        if (!stashList.endsWith("\n")) count++;
        return count;
    }

    /**
     * Extracts the first full 40-character commit hash from git output.
     * Tries {@code commit <hash>} lines first, falls back to standalone hex patterns.
     * Pure function — no IDE dependency.
     */
    @Nullable
    static String extractFirstCommitHash(@Nullable String gitOutput) {
        if (gitOutput == null || gitOutput.isEmpty()) return null;
        var m = COMMIT_LINE_PATTERN.matcher(gitOutput);
        if (m.find()) return m.group(1);
        var m2 = FULL_HASH_PATTERN.matcher(gitOutput);
        if (m2.find()) return m2.group();
        return null;
    }

    /**
     * Detects the default branch (origin/main or origin/master) in the primary repo.
     */
    @Nullable
    protected String detectDefaultBranch() {
        return detectDefaultBranchIn(resolveRepoRootOrError(null));
    }

    @Nullable
    private String detectDefaultBranchIn(@NotNull String rootDir) {
        String symbolic = runGitInQuiet(rootDir, "symbolic-ref", "refs/remotes/origin/HEAD");
        if (symbolic != null) {
            return symbolic.replace("refs/remotes/", "");
        }
        String branches = runGitInQuiet(rootDir, "branch", "-r", "--list", "origin/main", "origin/master");
        if (branches == null) return null;
        if (branches.contains("origin/main")) return "origin/main";
        if (branches.contains("origin/master")) return "origin/master";
        return null;
    }

    // ── Auto-fetch throttling ────────────────────────────────

    /**
     * Fetches from origin in the primary repo if the last fetch was more than 60 seconds ago.
     * Returns a note about what was fetched, or empty string if throttled/failed.
     */
    protected String autoFetchIfStale() {
        String root = resolveRepoRootOrError(null);
        return root.startsWith("Error") ? "" : autoFetchIfStaleIn(root);
    }

    /**
     * Root-aware variant of {@link #autoFetchIfStale()}.
     * Throttle is tracked per repository root to avoid cross-repo interference.
     */
    protected String autoFetchIfStaleIn(@NotNull String rootDir) {
        AtomicLong timer = lastFetchTimes.computeIfAbsent(rootDir, k -> new AtomicLong(0));
        long now = System.currentTimeMillis();
        long last = timer.get();
        if (now - last < FETCH_THROTTLE_MS) return "";
        if (!timer.compareAndSet(last, now)) return "";

        try {
            String result = runGitIn(rootDir, "fetch", "--quiet", "origin");
            if (result != null && !result.isBlank() && !result.startsWith("Error")) {
                return "(auto-fetched latest from origin)\n";
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Records a completed explicit fetch so the auto-fetch throttle skips the next window.
     * Call this after a successful explicit {@code git fetch} to prevent a redundant
     * auto-fetch within the next {@value #FETCH_THROTTLE_MS} ms.
     */
    protected void markFetchCompleted(@NotNull String rootDir) {
        lastFetchTimes.computeIfAbsent(rootDir, k -> new AtomicLong(0))
            .set(System.currentTimeMillis());
    }

    /**
     * Checks if a branch/ref argument references a remote and fetches if stale.
     *
     * @param ref the branch or ref name from tool arguments
     * @return fetch note if fetched, empty string otherwise
     */
    protected String autoFetchForRemoteRef(@Nullable String ref) {
        if (ref == null) return "";
        if (ref.startsWith("origin/") || ref.startsWith("remotes/")) {
            return autoFetchIfStale();
        }
        return "";
    }

    /**
     * Root-aware variant of {@link #autoFetchForRemoteRef(String)}.
     */
    protected String autoFetchForRemoteRefIn(@Nullable String ref, @NotNull String rootDir) {
        if (ref == null) return "";
        if (ref.startsWith("origin/") || ref.startsWith("remotes/")) {
            return autoFetchIfStaleIn(rootDir);
        }
        return "";
    }

    // ── Run git (quiet variant for metadata) ─────────────────

    /**
     * Runs a git command in the primary repository and returns trimmed stdout, or null on error.
     * Used for lightweight metadata queries that must not fail loudly.
     */
    @Nullable
    protected String runGitQuiet(String... args) {
        try {
            String result = runGit(args);
            if (result == null || result.startsWith("Error")) return null;
            return result.trim();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Runs a git command in {@code rootDir} and returns trimmed stdout, or null on any error.
     */
    @Nullable
    protected String runGitInQuiet(@NotNull String rootDir, String... args) {
        try {
            String result = runGitIn(rootDir, args);
            if (result == null || result.startsWith("Error")) return null;
            return result.trim();
        } catch (Exception e) {
            return null;
        }
    }

    // ── Core git execution ───────────────────────────────────

    /**
     * Flush pending auto-format and save all documents to disk.
     * Called before git commands that need the working tree up-to-date.
     */
    protected void flushAndSave() {
        FileTool.flushPendingAutoFormat(project);
        saveAllDocuments();
    }

    /**
     * Run a git command in the primary repository, preferring IntelliJ's Git4Idea infrastructure.
     * Falls back to ProcessBuilder if Git4Idea is unavailable.
     */
    protected String runGit(String... args) throws Exception {
        if (args.length == 0) return "Error: no git command";

        String result;
        try {
            result = PlatformApiCompat.runIdeGitCommand(project, args);
            if (result == null) {
                String basePath = project.getBasePath();
                result = basePath != null
                    ? runGitProcess(basePath, args)
                    : "Error: no project base path";
            }
        } catch (NoClassDefFoundError e) {
            String basePath = project.getBasePath();
            result = basePath != null
                ? runGitProcess(basePath, args)
                : "Error: no project base path";
        }

        if (WRITE_COMMANDS.contains(args[0])) {
            refreshVcsState();
        }

        return result;
    }

    /**
     * Run a git command in the specified repository root directory.
     * Prefers Git4Idea infrastructure; falls back to ProcessBuilder.
     */
    protected String runGitIn(@NotNull String rootDir, String... args) throws Exception {
        if (args.length == 0) return "Error: no git command";

        String result;
        try {
            result = PlatformApiCompat.runIdeGitCommandIn(project, rootDir, args);
            if (result == null) {
                result = runGitProcess(rootDir, args);
            }
        } catch (NoClassDefFoundError e) {
            result = runGitProcess(rootDir, args);
        }

        if (WRITE_COMMANDS.contains(args[0])) {
            refreshVcsState();
        }

        return result;
    }

    private String runGitProcess(@NotNull String rootDir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("--no-pager");
        cmd.addAll(Arrays.asList(args));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new File(rootDir));
        pb.redirectErrorStream(false);
        // Prevent git from opening a text editor (e.g. for revert/commit without --no-edit).
        // "true" is a POSIX no-op that exits 0, causing git to use the default message.
        pb.environment().put("GIT_EDITOR", "true");
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        Process p = pb.start();

        String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

        boolean finished = p.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            return "Error: git command timed out";
        }

        if (p.exitValue() != 0) {
            return "Error (exit " + p.exitValue() + "): " + stderr.trim();
        }
        return stdout;
    }

    protected void refreshVcsState() {
        String basePath = project.getBasePath();
        if (basePath == null) return;
        EdtUtil.invokeLater(() -> {
            var root = LocalFileSystem.getInstance().findFileByPath(basePath);
            if (root != null) {
                VfsUtil.markDirtyAndRefresh(true, true, true, root);
            }
            VcsDirtyScopeManager.getInstance(project).markEverythingDirty();
        });
    }

    private void saveAllDocuments() {
        EdtUtil.invokeAndWait(() ->
            WriteAction.run(() -> {
                PsiDocumentManager.getInstance(project).commitAllDocuments();
                FileDocumentManager.getInstance().saveAllDocuments();
            }));
    }

    // ── VCS Log follow-along ─────────────────────────────────

    /**
     * After a successful commit, open the Git Log tab and navigate to HEAD of {@code repoRoot}.
     * Reads HEAD from the supplied repo root (not the project base) so multi-repo commits
     * navigate to the correct commit. See {@code docs/bugs/COMMIT-NOT-FOUND-IN-LOG-BUG.md}.
     *
     * <p>Uses {@code tw.show()} instead of {@code tw.activate()} when the chat prompt has focus,
     * preventing keystroke leaks to the VCS tool window.
     *
     * @param repoRoot absolute path of the repository the commit was made in
     */
    protected void showNewCommitInLog(@NotNull String repoRoot) {
        if (!ToolLayerSettings.getInstance(project).getFollowAgentFiles()) return;

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                String fullHash = runGitIn(repoRoot, "rev-parse", "HEAD").trim();
                if (fullHash.length() != 40) return;

                EdtUtil.invokeLater(() -> {
                    var twm = com.intellij.openapi.wm.ToolWindowManager.getInstance(project);
                    var tw = twm.getToolWindow(com.intellij.openapi.wm.ToolWindowId.VCS);
                    if (tw != null) {
                        if (PsiBridgeService.isChatToolWindowActive(project)) {
                            tw.show();
                        } else {
                            tw.activate(null);
                        }
                    }

                    PlatformApiCompat.showRevisionInLogAfterRefresh(project, fullHash, repoRoot);
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // best-effort UI follow-along
            }
        });
    }

    /**
     * Extracts the first full commit hash from git output and navigates to it in the VCS Log tab.
     * Uses {@link PlatformApiCompat#showRevisionInLogAfterRefresh} to wait for the VCS log to
     * index the commit before navigating — avoids "commit could not be found" errors.
     *
     * @param repoRoot absolute path of the repository the git command was run in;
     *                 routes the navigation to the correct repo in multi-repo projects
     */
    protected void showFirstCommitInLog(@NotNull String repoRoot, String gitOutput) {
        if (!ToolLayerSettings.getInstance(project).getFollowAgentFiles()) return;
        if (gitOutput == null || gitOutput.isEmpty()) return;
        String hash = extractFirstCommitHash(gitOutput);
        if (hash == null) return;
        EdtUtil.invokeLater(() -> {
            try {
                PlatformApiCompat.showRevisionInLogAfterRefresh(project, hash, repoRoot);
            } catch (Exception ignored) {
                // best-effort UI follow-along
            }
        });
    }

    // ── Utility ──────────────────────────────────────────────

    /**
     * Converts an absolute path to a path relative to {@code basePath}.
     * Returns {@code "."} when they are equal, preserves absolute path when
     * {@code basePath} is null or {@code absPath} is not under it.
     */
    static String toRelativePath(@NotNull String absPath, @Nullable String basePath) {
        if (basePath == null) return absPath;
        if (absPath.equals(basePath)) return ".";
        if (absPath.startsWith(basePath + "/")) return absPath.substring(basePath.length() + 1);
        return absPath;
    }
}
