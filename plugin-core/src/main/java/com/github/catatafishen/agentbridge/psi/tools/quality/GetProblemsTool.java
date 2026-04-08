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
    public @NotNull String displayName() {
        return "Get Problems";
    }

    @Override
    public @NotNull String description() {
        return "Get cached editor problems (errors/warnings) for open files";
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
            {"path", TYPE_STRING, "Optional: file path to check. If omitted, checks all open files", ""}
        });
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String pathStr = args.has("path") ? args.get("path").getAsString() : "";

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                ApplicationManager.getApplication().runReadAction(() -> collectProblems(pathStr, resultFuture));
            } catch (Exception e) {
                resultFuture.complete("Error getting problems: " + e.getMessage());
            }
        });

        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    private void collectProblems(String pathStr, CompletableFuture<String> resultFuture) {
        List<VirtualFile> filesToCheck = new ArrayList<>();
        if (!pathStr.isEmpty()) {
            VirtualFile vf = resolveVirtualFile(pathStr);
            if (vf == null) {
                resultFuture.complete(ToolUtils.ERROR_FILE_NOT_FOUND + pathStr);
                return;
            }
            filesToCheck.add(vf);
        } else {
            var fem = FileEditorManager.getInstance(project);
            filesToCheck.addAll(List.of(fem.getOpenFiles()));
        }

        String basePath = project.getBasePath();
        List<String> problems = new ArrayList<>();
        for (VirtualFile vf : filesToCheck) {
            collectProblemsForFile(vf, basePath, problems);
        }

        if (problems.isEmpty()) {
            resultFuture.complete("No problems found"
                + (pathStr.isEmpty() ? " in open files" : " in " + pathStr)
                + ". Analysis is based on IntelliJ's inspections — file must be open in editor for highlights to be available.");
        } else {
            resultFuture.complete(problems.size() + " problems:\n" + String.join("\n", problems));
        }
    }

    private void collectProblemsForFile(VirtualFile vf, String basePath, List<String> problems) {
        PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
        if (psiFile == null) return;
        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (doc == null) return;

        String relPath = basePath != null ? relativize(basePath, vf.getPath()) : vf.getName();
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
}
