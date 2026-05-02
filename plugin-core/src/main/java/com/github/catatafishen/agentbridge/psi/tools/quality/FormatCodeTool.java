package com.github.catatafishen.agentbridge.psi.tools.quality;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.tools.file.FileTool;
import com.github.catatafishen.agentbridge.ui.renderers.SimpleStatusRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
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
        return "Manually format a file using IntelliJ's configured code style. "
            + "Supports partial formatting via start_line/end_line parameters. "
            + "Useful after edit_text match failures to normalize whitespace before retrying.";
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
            Param.required("path", TYPE_STRING, "Absolute or project-relative path to the file to format"),
            Param.optional("start_line", TYPE_INTEGER, "First line to format (1-based). If omitted, formats the entire file."),
            Param.optional("end_line", TYPE_INTEGER, "Last line to format (1-based, inclusive). If omitted, formats to end of file.")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String pathStr = args.get("path").getAsString();
        int startLine = args.has("start_line") ? args.get("start_line").getAsInt() : -1;
        int endLine = args.has("end_line") ? args.get("end_line").getAsInt() : -1;

        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        EdtUtil.invokeLater(() -> {
            try {
                FilePair pair = resolveFilePair(pathStr, resultFuture);
                if (pair == null) return;
                WriteCommandAction.runWriteCommandAction(project, "Reformat Code", null, () -> {
                    PsiDocumentManager.getInstance(project).commitAllDocuments();
                    if (startLine > 0) {
                        Document doc = PsiDocumentManager.getInstance(project).getDocument(pair.psiFile());
                        if (doc != null) {
                            int start = doc.getLineStartOffset(Math.max(0, startLine - 1));
                            int actualEnd = endLine > 0 ? endLine : doc.getLineCount();
                            int end = doc.getLineEndOffset(Math.min(doc.getLineCount() - 1, actualEnd - 1));
                            TextRange range = new TextRange(start, end);
                            new com.intellij.codeInsight.actions.ReformatCodeProcessor(project, pair.psiFile(), range, false).run();
                        } else {
                            new com.intellij.codeInsight.actions.ReformatCodeProcessor(pair.psiFile(), false).run();
                        }
                    } else {
                        new com.intellij.codeInsight.actions.ReformatCodeProcessor(pair.psiFile(), false).run();
                    }
                });
                String relPath = project.getBasePath() != null
                    ? relativize(project.getBasePath(), pair.vf().getPath()) : pathStr;
                String rangeInfo = startLine > 0
                    ? " (lines " + startLine + "-" + (endLine > 0 ? endLine : "end") + ")"
                    : "";
                resultFuture.complete("Code formatted: " + relPath + rangeInfo);
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
