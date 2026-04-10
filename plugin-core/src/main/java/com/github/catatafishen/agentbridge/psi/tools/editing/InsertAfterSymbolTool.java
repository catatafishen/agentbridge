package com.github.catatafishen.agentbridge.psi.tools.editing;

import com.github.catatafishen.agentbridge.psi.CodeChangeTracker;
import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.FileAccessTracker;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.psi.tools.file.FileTool;
import com.github.catatafishen.agentbridge.ui.renderers.ReplaceSymbolRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Inserts content after a symbol definition.
 * Auto-formats and optimizes imports immediately on every call.
 */
public final class InsertAfterSymbolTool extends EditingTool {

    private static final String PARAM_CONTENT = "content";

    public InsertAfterSymbolTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "insert_after_symbol";
    }

    @Override
    public @NotNull String displayName() {
        return "Insert After Symbol";
    }

    @Override
    public @NotNull String description() {
        return "Insert content after a symbol definition. PSI-aware — finds symbols by name, no line numbers needed. " +
            "Auto-formats and optimizes imports immediately. Use for adding new methods after an existing one.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Insert after {symbol} in {file}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required("file", TYPE_STRING, "Absolute or project-relative path to the file containing the symbol"),
            Param.required("symbol", TYPE_STRING, "Name of the symbol to insert after"),
            Param.required(PARAM_CONTENT, TYPE_STRING, "The content to insert after the symbol"),
            Param.optional("line", TYPE_INTEGER, "Optional: line number hint to disambiguate if multiple symbols share the same name")
        );
    }

    @Override
    public @NotNull Object resultRenderer() {
        return ReplaceSymbolRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String error = validateArgs(args, PARAM_CONTENT);
        if (error != null) return error;

        String pathStr = args.get(PARAM_FILE).getAsString();
        String symbolName = args.get(PARAM_SYMBOL).getAsString();
        String content = args.get(PARAM_CONTENT).getAsString();
        Integer lineHint = args.has(PARAM_LINE) ? args.get(PARAM_LINE).getAsInt() : null;

        CompletableFuture<String> result = new CompletableFuture<>();
        int[] endLine = new int[1];

        EdtUtil.invokeLater(() -> performInsertAfter(pathStr, symbolName, content, lineHint, endLine, result));

        String resultStr = result.get(15, TimeUnit.SECONDS);
        if (!resultStr.startsWith(ToolUtils.ERROR_PREFIX) && !resultStr.startsWith(SYMBOL_PREFIX)) {
            int insertedLines = (int) content.chars().filter(c -> c == '\n').count() + 1;
            CodeChangeTracker.recordChange(insertedLines, 0);
            int insertStart = endLine[0] + 1;
            FileTool.followFileIfEnabled(project, pathStr, insertStart, insertStart + insertedLines - 1,
                FileTool.HIGHLIGHT_EDIT, "inserting after " + symbolName);
            FileAccessTracker.recordWrite(project, pathStr);
        }
        return resultStr;
    }

    private void performInsertAfter(String pathStr, String symbolName, String content,
                                    Integer lineHint, int[] endLine, CompletableFuture<String> result) {
        try {
            SymbolLocation loc = resolveSymbol(pathStr, symbolName, lineHint);
            if (loc == null) {
                result.complete(symbolNotFoundMessage(pathStr, symbolName, lineHint));
                return;
            }
            endLine[0] = loc.endLine();

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

            String normalized = content.replace("\r\n", "\n").replace("\r", "\n");
            if (!normalized.endsWith("\n")) {
                normalized += "\n";
            }
            int offset = doc.getLineEndOffset(loc.endLine() - 1);
            if (offset < doc.getTextLength() && doc.getCharsSequence().charAt(offset) == '\n') {
                offset++;
            }
            final String fContent = normalized;
            final int fOffset = offset;

            WriteAction.run(() ->
                CommandProcessor.getInstance().executeCommand(
                    project, () -> doc.insertString(fOffset, fContent),
                    "Insert After Symbol", null)
            );

            PsiDocumentManager.getInstance(project).commitDocument(doc);
            formatInline(vf);
            FileDocumentManager.getInstance().saveDocument(doc);

            int newLineCount = (int) fContent.chars().filter(c -> c == '\n').count();
            result.complete("Inserted " + newLineCount + " lines after line " + loc.endLine() + " in " + pathStr
                + FORMATTED_SUFFIX);
        } catch (Exception e) {
            result.complete(ToolUtils.ERROR_PREFIX + e.getMessage());
        }
    }
}
