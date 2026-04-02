package com.github.catatafishen.ideagentforcopilot.psi.tools.git;

import com.github.catatafishen.ideagentforcopilot.psi.EdtUtil;
import com.github.catatafishen.ideagentforcopilot.psi.PlatformApiCompat;
import com.github.catatafishen.ideagentforcopilot.psi.ToolLayerSettings;
import com.github.catatafishen.ideagentforcopilot.psi.tools.Tool;
import com.github.catatafishen.ideagentforcopilot.psi.tools.file.FileTool;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.github.catatafishen.ideagentforcopilot.ui.renderers.GitOperationRenderer;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.psi.PsiDocumentManager;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Abstract base for git tools. Provides git process execution, VCS refresh,
 * and IDE follow-along helpers previously in GitToolHandler.
 */
@SuppressWarnings("java:S112") // generic exceptions caught at JSON-RPC dispatch level
public abstract class GitTool extends Tool {

    private static final Set<String> WRITE_COMMANDS = Set.of(
        "add", "branch", "checkout", "cherry-pick", "commit", "fetch", "merge",
        "pull", "push", "rebase", "remote", "reset", "restore",
        "revert", "stash", "switch", "tag"
    );

    private static final Pattern FULL_HASH_PATTERN =
        Pattern.compile("\\b[0-9a-f]{40}\\b");
    private static final Pattern COMMIT_LINE_PATTERN =
        Pattern.compile("^commit ([0-9a-f]{40})$", Pattern.MULTILINE);

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
                    var twm = com.intellij.openapi.wm.ToolWindowManager.getInstance(project);
                    var tw = twm.getToolWindow("Version Control");
                    if (tw != null) tw.activate(null);

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
     */
    protected void showFirstCommitInLog(String gitOutput) {
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
}
