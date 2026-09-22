package com.github.catatafishen.agentbridge.psi.tools.quality;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Fast compilation error check using cached daemon results.
 * Much faster than build_project since it uses cached daemon analysis results.
 */
public final class GetCompilationErrorsTool extends QualityTool {

    private static final Logger LOG = Logger.getInstance(GetCompilationErrorsTool.class);
    private static final long ANALYSIS_FRESHNESS_TIMEOUT_SECONDS = 15;

    public GetCompilationErrorsTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "get_compilation_errors";
    }

    @Override
    public @NotNull String displayName() {
        return "Get Compilation Errors";
    }

    @Override
    public @NotNull String description() {
        return "Fast compilation error check using cached daemon results. "
            + "Lightweight alternative to build_project — checks open files instantly without "
            + "triggering a full build. Use after edits to verify no compilation errors.";
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
            Param.optional("path", TYPE_STRING, "Optional: specific file to check. If omitted, checks all open source files", "")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String pathStr = args.has("path") ? args.get("path").getAsString() : null;

        if (!project.isInitialized()) {
            return ERROR_IDE_INITIALIZING;
        }

        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                collectCompilationErrors(pathStr, resultFuture);
            } catch (Exception e) {
                LOG.error("Error getting compilation errors", e);
                resultFuture.complete("Error getting compilation errors: " + e.getMessage());
            }
        });
        return resultFuture.get(30, TimeUnit.SECONDS);
    }

    private void collectCompilationErrors(String pathStr, CompletableFuture<String> resultFuture) {
        Collection<VirtualFile> files = com.intellij.openapi.application.ReadAction.compute(() -> {
            ProjectFileIndex fileIndex = ProjectRootManager.getInstance(project).getFileIndex();
            return collectFilesForCompilationAnalysis(pathStr, fileIndex, resultFuture);
        });
        if (resultFuture.isDone()) return;

        VirtualFile explicitFile = pathStr != null && !pathStr.isEmpty() && files.size() == 1
            ? files.iterator().next()
            : null;
        FreshAnalysisResult analysisResult = awaitFreshErrorAnalysis(files, explicitFile);
        if (analysisResult.unavailableResult() != null) {
            resultFuture.complete(analysisResult.unavailableResult());
            return;
        }
        if (!analysisResult.pendingFiles().isEmpty()) {
            resultFuture.complete(formatPendingAnalysisResult(analysisResult.pendingFiles().size()));
            return;
        }

        ApplicationManager.getApplication().runReadAction(() -> {
            String basePath = project.getBasePath();
            List<String> errors = new ArrayList<>();
            int filesWithErrors = 0;

            for (VirtualFile vf : files) {
                boolean hasErrors = collectFileErrors(vf, basePath, errors);
                if (hasErrors) filesWithErrors++;
            }

            if (errors.isEmpty()) {
                resultFuture.complete(String.format("No compilation errors in %d files checked.", files.size()));
            } else {
                String summary = String.format("Found %d compilation errors across %d files:%n%n",
                    errors.size(), filesWithErrors);
                resultFuture.complete(summary + String.join("\n", errors));
            }
        });
    }

    private Collection<VirtualFile> collectFilesForCompilationAnalysis(
        String pathStr, ProjectFileIndex fileIndex, CompletableFuture<String> resultFuture) {
        if (pathStr != null && !pathStr.isEmpty()) {
            return collectFilesForHighlightAnalysis(pathStr, false, fileIndex, resultFuture);
        }

        List<VirtualFile> files = new ArrayList<>();
        for (VirtualFile vf : FileEditorManager.getInstance(project).getOpenFiles()) {
            if (fileIndex.isInSourceContent(vf)) {
                files.add(vf);
            }
        }
        return files;
    }

    /**
     * Starts a new error-highlighting pass and waits for its completion before cached highlights
     * are read. This avoids returning errors retained from the pass that preceded a project-model
     * or document update.
     */
    private FreshAnalysisResult awaitFreshErrorAnalysis(Collection<VirtualFile> files,
                                                        VirtualFile explicitFile) {
        Set<VirtualFile> watchedFiles = new HashSet<>(files);
        Semaphore daemonFinished = new Semaphore(0);
        Runnable disconnect = PlatformApiCompat.subscribeDaemonListener(project,
            new com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.DaemonListener() {
                @Override
                public void daemonFinished(
                    @NotNull Collection<? extends com.intellij.openapi.fileEditor.FileEditor> fileEditors) {
                    if (fileEditors.stream().anyMatch(editor -> watchedFiles.contains(editor.getFile()))) {
                        daemonFinished.release();
                    }
                }
            });

        ToolLayerSettings settings = ToolLayerSettings.getInstance(project);
        boolean followAgentFiles = settings.getFollowAgentFiles();
        boolean allowTransientFileOpens = settings.getAllowTransientFileOpens();
        boolean openedByTool = false;
        List<VirtualFile> analyzedFiles = new ArrayList<>(files);
        try {
            if (explicitFile != null) {
                EditorOpenResult openResult = openExplicitFileForAnalysis(
                    explicitFile, followAgentFiles, allowTransientFileOpens);
                if (!openResult.isOpen()) {
                    return new FreshAnalysisResult(List.of(), allowTransientFileOpens || followAgentFiles
                        ? formatEditorOpenFailedResult()
                        : formatTransientOpenDisabledResult());
                }
                openedByTool = openResult.openedByTool();
            }

            analyzedFiles = restartErrorAnalysis(files);
            if (analyzedFiles.isEmpty()) return new FreshAnalysisResult(analyzedFiles, null);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(ANALYSIS_FRESHNESS_TIMEOUT_SECONDS);
            while (true) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0 || !daemonFinished.tryAcquire(remainingNanos, TimeUnit.NANOSECONDS)) {
                    return new FreshAnalysisResult(analyzedFiles, null);
                }
                List<VirtualFile> pendingFiles = findFilesWithPendingErrorAnalysis(analyzedFiles);
                if (pendingFiles.isEmpty()) return new FreshAnalysisResult(pendingFiles, null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.info("Interrupted while waiting for daemon analysis");
        } catch (Exception e) {
            LOG.info("Failed to refresh daemon analysis: " + e.getMessage());
        } finally {
            disconnect.run();
            if (openedByTool && !followAgentFiles) {
                closeFileOpenedForAnalysis(explicitFile);
            }
        }
        return new FreshAnalysisResult(analyzedFiles, null);
    }

    /**
     * Opens an explicitly requested file only after the daemon listener is installed, so the
     * daemon pass caused by opening it cannot be missed.
     */
    private EditorOpenResult openExplicitFileForAnalysis(
        VirtualFile file, boolean followAgentFiles, boolean allowTransientFileOpens) {
        CompletableFuture<EditorOpenResult> result = new CompletableFuture<>();
        EdtUtil.invokeLater(() -> {
            try {
                FileEditorManager editorManager = FileEditorManager.getInstance(project);
                if (editorManager.isFileOpen(file)) {
                    result.complete(new EditorOpenResult(true, false));
                    return;
                }
                if (!followAgentFiles && !allowTransientFileOpens) {
                    result.complete(new EditorOpenResult(false, false));
                    return;
                }
                PlatformApiCompat.edtReadAction(() -> editorManager.openFile(file, false));
                result.complete(new EditorOpenResult(editorManager.isFileOpen(file), true));
            } catch (Exception e) {
                LOG.info("Failed to open file for compilation analysis: " + e.getMessage());
                result.complete(new EditorOpenResult(false, false));
            }
        });
        try {
            return result.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.info("Timed out opening file for compilation analysis: " + e.getMessage());
        }
        return new EditorOpenResult(false, false);
    }

    private void closeFileOpenedForAnalysis(VirtualFile file) {
        EdtUtil.invokeLater(() -> FileEditorManager.getInstance(project).closeFile(file));
    }

    private record FreshAnalysisResult(List<VirtualFile> pendingFiles, String unavailableResult) {
    }

    private record EditorOpenResult(boolean isOpen, boolean openedByTool) {
    }

    private List<VirtualFile> restartErrorAnalysis(Collection<VirtualFile> files) {
        return com.intellij.openapi.application.ReadAction.compute(() -> {
            var analyzer = com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project);
            List<VirtualFile> analyzedFiles = new ArrayList<>();
            for (VirtualFile vf : files) {
                Document doc = FileDocumentManager.getInstance().getDocument(vf);
                PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
                if (doc != null && psiFile != null) {
                    analyzer.restart(psiFile);
                    analyzedFiles.add(vf);
                }
            }
            return analyzedFiles;
        });
    }

    private List<VirtualFile> findFilesWithPendingErrorAnalysis(Collection<VirtualFile> files) {
        return com.intellij.openapi.application.ReadAction.compute(() -> {
            var analyzer = com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx.getInstanceEx(project);
            List<VirtualFile> pendingFiles = new ArrayList<>();
            for (VirtualFile vf : files) {
                Document doc = FileDocumentManager.getInstance().getDocument(vf);
                PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
                if (doc != null && psiFile != null && !analyzer.isErrorAnalyzingFinished(psiFile)) {
                    pendingFiles.add(vf);
                }
            }
            return pendingFiles;
        });
    }

    static String formatPendingAnalysisResult(int pendingFileCount) {
        return String.format(
            "Analysis pending for %d file(s): cached compilation diagnostics were not read because IntelliJ "
                + "error analysis did not finish within %d seconds. Wait for IDE code analysis to finish and "
                + "call get_compilation_errors again; if it remains pending, run build_project.",
            pendingFileCount, ANALYSIS_FRESHNESS_TIMEOUT_SECONDS);
    }

    static String formatTransientOpenDisabledResult() {
        return "Compilation diagnostics unavailable for the requested file: it is not open in the IDE editor, "
            + "and temporary file opens are disabled while Follow Agent Files is off. Cached diagnostics were "
            + "not read. Enable 'Open files temporarily for code quality data' in AgentBridge → UI/UX settings, "
            + "enable Follow Agent Files, or run build_project.";
    }

    static String formatEditorOpenFailedResult() {
        return "Compilation diagnostics unavailable for the requested file because IntelliJ could not open it "
            + "in an editor for daemon analysis. Cached diagnostics were not read; run build_project for a "
            + "definitive result.";
    }

    private boolean collectFileErrors(VirtualFile vf, String basePath, List<String> errors) {
        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (doc == null) return false;

        String relPath = basePath != null ? relativize(basePath, vf.getPath()) : vf.getName();
        List<com.intellij.codeInsight.daemon.impl.HighlightInfo> highlights = new ArrayList<>();
        com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx.processHighlights(
            doc, project, null, 0, doc.getTextLength(), highlights::add);

        boolean fileHasErrors = false;
        for (var h : highlights) {
            if (h.getDescription() != null
                && h.getSeverity() == com.intellij.lang.annotation.HighlightSeverity.ERROR) {
                int line = doc.getLineNumber(h.getStartOffset()) + 1;
                errors.add(String.format(FORMAT_LOCATION, relPath, line, "ERROR", h.getDescription()));
                fileHasErrors = true;
            }
        }
        return fileHasErrors;
    }
}
