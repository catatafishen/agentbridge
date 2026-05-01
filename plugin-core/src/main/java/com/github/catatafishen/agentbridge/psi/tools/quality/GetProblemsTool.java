package com.github.catatafishen.agentbridge.psi.tools.quality;

import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Gets cached editor problems (errors/warnings) for open files.
 */
public final class GetProblemsTool extends QualityTool {

    public GetProblemsTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "get_problems";
    }

    @Override
    public boolean requiresIndex() {
        return true;
    }

    @Override
    public @NotNull String displayName() {
        return "Get Problems";
    }

    @Override
    public @NotNull String description() {
        return "Get cached editor problems (errors/warnings) for open files. Returns severity, message, and available quick-fixes per problem. " +
            "For files NOT open in an editor, falls back to public batch code-smell analysis " +
            "(weak warnings and intentions are only available when the file is open). " +
            "Use get_compilation_errors for a faster check focused on compile errors only. " +
            "Use get_highlights for richer diagnostics including inspections, typos, and intentions.";
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
            Param.optional("path", TYPE_STRING, "Optional: file path to check. If omitted, checks all open files", "")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String pathStr = args.has("path") ? args.get("path").getAsString() : "";

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                collectProblems(pathStr, resultFuture);
            } catch (Exception e) {
                resultFuture.complete("Error getting problems: " + e.getMessage());
            }
        });

        return resultFuture.get(30, TimeUnit.SECONDS);
    }

    private void collectProblems(String pathStr, CompletableFuture<String> resultFuture) {
        // Phase 1 (inside read action): resolve files, partition open vs closed, collect highlights for open ones.
        ResolvedFiles resolved = com.intellij.openapi.application.ReadAction.compute(
            () -> resolveAndCollectOpen(pathStr));
        if (resolved.error != null) {
            resultFuture.complete(resolved.error);
            return;
        }

        // Phase 2 (outside read action): closed-file batch analysis. CodeSmellDetector
        // must NOT run inside a read action — it manages its own progress and threading.
        String basePath = project.getBasePath();
        for (VirtualFile vf : resolved.closedFiles) {
            String relPath = basePath != null ? relativize(basePath, vf.getPath()) : vf.getName();
            collectProblemsViaCodeSmellDetector(vf, relPath, resolved.problems);
        }

        if (resolved.problems.isEmpty()) {
            resultFuture.complete("No problems found"
                + (pathStr.isEmpty() ? " in open files" : " in " + pathStr) + ".");
        } else {
            resultFuture.complete(resolved.problems.size() + " problems:\n" + String.join("\n", resolved.problems));
        }
    }

    private @NotNull ResolvedFiles resolveAndCollectOpen(@NotNull String pathStr) {
        ResolvedFiles out = new ResolvedFiles();
        List<VirtualFile> filesToCheck = new ArrayList<>();
        if (!pathStr.isEmpty()) {
            VirtualFile vf = resolveVirtualFile(pathStr);
            if (vf == null) {
                out.error = ToolUtils.ERROR_FILE_NOT_FOUND + pathStr;
                return out;
            }
            filesToCheck.add(vf);
        } else {
            var fem = FileEditorManager.getInstance(project);
            filesToCheck.addAll(List.of(fem.getOpenFiles()));
        }

        String basePath = project.getBasePath();
        var fem = FileEditorManager.getInstance(project);
        for (VirtualFile vf : filesToCheck) {
            String relPath = basePath != null ? relativize(basePath, vf.getPath()) : vf.getName();
            int beforeSize = out.problems.size();
            collectOpenFileHighlights(vf, relPath, out.problems);
            // If the file is not open AND we found no cached highlights, defer to closed-file analysis.
            if (out.problems.size() == beforeSize && !fem.isFileOpen(vf)) {
                out.closedFiles.add(vf);
            }
        }
        return out;
    }

    private void collectOpenFileHighlights(VirtualFile vf, String relPath, List<String> problems) {
        PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
        if (psiFile == null) return;
        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (doc == null) return;

        List<com.intellij.codeInsight.daemon.impl.HighlightInfo> highlights = new ArrayList<>();
        com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx.processHighlights(
            doc, project,
            com.intellij.lang.annotation.HighlightSeverity.WARNING,
            0, doc.getTextLength(),
            highlights::add
        );
        for (var h : highlights) {
            if (h.getDescription() == null) continue;
            int line = doc.getLineNumber(h.getStartOffset()) + 1;
            String severity = h.getSeverity().getName();
            problems.add(String.format(FORMAT_LOCATION,
                relPath, line, severity, h.getDescription()));
        }
    }

    private static final class ResolvedFiles {
        final List<VirtualFile> closedFiles = new ArrayList<>();
        final List<String> problems = new ArrayList<>();
        String error;
    }

    /**
     * Fallback for files not open in any editor: run public batch code-smell analysis
     * via {@link com.intellij.openapi.vcs.CodeSmellDetector}. This catches errors and
     * warnings without requiring the daemon to highlight the file first.
     *
     * <p>Must NOT be called from inside a read action — the detector manages its own
     * threading and may dispatch to EDT internally.</p>
     *
     * <p>Note: weak warnings and intentions are only available when the file is open.</p>
     */
    private void collectProblemsViaCodeSmellDetector(VirtualFile vf,
                                                     String relPath, List<String> problems) {
        try {
            var detector = com.intellij.openapi.vcs.CodeSmellDetector.getInstance(project);
            var smells = detector.findCodeSmells(java.util.Collections.singletonList(vf));
            for (var s : smells) {
                String msg = s.getDescription();
                if (msg == null || msg.isBlank()) continue;
                int line = s.getStartLine() >= 0 ? s.getStartLine() + 1 : 1;
                String severity = s.getSeverity() != null ? s.getSeverity().getName() : "WARNING";
                problems.add(String.format(FORMAT_LOCATION, relPath, line, severity, msg));
            }
        } catch (com.intellij.openapi.progress.ProcessCanceledException pce) {
            throw pce;
        } catch (Exception e) {
            // Closed-file analysis is best-effort; surface the failure but don't blow up the whole call.
            problems.add(String.format(FORMAT_LOCATION, relPath, 1, "INFO",
                "Closed-file analysis unavailable: " + e.getMessage()));
        }
    }
}
