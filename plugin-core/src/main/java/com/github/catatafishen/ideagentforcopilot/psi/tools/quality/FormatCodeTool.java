package com.github.catatafishen.ideagentforcopilot.psi.tools.quality;

import com.github.catatafishen.ideagentforcopilot.psi.EdtUtil;
import com.github.catatafishen.ideagentforcopilot.psi.tools.file.FileTool;
import com.github.catatafishen.ideagentforcopilot.ui.renderers.SimpleStatusRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Formats a file using IntelliJ's configured code style.
 */
public final class FormatCodeTool extends QualityTool {

    public FormatCodeTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "format_code";
    }

    @Override
    public @NotNull String displayName() {
        return "Format Code";
    }

    @Override
    public @NotNull String description() {
        return "Manually format a file using IntelliJ's configured code style";
    }

    

    @Override
    public @NotNull String kind() {
        return "edit";
    }
@Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
            {"path", TYPE_STRING, "Absolute or project-relative path to the file to format"}
        }, "path");
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
                        new com.intellij.codeInsight.actions.ReformatCodeProcessor(pair.psiFile(), false).run();
                    }, "Reformat Code", null)
                );
                String relPath = project.getBasePath() != null
                    ? relativize(project.getBasePath(), pair.vf().getPath()) : pathStr;
                resultFuture.complete("Code formatted: " + relPath);
            } catch (Exception e) {
                resultFuture.complete("Error formatting code: " + e.getMessage());
            }
        });
        String result = resultFuture.get(30, TimeUnit.SECONDS);
        if (result.startsWith("Code formatted")) {
            FileTool.followFileIfEnabled(project, pathStr, 1, 1,
                FileTool.HIGHLIGHT_EDIT, FileTool.agentLabel(project) + " formatted");
        }
        return result;
    }

    @Override
    public @NotNull Object resultRenderer() {
        return SimpleStatusRenderer.INSTANCE;
    }
}
