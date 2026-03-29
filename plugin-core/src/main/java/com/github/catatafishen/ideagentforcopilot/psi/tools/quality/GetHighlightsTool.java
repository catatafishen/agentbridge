package com.github.catatafishen.ideagentforcopilot.psi.tools.quality;

import com.github.catatafishen.ideagentforcopilot.psi.EdtUtil;
import com.github.catatafishen.ideagentforcopilot.psi.PlatformApiCompat;
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

    private static final String PARAM_INCLUDE_UNINDEXED = "include_unindexed";

    public GetHighlightsTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "get_highlights";
    }

    @Override
    public @NotNull String displayName() {
        return "Get Highlights";
    }

    @Override
    public @NotNull String description() {
        return "Get cached editor highlights for open files";
    }

    @Override
    public @NotNull String kind() {
        return "read";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
            {"path", TYPE_STRING, "Optional: file path to check. If omitted, checks all open files", ""},
            {PARAM_LIMIT, TYPE_INTEGER, "Maximum number of highlights to return (default: 100)"},
            {PARAM_INCLUDE_UNINDEXED, TYPE_BOOLEAN, "If true, also include highlights from files not indexed by the project (default: false)"}
        });
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String pathStr = args.has("path") ? args.get("path").getAsString() : null;
        int limit = args.has(PARAM_LIMIT) ? args.get(PARAM_LIMIT).getAsInt() : 100;
        boolean includeUnindexed = args.has(PARAM_INCLUDE_UNINDEXED) && args.get(PARAM_INCLUDE_UNINDEXED).getAsBoolean();

        if (!project.isInitialized()) {
            return ERROR_IDE_INITIALIZING;
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
                List<String> notifications = PlatformApiCompat.collectEditorNotificationTexts(project, vf, editor);

                future.complete(notifications);
            } catch (Exception e) {
                future.complete(Collections.emptyList());
            }
        });
        return future.get(10, TimeUnit.SECONDS);
    }
}
