package com.github.catatafishen.agentbridge.psi.tools.quality;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Gets cached editor highlights for open files.
 */
public final class GetHighlightsTool extends QualityTool {

    private static final Logger LOG = Logger.getInstance(GetHighlightsTool.class);
    private static final String REVIEW_PENDING_AGENT_NOTE =
        "[REVIEW_PENDING] User review is still pending for this file. "
            + "Agent git commit/push/amend must wait for approval or rejection in the Review panel.";

    private static final String PARAM_INCLUDE_UNINDEXED = "include_unindexed";

    public GetHighlightsTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "get_highlights";
    }

    @Override
    public boolean requiresIndex() {
        return true;
    }

    @Override
    public @NotNull String displayName() {
        return "Get Highlights";
    }

    @Override
    public @NotNull String description() {
        return "Get cached editor highlights for open files. Richer than get_problems — includes inspections, intentions, " +
            "and quick-fix action names. Use get_compilation_errors for a fast compile-error-only check.";
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
            Param.optional("path", TYPE_STRING, "Optional: file path to check. If omitted, checks all open files", ""),
            Param.optional(PARAM_LIMIT, TYPE_INTEGER, "Maximum number of highlights to return (default: 100)"),
            Param.optional(PARAM_INCLUDE_UNINDEXED, TYPE_BOOLEAN, "If true, also include highlights from files not indexed by the project (default: false)")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String pathStr = args.has("path") ? args.get("path").getAsString() : null;
        int limit = args.has(PARAM_LIMIT) ? args.get(PARAM_LIMIT).getAsInt() : 100;
        boolean includeUnindexed = args.has(PARAM_INCLUDE_UNINDEXED) && args.get(PARAM_INCLUDE_UNINDEXED).getAsBoolean();

        if (!project.isInitialized()) {
            return ERROR_IDE_INITIALIZING;
        }

        // For single-file queries, ensure the file is open in an editor so the daemon
        // produces highlights. Without this, files edited with follow-agent mode off
        // may never get daemon analysis.
        if (pathStr != null && !pathStr.isEmpty()) {
            VirtualFile vf = resolveVirtualFile(pathStr);
            if (vf != null) {
                ensureDaemonAnalyzed(vf);
            }
        }

        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                getHighlightsCached(pathStr, limit, includeUnindexed, resultFuture);
            } catch (Exception e) {
                LOG.error("Error getting highlights", e);
                resultFuture.complete("Error getting highlights: " + e.getMessage());
            }
        });
        return resultFuture.get(30, TimeUnit.SECONDS);
    }

    private void getHighlightsCached(String pathStr, int limit, boolean includeUnindexed,
                                     CompletableFuture<String> resultFuture) {
        StringBuilder result = new StringBuilder();
        ApplicationManager.getApplication().runReadAction(() -> {
            ProjectFileIndex fileIndex = ProjectRootManager.getInstance(project).getFileIndex();
            Collection<VirtualFile> allFiles =
                collectFilesForHighlightAnalysis(pathStr, includeUnindexed, fileIndex, resultFuture);
            if (resultFuture.isDone()) return;

            LOG.info("Analyzing " + allFiles.size() + " files for highlights (cached mode)");

            List<String> problems = new ArrayList<>();
            int[] counts = analyzeFilesForHighlights(allFiles, limit, problems);

            if (problems.isEmpty()) {
                result.append(String.format("No highlights found in %d files analyzed (0 files with issues). " +
                        "Note: This reads cached daemon analysis results from already-analyzed files. " +
                        "For comprehensive code quality analysis, use run_inspections instead.",
                    allFiles.size()));
            } else {
                result.append(String.format("Found %d problems across %d files (showing up to %d):%n%n",
                    counts[0], counts[1], limit));
                result.append(String.join("\n", problems));
            }
        });
        if (resultFuture.isDone()) return;

        // Collect editor notifications (needs EDT for Swing components)
        if (pathStr != null && !pathStr.isEmpty()) {
            try {
                List<String> notifications = collectEditorNotifications(pathStr);
                if (!notifications.isEmpty()) {
                    result.append("\n\n--- Editor Notifications ---\n");
                    result.append(String.join("\n", notifications));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.info("Interrupted while collecting editor notifications: " + e.getMessage());
            } catch (ExecutionException | TimeoutException e) {
                LOG.info("Failed to collect editor notifications: " + e.getMessage());
            }
        }

        resultFuture.complete(result.toString());
    }

    /**
     * Ensures the target file is open in an editor and daemon analysis has completed.
     * When follow-agent mode is off, files may be edited programmatically without ever
     * being opened in an editor tab, so the daemon never produces highlights for them.
     * This method opens the file silently (no focus), waits for the daemon to finish,
     * then closes the file again if follow-agent mode is off (to avoid polluting the editor).
     */
    private void ensureDaemonAnalyzed(@NotNull VirtualFile vf) {
        // Check on EDT whether the file is already open
        CompletableFuture<Boolean> openCheck = new CompletableFuture<>();
        EdtUtil.invokeLater(() ->
            openCheck.complete(
                com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).isFileOpen(vf)));
        boolean alreadyOpen;
        try {
            alreadyOpen = openCheck.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception e) {
            return;
        }
        if (alreadyOpen) return;

        boolean followAgent = ToolLayerSettings.getInstance(project).getFollowAgentFiles();

        // Subscribe BEFORE opening so we don't miss the daemon pass
        var latch = new java.util.concurrent.CountDownLatch(1);
        Runnable disconnect = PlatformApiCompat.subscribeDaemonListener(project,
            new com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.DaemonListener() {
                @Override
                public void daemonFinished(
                    @NotNull java.util.Collection<? extends com.intellij.openapi.fileEditor.FileEditor> fileEditors) {
                    if (fileEditors.stream().anyMatch(fe -> vf.equals(fe.getFile()))) {
                        latch.countDown();
                    }
                }
            });
        try {
            CompletableFuture<Void> opened = new CompletableFuture<>();
            EdtUtil.invokeLater(() -> {
                try {
                    com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                        .openFile(vf, false);
                    com.intellij.psi.PsiFile psiFile =
                        com.intellij.psi.PsiManager.getInstance(project).findFile(vf);
                    if (psiFile != null) {
                        com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project)
                            .restart(psiFile, "get_highlights: file opened for analysis");
                    }
                } finally {
                    opened.complete(null);
                }
            });
            opened.get(5, TimeUnit.SECONDS);

            if (!latch.await(5, TimeUnit.SECONDS)) {
                LOG.info("get_highlights: daemon analysis timed out for " + vf.getPath());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.info("get_highlights: ensureDaemonAnalyzed failed for " + vf.getPath()
                + ": " + e.getMessage());
        } finally {
            disconnect.run();
            // Close the file again if we opened it and follow-agent mode is off,
            // to avoid leaving stale editor tabs from silent background analysis.
            if (!followAgent) {
                EdtUtil.invokeLater(() ->
                    com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                        .closeFile(vf));
            }
        }
    }

    private int[] analyzeFilesForHighlights(Collection<VirtualFile> files, int limit, List<String> problems) {
        String basePath = project.getBasePath();
        int totalCount = 0;
        int filesWithProblems = 0;
        for (VirtualFile vf : files) {
            if (totalCount >= limit) break;
            Document doc = FileDocumentManager.getInstance().getDocument(vf);
            if (doc != null) {
                String relPath = basePath != null ? relativize(basePath, vf.getPath()) : vf.getName();
                int added = collectFileHighlights(doc, relPath, limit - totalCount, problems);
                if (added > 0) filesWithProblems++;
                totalCount += added;
            }
        }
        return new int[]{totalCount, filesWithProblems};
    }

    private int collectFileHighlights(Document doc, String relPath, int remaining, List<String> problems) {
        List<com.intellij.codeInsight.daemon.impl.HighlightInfo> highlights = new ArrayList<>();
        int added = 0;
        try {
            com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx.processHighlights(
                doc, project, null, 0, doc.getTextLength(), highlights::add);

            for (var h : highlights) {
                if (added >= remaining) break;
                var severity = h.getSeverity();
                if (h.getDescription() != null
                    && severity != com.intellij.lang.annotation.HighlightSeverity.INFORMATION
                    && severity.myVal >= com.intellij.lang.annotation.HighlightSeverity.WEAK_WARNING.myVal) {
                    int line = doc.getLineNumber(h.getStartOffset()) + 1;
                    StringBuilder entry = new StringBuilder(
                        String.format(FORMAT_LOCATION, relPath, line, severity.getName(), h.getDescription()));
                    List<String> fixes = collectQuickFixNames(h);
                    // One fix per line with plain "Fix:" prefix so action names are unambiguous
                    for (String fix : fixes) {
                        entry.append("\n    Fix: ").append(fix);
                    }
                    problems.add(entry.toString());
                    added++;
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to analyze file: " + relPath, e);
        }
        return added;
    }

    /**
     * Collects editor notification banners for a file.
     * Must be called outside a read action since it dispatches to EDT for Swing component creation.
     */
    private List<String> collectEditorNotifications(String pathStr) throws ExecutionException, InterruptedException, TimeoutException {
        CompletableFuture<List<String>> future = new CompletableFuture<>();
        EdtUtil.invokeLater(() -> {
            try {
                VirtualFile vf = resolveVirtualFile(pathStr);
                if (vf == null) {
                    future.complete(Collections.emptyList());
                    return;
                }

                var fem = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project);
                var editors = fem.getEditors(vf);
                if (editors.length == 0) {
                    future.complete(Collections.emptyList());
                    return;
                }

                var editor = editors[0];
                List<String> notifications = PlatformApiCompat.collectEditorNotificationTexts(project, vf, editor)
                    .stream()
                    .map(GetHighlightsTool::formatEditorNotificationForAgent)
                    .toList();

                future.complete(notifications);
            } catch (Exception e) {
                future.complete(Collections.emptyList());
            }
        });
        return future.get(10, TimeUnit.SECONDS);
    }

    static @NotNull String formatEditorNotificationForAgent(@NotNull String notification) {
        if (!notification.startsWith("[BANNER] Review pending:")) {
            return notification;
        }
        return notification + "\n" + REVIEW_PENDING_AGENT_NOTE;
    }
}
