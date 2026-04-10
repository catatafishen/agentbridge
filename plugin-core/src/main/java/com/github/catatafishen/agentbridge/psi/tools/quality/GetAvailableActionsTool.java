package com.github.catatafishen.agentbridge.psi.tools.quality;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.google.gson.JsonObject;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class GetAvailableActionsTool extends QualityTool {

    private static final Logger LOG = Logger.getInstance(GetAvailableActionsTool.class);
    private static final String PARAM_COLUMN = "column";
    private static final String PARAM_SYMBOL = "symbol";
    private static final String LINE_LABEL = " line ";

    public GetAvailableActionsTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "get_available_actions";
    }

    @Override
    public @NotNull String displayName() {
        return "Get Available Actions";
    }

    @Override
    public @NotNull String description() {
        return "Get quick-fix and intention action names available at a specific file and line. "
            + "Without 'column'/'symbol': returns highlight-based quick-fixes (errors/warnings with fixes). "
            + "With 'symbol' or 'column': also returns context-aware intention actions at that symbol position "
            + "(refactoring, conversions, etc. — the full Alt+Enter menu). "
            + "Use 'symbol' (preferred) to auto-detect the column from the symbol name. "
            + "Use apply_action to invoke one, or optimize_imports to fix all missing imports at once.";
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
            Param.required("file", TYPE_STRING, "Path to the file"),
            Param.required("line", TYPE_INTEGER, "Line number (1-based)"),
            Param.optional(PARAM_SYMBOL, TYPE_STRING, "Symbol name on the line (e.g. '_scrollRAF'). "
                + "Auto-detects the column and returns intention actions at that symbol. "
                + "Preferred over specifying 'column' manually."),
            Param.optional(PARAM_COLUMN, TYPE_INTEGER, "Column number (1-based, optional). "
                + "Use 'symbol' instead when possible. When provided, also returns "
                + "intention actions available at that exact symbol position (refactoring, conversions, etc.)")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        if (!args.has("file") || !args.has("line")) {
            return "Error: 'file' and 'line' parameters are required";
        }
        String pathStr = args.get("file").getAsString();
        int targetLine = args.get("line").getAsInt();
        String symbol = args.has(PARAM_SYMBOL) ? args.get(PARAM_SYMBOL).getAsString() : null;
        Integer targetCol = args.has(PARAM_COLUMN) ? args.get(PARAM_COLUMN).getAsInt() : null;

        // If symbol or column provided, collect intentions on EDT
        if (symbol != null || targetCol != null) {
            CompletableFuture<String> future = new CompletableFuture<>();
            EdtUtil.invokeLater(() -> {
                try {
                    future.complete(collectActionsWithIntentions(pathStr, targetLine, symbol, targetCol));
                } catch (Exception e) {
                    LOG.warn("Error collecting actions at " + pathStr + ":" + targetLine, e);
                    future.complete("Error: " + e.getMessage());
                }
            });
            return future.get(15, TimeUnit.SECONDS);
        }

        CompletableFuture<String> future = new CompletableFuture<>();
        ApplicationManager.getApplication().executeOnPooledThread(() ->
            ApplicationManager.getApplication().runReadAction(() -> {
                try {
                    future.complete(collectQuickFixesOnly(pathStr, targetLine));
                } catch (Exception e) {
                    LOG.warn("Error collecting quick-fixes at " + pathStr + ":" + targetLine, e);
                    future.complete("Error: " + e.getMessage());
                }
            })
        );
        return future.get(15, TimeUnit.SECONDS);
    }

    // ── Quick-fixes only (no symbol/column) ─────────────────────────

    private String collectQuickFixesOnly(String pathStr, int targetLine) {
        VirtualFile vf = resolveVirtualFile(pathStr);
        if (vf == null) return "Error: File not found: " + pathStr;

        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (doc == null) return "Error: Cannot get document for: " + pathStr;

        if (targetLine < 1 || targetLine > doc.getLineCount()) {
            return "Error: Line " + targetLine + " is out of bounds (file has " + doc.getLineCount() + " lines)";
        }

        List<HighlightInfo> lineHighlights = highlightsOnLine(doc, targetLine);
        List<String> entries = new ArrayList<>();
        Set<String> allFixNames = new LinkedHashSet<>();

        for (var h : lineHighlights) {
            String desc = h.getDescription();
            if (desc == null) continue;

            int actualLine = doc.getLineNumber(h.getStartOffset()) + 1;
            String severity = h.getSeverity().getName();
            List<String> fixes = collectQuickFixNames(h);

            StringBuilder entry = new StringBuilder(pathStr).append(':').append(actualLine)
                .append(" [").append(severity).append("] ").append(desc);
            for (String fix : fixes) {
                entry.append("\n    Fix: ").append(fix);
                allFixNames.add(fix);
            }
            entries.add(entry.toString());
        }

        if (entries.isEmpty()) {
            return "No highlights found at " + pathStr + LINE_LABEL + targetLine + ". "
                + "The daemon may not have analyzed this file yet - open it in the editor or call get_highlights first. "
                + "Tip: provide 'symbol' or 'column' to also query intention actions (refactoring, conversions, etc.).";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[QUICK FIX] at ").append(pathStr).append(LINE_LABEL).append(targetLine).append(":\n\n");
        sb.append(String.join("\n", entries));
        if (!allFixNames.isEmpty()) {
            sb.append("\n\nCopy the action name exactly as shown (after 'Fix:') and pass to apply_action.");
        }
        return sb.toString();
    }

    // ── Quick-fixes + intentions (with symbol or column) ───────────────────

    private String collectActionsWithIntentions(String pathStr, int targetLine,
                                                @Nullable String symbol, @Nullable Integer targetCol) {
        VirtualFile vf = resolveVirtualFile(pathStr);
        if (vf == null) return "Error: File not found: " + pathStr;

        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (doc == null) return "Error: Cannot get document for: " + pathStr;

        if (targetLine < 1 || targetLine > doc.getLineCount()) {
            return "Error: Line " + targetLine + " is out of bounds (file has " + doc.getLineCount() + " lines)";
        }

        Editor editor = getOrOpenEditor(vf);
        if (editor == null) return "Error: Could not open editor for " + pathStr;

        int col = resolveColumn(doc, targetLine, symbol, targetCol);
        editor.getCaretModel().moveToLogicalPosition(new LogicalPosition(targetLine - 1, col));

        PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
        if (psiFile == null) return "Error: Cannot parse file: " + pathStr;

        List<String> quickFixes = highlightsOnLine(doc, targetLine).stream()
            .flatMap(h -> collectQuickFixNames(h).stream())
            .distinct()
            .toList();

        List<String> intentions = collectIntentionNames(editor, psiFile);

        if (quickFixes.isEmpty() && intentions.isEmpty()) {
            return "No actions available at " + pathStr + LINE_LABEL + targetLine
                + (symbol != null ? " (symbol: '" + symbol + "')" : " col " + (col + 1)) + ".";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Actions at ").append(pathStr).append(LINE_LABEL).append(targetLine);
        if (symbol != null) {
            sb.append(" (symbol: '").append(symbol).append("', col ").append(col + 1).append(')');
        } else {
            sb.append(" col ").append(col + 1);
        }
        sb.append(":\n");
        sb.append("\nCopy the action name exactly as shown to pass to apply_action.\n");

        if (!quickFixes.isEmpty()) {
            sb.append("\n[QUICK FIX]\n");
            quickFixes.forEach(f -> sb.append("  ").append(f).append("\n"));
        }
        if (!intentions.isEmpty()) {
            sb.append("\n[INTENTION]\n");
            intentions.forEach(i -> sb.append("  ").append(i).append("\n"));
        }

        sb.append("\nUse apply_action(file, line, action_name) to invoke one.");
        return sb.toString();
    }

    /**
     * Resolves the 0-based caret column from a symbol name or an explicit column.
     * Falls back to column 0 if neither resolves successfully.
     */
    private static int resolveColumn(Document doc, int targetLine,
                                     @Nullable String symbol, @Nullable Integer targetCol) {
        if (symbol != null && !symbol.isBlank()) {
            int lineStart = doc.getLineStartOffset(targetLine - 1);
            int lineEnd = doc.getLineEndOffset(targetLine - 1);
            String lineText = doc.getText(new com.intellij.openapi.util.TextRange(lineStart, lineEnd));
            int idx = lineText.indexOf(symbol);
            if (idx >= 0) return idx;
        }
        return targetCol != null ? Math.max(0, targetCol - 1) : 0;
    }
}
