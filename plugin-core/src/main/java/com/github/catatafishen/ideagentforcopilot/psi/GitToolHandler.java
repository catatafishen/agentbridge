package com.github.catatafishen.ideagentforcopilot.psi;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Git infrastructure — process execution, VCS refresh, IDE follow-along helpers.
 * Individual git command implementations live in the OO tool classes under
 * {@code com.github.catatafishen.ideagentforcopilot.psi.tools.git}.
 */
@SuppressWarnings("java:S112") // generic exceptions are caught at the JSON-RPC dispatch level
public final class GitToolHandler {

    /**
     * Git subcommands that modify the repository and require a VCS refresh.
     */
    private static final Set<String> WRITE_COMMANDS = Set.of(
        "add", "branch", "checkout", "cherry-pick", "commit", "fetch", "merge",
        "pull", "push", "rebase", "remote", "reset", "restore",
        "revert", "stash", "switch", "tag"
    );

    private static final Pattern FULL_HASH_PATTERN =
        Pattern.compile("\\b[0-9a-f]{40}\\b");
    private static final Pattern COMMIT_LINE_PATTERN =
        Pattern.compile("^commit ([0-9a-f]{40})$", Pattern.MULTILINE);

    private final Project project;
    private final FileTools fileTools;

    GitToolHandler(Project project, FileTools fileTools) {
        this.project = project;
        this.fileTools = fileTools;
    }

    /**
     * Convenience method: flush pending auto-format and save all documents to disk.
     * Called by git tool classes before commands that need the working tree up-to-date.
     */
    public void flushAndSave() {
        fileTools.flushPendingAutoFormat();
        saveAllDocuments();
    }

    /**
     * Run a git command, preferring IntelliJ's Git4Idea infrastructure for proper VCS integration.
     * Falls back to ProcessBuilder if Git4Idea is unavailable or the command is not mapped.
     */
    public String runGit(String... args) throws Exception {
        if (args.length == 0) return "Error: no git command";

        String result;
        try {
            result = IdeGitSupport.run(project, args);
            if (result == null) {
                result = runGitProcess(args);
            }
        } catch (NoClassDefFoundError e) {
            // Git4Idea plugin not available — fall back to ProcessBuilder
            result = runGitProcess(args);
        }

        if (WRITE_COMMANDS.contains(args[0])) {
            refreshVcsState();
        }

        return result;
    }

    /**
     * Fallback: run git via ProcessBuilder (bypasses IntelliJ VCS layer).
     */
    private String runGitProcess(String... args) throws Exception {
        String basePath = project.getBasePath();
        if (basePath == null) return "Error: no project base path";

        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("--no-pager");
        cmd.addAll(Arrays.asList(args));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new java.io.File(basePath));
        pb.redirectErrorStream(false);
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

    /**
     * Refresh IntelliJ's VCS state after git write operations so the Git tool window
     * (Changes tab, Log tab, branch indicator) updates in real time.
     */
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

    /**
     * Flush all IntelliJ editor buffers to disk so git sees current content.
     * Commits any pending PSI changes first to catch async reformats from external tools.
     */
    public void saveAllDocuments() {
        EdtUtil.invokeAndWait(() ->
            com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction(() -> {
                com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments();
                com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments();
            }));
    }

    /**
     * After a successful commit, open the Git Log tab and navigate to the
     * new HEAD commit. Runs the hash lookup on a pooled thread and navigates
     * only after the log has indexed the new commit, avoiding the
     * "Commit or reference 'xxx' not found" notification.
     * Respects the "Follow Agent" setting — does nothing if disabled.
     */
    public void showNewCommitInLog() {
        if (!ToolLayerSettings.getInstance(project).getFollowAgentFiles()) return;

        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                String fullHash = runGit("rev-parse", "HEAD").trim();
                if (fullHash.length() != 40) return;

                EdtUtil.invokeLater(() -> {
                    var twm = com.intellij.openapi.wm.ToolWindowManager.getInstance(project);
                    var tw = twm.getToolWindow("Version Control");
                    if (tw != null) tw.activate(null);

                    PlatformApiCompat.showRevisionInLogAfterRefresh(project, fullHash);
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // best-effort — fall back silently
            }
        });
    }

    /**
     * Extracts the first full commit hash from a git-log result and navigates
     * to it in the VCS Log tab, so the user can follow what the agent is reading.
     * Respects the "Follow Agent" setting — does nothing if disabled.
     */
    public void showFirstCommitInLog(String gitOutput) {
        if (!ToolLayerSettings.getInstance(project).getFollowAgentFiles()) return;
        if (gitOutput == null || gitOutput.isEmpty()) return;
        String hash = null;
        var m = COMMIT_LINE_PATTERN.matcher(gitOutput);
        if (m.find()) {
            hash = m.group(1);
        } else {
            var m2 = FULL_HASH_PATTERN.matcher(gitOutput);
            if (m2.find()) {
                hash = m2.group();
            }
        }
        if (hash == null) return;
        String finalHash = hash;
        EdtUtil.invokeLater(() -> {
            try {
                PlatformApiCompat.showRevisionInLog(project, finalHash);
            } catch (Exception ignored) {
                // best-effort UI follow-along
            }
        });
    }

    /**
     * Delegates to {@link PlatformApiCompat#runIdeGitCommand} which isolates all Git4Idea API
     * calls that produce false-positive errors in the IDE editor.
     */
    private static final class IdeGitSupport {
        static String run(Project project, String[] args) {
            return PlatformApiCompat.runIdeGitCommand(project, args);
        }
    }
}
