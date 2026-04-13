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
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Abstract base for git tools. Provides git process execution, VCS refresh,
 * branch context enrichment, auto-fetch throttling, and IDE follow-along helpers.
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

    protected static final long FETCH_THROTTLE_MS = 60_000;
    protected static final AtomicLong lastFetchTime = new AtomicLong(0);

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

    // ── Branch context enrichment ────────────────────────────

    /**
     * Gathers current branch state metadata for appending to tool responses.
     * Runs ~5 lightweight git commands, each fail-safe (omitted on error).
     * Typical latency: &lt;200ms total.
     *
     * @return formatted context block, or empty string if branch detection fails
     */
    protected String getBranchContext() {
        StringBuilder ctx = new StringBuilder();

        String branch = runGitQuiet("rev-parse", "--abbrev-ref", "HEAD");
        if (branch == null) return "";

        ctx.append("\n\n--- Context ---\n");
        ctx.append("On branch: ").append(branch).append('\n');

        String tracking = runGitQuiet("rev-parse", "--abbrev-ref", "@{upstream}");
        if (tracking != null) {
            ctx.append("Tracking: ").append(tracking);
            appendAheadBehind(ctx, tracking);
            ctx.append('\n');
        } else {
            ctx.append("Tracking: none (no upstream set — use git_push with set_upstream: true)\n");
        }

        appendDivergenceFromDefault(ctx, branch);
        appendWorkingTreeStatus(ctx);
        appendStashCount(ctx);

        return ctx.toString();
    }

    /**
     * Returns a compact one-line branch summary (for tools that want less verbosity).
     */
    protected String getBranchSummary() {
        String branch = runGitQuiet("rev-parse", "--abbrev-ref", "HEAD");
        if (branch == null) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("\nBranch: ").append(branch);

        String tracking = runGitQuiet("rev-parse", "--abbrev-ref", "@{upstream}");
        if (tracking != null) {
            appendAheadBehind(sb, tracking);
        }
        return sb.toString();
    }

    private void appendAheadBehind(StringBuilder ctx, String tracking) {
        String ahead = runGitQuiet("rev-list", "--count", tracking + "..HEAD");
        String behind = runGitQuiet("rev-list", "--count", "HEAD.." + tracking);
        if (ahead != null && behind != null) {
            ctx.append(" (ahead ").append(ahead).append(", behind ").append(behind).append(')');
        }
    }

    private void appendDivergenceFromDefault(StringBuilder ctx, String currentBranch) {
        String defaultBranch = detectDefaultBranch();
        if (defaultBranch == null || defaultBranch.equals(currentBranch)) return;

        String count = runGitQuiet("rev-list", "--count", defaultBranch + "..HEAD");
        if (count != null && !"0".equals(count)) {
            ctx.append("Branch has ").append(count)
                .append(" commit(s) since ").append(defaultBranch).append('\n');
        }
    }

    private void appendWorkingTreeStatus(StringBuilder ctx) {
        String porcelain = runGitQuiet("status", "--porcelain");
        if (porcelain == null) return;

        if (porcelain.isEmpty()) {
            ctx.append("Working tree: clean\n");
            return;
        }

        ctx.append("Working tree: ").append(formatPorcelainStatus(porcelain)).append('\n');
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

    private void appendStashCount(StringBuilder ctx) {
        String stashList = runGitQuiet("stash", "list");
        if (stashList == null || stashList.isEmpty()) return;
        long count = countStashEntries(stashList);
        if (count > 0) {
            ctx.append("Stash: ").append(count).append(" entr").append(count == 1 ? "y" : "ies").append('\n');
        }
    }

    /**
     * Counts the number of stash entries from {@code git stash list} output. Pure function.
     */
    static long countStashEntries(String stashList) {
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
     * Detects the default branch (origin/main or origin/master).
     */
    @Nullable
    protected String detectDefaultBranch() {
        String symbolic = runGitQuiet("symbolic-ref", "refs/remotes/origin/HEAD");
        if (symbolic != null) {
            return symbolic.replace("refs/remotes/", "");
        }
        // Fallback: check common names
        String branches = runGitQuiet("branch", "-r", "--list", "origin/main", "origin/master");
        if (branches == null) return null;
        if (branches.contains("origin/main")) return "origin/main";
        if (branches.contains("origin/master")) return "origin/master";
        return null;
    }

    // ── Auto-fetch throttling ────────────────────────────────

    /**
     * Fetches from origin if the last fetch was more than 60 seconds ago.
     * Returns a note about what was fetched, or empty string if throttled/failed.
     * This prevents agents from working with stale remote refs.
     */
    protected String autoFetchIfStale() {
        long now = System.currentTimeMillis();
        long last = lastFetchTime.get();
        if (now - last < FETCH_THROTTLE_MS) return "";

        if (!lastFetchTime.compareAndSet(last, now)) return "";

        try {
            String result = runGit("fetch", "--quiet", "origin");
            if (result != null && !result.isBlank() && !result.startsWith("Error")) {
                return "(auto-fetched latest from origin)\n";
            }
            return "";
        } catch (Exception e) {
            return "";
        }
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

    // ── Run git (quiet variant for metadata) ─────────────────

    /**
     * Runs a git command and returns trimmed stdout, or null on any error.
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
     * Run a git command, preferring IntelliJ's Git4Idea infrastructure.
     * Falls back to ProcessBuilder if Git4Idea is unavailable.
     */
    protected String runGit(String... args) throws Exception {
        if (args.length == 0) return "Error: no git command";

        String result;
        try {
            result = PlatformApiCompat.runIdeGitCommand(project, args);
            if (result == null) {
                result = runGitProcess(args);
            }
        } catch (NoClassDefFoundError e) {
            result = runGitProcess(args);
        }

        if (WRITE_COMMANDS.contains(args[0])) {
            refreshVcsState();
        }

        return result;
    }

    private String runGitProcess(String... args) throws Exception {
        String basePath = project.getBasePath();
        if (basePath == null) return "Error: no project base path";

        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("--no-pager");
        cmd.addAll(Arrays.asList(args));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new File(basePath));
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

    private void refreshVcsState() {
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
     * After a successful commit, open the Git Log tab and navigate to HEAD.
     */
    protected void showNewCommitInLog() {
        if (!ToolLayerSettings.getInstance(project).getFollowAgentFiles()) return;

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                String fullHash = runGit("rev-parse", "HEAD").trim();
                if (fullHash.length() != 40) return;

                EdtUtil.invokeLater(() -> {
                    if (!PsiBridgeService.isChatToolWindowActive(project)) {
                        var twm = com.intellij.openapi.wm.ToolWindowManager.getInstance(project);
                        var tw = twm.getToolWindow(com.intellij.openapi.wm.ToolWindowId.VCS);
                        if (tw != null) tw.activate(null);
                    }

                    PlatformApiCompat.showRevisionInLogAfterRefresh(project, fullHash);
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
     */
    protected void showFirstCommitInLog(String gitOutput) {
        if (!ToolLayerSettings.getInstance(project).getFollowAgentFiles()) return;
        if (gitOutput == null || gitOutput.isEmpty()) return;
        String hash = extractFirstCommitHash(gitOutput);
        if (hash == null) return;
        String finalHash = hash;
        EdtUtil.invokeLater(() -> {
            try {
                PlatformApiCompat.showRevisionInLogAfterRefresh(project, finalHash);
            } catch (Exception ignored) {
                // best-effort UI follow-along
            }
        });
    }
}
