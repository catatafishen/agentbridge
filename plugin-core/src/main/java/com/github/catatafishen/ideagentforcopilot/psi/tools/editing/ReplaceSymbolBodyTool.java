package com.github.catatafishen.ideagentforcopilot.psi.tools.editing;

import com.github.catatafishen.ideagentforcopilot.psi.EdtUtil;
import com.github.catatafishen.ideagentforcopilot.psi.FileAccessTracker;
import com.github.catatafishen.ideagentforcopilot.psi.ToolUtils;
import com.github.catatafishen.ideagentforcopilot.psi.tools.file.FileTool;
import com.github.catatafishen.ideagentforcopilot.ui.renderers.ReplaceSymbolRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Replaces the entire definition of a symbol (method, class, field) by name.
 * Auto-formats and optimizes imports immediately on every call.
 */
public final class ReplaceSymbolBodyTool extends EditingTool {

    private static final String PARAM_NEW_BODY = "new_body";

    public ReplaceSymbolBodyTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "replace_symbol_body";
    }

    @Override
    public @NotNull String displayName() {
        return "Replace Symbol Body";
    }

    @Override
    public @NotNull String description() {
        return "Replace the entire definition of a symbol (method, class, field) by name -- no line numbers needed. "
            + "Auto-formats and optimizes imports immediately on every call";
    }

    

    @Override
    public @NotNull String kind() {
        return "edit";
    }
@Override
    public @NotNull String permissionTemplate() {
        return "Replace {symbol} in {file}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
            {"file", TYPE_STRING, "Absolute or project-relative path to the file containing the symbol"},
            {"symbol", TYPE_STRING, "Name of the symbol to replace (method, class, function, or field)"},
            {PARAM_NEW_BODY, TYPE_STRING, "The complete new definition to replace the symbol with"},
            {"line", TYPE_INTEGER, "Optional: line number hint to disambiguate if multiple symbols share the same name"}
        }, "file", "symbol", PARAM_NEW_BODY);
    }

    @Override
    public @NotNull Object resultRenderer() {
        return ReplaceSymbolRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String error = validateArgs(args, PARAM_NEW_BODY);
        if (error != null) return error;

        String pathStr = args.get(PARAM_FILE).getAsString();
        String symbolName = args.get(PARAM_SYMBOL).getAsString();
        String newBody = args.get(PARAM_NEW_BODY).getAsString();
        Integer lineHint = args.has(PARAM_LINE) ? args.get(PARAM_LINE).getAsInt() : null;

        CompletableFuture<String> result = new CompletableFuture<>();
        int[] lineRange = new int[2];
        String[] symbolType = new String[1];

        EdtUtil.invokeLater(() -> {
            try {
                SymbolLocation loc = resolveSymbol(pathStr, symbolName, lineHint);
                if (loc == null) {
                    result.complete(symbolNotFoundMessage(pathStr, symbolName, lineHint));
                    return;
                }
                lineRange[0] = loc.startLine();
                lineRange[1] = loc.endLine();
                symbolType[0] = loc.type();

                VirtualFile vf = resolveVirtualFile(pathStr);
                if (vf == null) {
                    result.complete(ToolUtils.ERROR_FILE_NOT_FOUND + pathStr);
                    return;
                }
                Document doc = FileDocumentManager.getInstance().getDocument(vf);
                if (doc == null) {
                    result.complete(ERROR_CANNOT_OPEN_DOC + pathStr);
                    return;
                }

                int startOffset = doc.getLineStartOffset(loc.startLine() - 1);
                int endOffset = calculateEndOffset(doc, loc);
                String normalized = prepareNormalizedBody(newBody);

                final int fStart = startOffset;
                final int fEnd = endOffset;
                final String fNew = normalized;

                WriteAction.run(() ->
                    CommandProcessor.getInstance().executeCommand(
                        project, () -> doc.replaceString(fStart, fEnd, fNew),
                        "Replace Symbol Body", null)
                );

                PsiDocumentManager.getInstance(project).commitDocument(doc);
                formatInline(vf);
                FileDocumentManager.getInstance().saveDocument(doc);

                int replacedLines = loc.endLine() - loc.startLine() + 1;
                int newLineCount = (int) fNew.chars().filter(c -> c == '\n').count();
                result.complete("Replaced lines " + loc.startLine() + "-" + loc.endLine()
                    + " (" + replacedLines + " lines) with " + newLineCount + " lines in " + pathStr
                    + FORMATTED_SUFFIX);
            } catch (Exception e) {
                result.complete(ToolUtils.ERROR_PREFIX + e.getMessage());
            }
        });

        String resultStr = result.get(15, TimeUnit.SECONDS);
        if (!resultStr.startsWith(ToolUtils.ERROR_PREFIX) && !resultStr.startsWith(SYMBOL_PREFIX)) {
            int newLineCount = (int) newBody.chars().filter(c -> c == '\n').count() + 1;
            FileTool.followFileIfEnabled(project, pathStr, lineRange[0], lineRange[0] + newLineCount - 1,
                FileTool.HIGHLIGHT_EDIT, "replacing " + symbolType[0] + " " + symbolName);
            FileAccessTracker.recordWrite(project, pathStr);
        }
        return resultStr;
    }

    private static int calculateEndOffset(Document doc, SymbolLocation loc) {
        int endOffset = doc.getLineEndOffset(loc.endLine() - 1);
        if (endOffset < doc.getTextLength() && doc.getText().charAt(endOffset) == '\n') {
            endOffset++;
        }
        return endOffset;
    }

    private static String prepareNormalizedBody(String body) {
        String normalized = body.replace("\r\n", "\n").replace("\r", "\n");
        if (!normalized.isEmpty() && !normalized.endsWith("\n")) {
            normalized += "\n";
        }
        return normalized;
    }
}
