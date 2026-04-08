package com.github.catatafishen.agentbridge.psi.tools.quality;

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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Fast compilation error check using cached daemon results.
 * Much faster than build_project since it uses cached daemon analysis results.
 */
public final class GetCompilationErrorsTool extends QualityTool {

    private static final Logger LOG = Logger.getInstance(GetCompilationErrorsTool.class);

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
        return schema(new Object[][]{
            {"path", TYPE_STRING, "Optional: specific file to check. If omitted, checks all open source files", ""}
        });
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
        ApplicationManager.getApplication().runReadAction(() -> {
            ProjectFileIndex fileIndex = ProjectRootManager.getInstance(project).getFileIndex();
            Collection<VirtualFile> files = collectFilesForHighlightAnalysis(pathStr, false, fileIndex, resultFuture);
            if (resultFuture.isDone()) return;

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
