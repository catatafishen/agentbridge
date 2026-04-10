package com.github.catatafishen.agentbridge.psi.tools.quality;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.ui.renderers.SimpleStatusRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Manually removes unused imports and organizes them according to code style.
 */
public final class OptimizeImportsTool extends QualityTool {

    public OptimizeImportsTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "optimize_imports";
    }

    @Override
    public @NotNull String displayName() {
        return "Optimize Imports";
    }

    @Override
    public @NotNull String description() {
        return "Manually remove unused imports and organize them according to code style";
    }

    @Override
    public boolean isIdempotent() {
        return true;
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required("path", TYPE_STRING, "Absolute or project-relative path to the file to optimize imports")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String pathStr = args.get("path").getAsString();
        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        EdtUtil.invokeLater(() -> {
            try {
                FilePair pair = resolveFilePair(pathStr, resultFuture);
                if (pair == null) return;
                WriteAction.run(() ->
                    CommandProcessor.getInstance().executeCommand(project, () -> {
                        PsiDocumentManager.getInstance(project).commitAllDocuments();
                        new com.intellij.codeInsight.actions.OptimizeImportsProcessor(project, pair.psiFile()).run();
                    }, "Optimize Imports", null)
                );
                String relPath = project.getBasePath() != null
                    ? relativize(project.getBasePath(), pair.vf().getPath()) : pathStr;
                resultFuture.complete("Imports optimized: " + relPath);
            } catch (Exception e) {
                resultFuture.complete("Error optimizing imports: " + e.getMessage());
            }
        });
        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    @Override
    public @NotNull Object resultRenderer() {
        return SimpleStatusRenderer.INSTANCE;
    }
}
