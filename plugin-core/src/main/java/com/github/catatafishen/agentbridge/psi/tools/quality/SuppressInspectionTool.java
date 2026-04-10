package com.github.catatafishen.agentbridge.psi.tools.quality;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.psi.tools.file.FileTool;
import com.github.catatafishen.agentbridge.ui.renderers.SimpleStatusRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Inserts a suppress annotation or comment for a specific inspection at a given line.
 */
public final class SuppressInspectionTool extends QualityTool {

    private static final Logger LOG = Logger.getInstance(SuppressInspectionTool.class);
    private static final String LABEL_SUPPRESS_INSPECTION = "Suppress Inspection";

    public SuppressInspectionTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "suppress_inspection";
    }

    @Override
    public @NotNull String displayName() {
        return LABEL_SUPPRESS_INSPECTION;
    }

    @Override
    public @NotNull String description() {
        return "Insert a suppress annotation or comment for a specific inspection at a given line";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required("path", TYPE_STRING, "Path to the file containing the code to suppress"),
            Param.required("line", TYPE_INTEGER, "Line number where the inspection finding is located"),
            Param.required(PARAM_INSPECTION_ID, TYPE_STRING, "The inspection ID to suppress (e.g., 'SpellCheckingInspection')")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String pathStr = args.get("path").getAsString();
        int line = args.get("line").getAsInt();
        String inspectionId = args.get(PARAM_INSPECTION_ID).getAsString().trim();

        if (inspectionId.isEmpty()) {
            return ToolUtils.ERROR_PREFIX + PARAM_INSPECTION_ID + " cannot be empty";
        }

        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        EdtUtil.invokeLater(() -> {
            try {
                processSuppressInspection(pathStr, line, inspectionId, resultFuture);
            } catch (Exception e) {
                LOG.error("Error suppressing inspection", e);
                resultFuture.complete("Error suppressing inspection: " + e.getMessage());
            }
        });
        String result = resultFuture.get(10, TimeUnit.SECONDS);
        if (result.startsWith("Added") || result.startsWith("Suppressed")) {
            FileTool.followFileIfEnabled(project, pathStr, line, line,
                FileTool.HIGHLIGHT_EDIT, FileTool.agentLabel(project) + " suppressed");
        }
        return result;
    }

    @Override
    public @NotNull Object resultRenderer() {
        return SimpleStatusRenderer.INSTANCE;
    }

    // ── Private helpers ──────────────────────────────────────

    private void processSuppressInspection(String pathStr, int line, String inspectionId,
                                           CompletableFuture<String> resultFuture) {
        var vf = resolveVirtualFile(pathStr);
        if (vf == null) {
            resultFuture.complete("Error: file not found: " + pathStr);
            return;
        }

        var psiFile = PsiManager.getInstance(project).findFile(vf);
        if (psiFile == null) {
            resultFuture.complete("Error: could not parse file: " + pathStr);
            return;
        }

        var document = PsiDocumentManager.getInstance(project).getDocument(psiFile);
        if (document == null) {
            resultFuture.complete("Error: could not get document for: " + pathStr);
            return;
        }

        int zeroLine = line - 1;
        if (zeroLine < 0 || zeroLine >= document.getLineCount()) {
            resultFuture.complete("Error: line " + line + " is out of range (file has " +
                document.getLineCount() + FORMAT_LINES_SUFFIX);
            return;
        }

        int offset = document.getLineStartOffset(zeroLine);
        var element = psiFile.findElementAt(offset);
        if (element == null) {
            resultFuture.complete("Error: no code element found at line " + line);
            return;
        }

        String fileName = vf.getName();

        if (fileName.endsWith(ToolUtils.JAVA_EXTENSION)) {
            try {
                resultFuture.complete(com.github.catatafishen.agentbridge.psi.java.CodeQualityJavaSupport.suppress(project, element, inspectionId, document));
            } catch (NoClassDefFoundError e) {
                resultFuture.complete(suppressWithComment(element, inspectionId, document));
            }
        } else if (fileName.endsWith(".kt") || fileName.endsWith(".kts")) {
            resultFuture.complete(suppressKotlin(element, inspectionId, document));
        } else {
            resultFuture.complete(suppressWithComment(element, inspectionId, document));
        }
    }

    private String suppressKotlin(PsiElement target, String inspectionId, Document document) {
        LineInfo info = getLineInfo(target, document);

        if (info.targetLine() > 0) {
            int prevStart = document.getLineStartOffset(info.targetLine() - 1);
            int prevEnd = document.getLineEndOffset(info.targetLine() - 1);
            String prevLine = document.getText(new TextRange(prevStart, prevEnd)).trim();
            if (prevLine.startsWith("@Suppress(") && prevLine.contains(inspectionId)) {
                return "Inspection '" + inspectionId + "' is already suppressed at this location";
            }
        }

        String annotation = info.indent() + "@Suppress(\"" + inspectionId + "\")\n";
        WriteAction.run(() ->
            CommandProcessor.getInstance().executeCommand(project, () -> {
                document.insertString(info.lineStart(), annotation);
                PsiDocumentManager.getInstance(project).commitDocument(document);
            }, LABEL_SUPPRESS_INSPECTION, null)
        );

        return "Added @Suppress(\"" + inspectionId + "\") at line " + (info.targetLine() + 1);
    }

    private String suppressWithComment(PsiElement target, String inspectionId, Document document) {
        LineInfo info = getLineInfo(target, document);
        String comment = info.indent() + "//noinspection " + inspectionId + "\n";
        WriteAction.run(() ->
            CommandProcessor.getInstance().executeCommand(project, () -> {
                document.insertString(info.lineStart(), comment);
                PsiDocumentManager.getInstance(project).commitDocument(document);
            }, LABEL_SUPPRESS_INSPECTION, null)
        );
        return "Added //noinspection " + inspectionId + " comment at line " + (info.targetLine() + 1);
    }

    private record LineInfo(int targetLine, int lineStart, String indent) {
    }

    private LineInfo getLineInfo(PsiElement target, Document document) {
        int targetOffset = target.getTextRange().getStartOffset();
        int targetLine = document.getLineNumber(targetOffset);
        int lineStart = document.getLineStartOffset(targetLine);
        String lineText = document.getText(new TextRange(lineStart, document.getLineEndOffset(targetLine)));
        StringBuilder indent = new StringBuilder();
        for (char c : lineText.toCharArray()) {
            if (c == ' ' || c == '\t') indent.append(c);
            else break;
        }
        return new LineInfo(targetLine, lineStart, indent.toString());
    }
}
