package com.github.catatafishen.agentbridge.psi.tools.quality;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.settings.DiagnosticFilterSettings;
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
import java.util.Map;
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
            + "The file does not need to be open: for a closed file the quick-fixes are computed on demand, "
            + "without opening an editor (intentions still need an editor, which is opened only briefly). "
            + "Fixes that the IDE computes lazily, such as 'Import class' for an unresolved symbol, are only "
            + "available for a file open in an editor; for a closed file the answer says so instead of listing none. "
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
            Param.required("path", TYPE_STRING, "Path to the file"),
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
        if (readPathParam(args) == null || !args.has("line")) {
            return "Error: 'path' and 'line' parameters are required";
        }
        String pathStr = readPathParam(args);
        int targetLine = args.get("line").getAsInt();
        String symbol = args.has(PARAM_SYMBOL) ? args.get(PARAM_SYMBOL).getAsString() : null;
        Integer targetCol = args.has(PARAM_COLUMN) ? args.get(PARAM_COLUMN).getAsInt() : null;

        // If symbol or column provided, collect intentions on EDT
        if (symbol != null || targetCol != null) {
            // The editor daemon only analyzes open files; analyze a closed one here, on the calling thread, so its
            // quick-fixes are listed too. The intentions below still need an editor and run on the EDT.
            Map<Document, List<HighlightInfo>> analyzed =
                analyzeClosedFileForEditing(pathStr, "Collecting quick-fixes");
            CompletableFuture<String> future = new CompletableFuture<>();
            EdtUtil.invokeLater(() -> {
                try {
                    future.complete(collectActionsWithIntentions(pathStr, targetLine, symbol, targetCol, analyzed));
                } catch (Exception e) {
                    LOG.warn("Error collecting actions at " + pathStr + ":" + targetLine, e);
                    future.complete("Error: " + e.getMessage());
                }
            });
            return future.get(15, TimeUnit.SECONDS);
        }

        CompletableFuture<String> future = new CompletableFuture<>();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                // Analysis must run outside the read action below: the runner manages its own read actions.
                // No editor is needed on this path, so the file is never opened.
                VirtualFile vf = resolveVirtualFileWithFallback(pathStr);
                Map<Document, List<HighlightInfo>> analyzed =
                    vf == null ? null : analyzeIfNotOpen(vf, "Collecting quick-fixes");
                ApplicationManager.getApplication().runReadAction(() -> {
                    future.complete(collectQuickFixesOnly(pathStr, targetLine, analyzed));
                });
            } catch (Exception e) {
                LOG.warn("Error collecting quick-fixes at " + pathStr + ":" + targetLine, e);
                future.complete("Error: " + e.getMessage());
            }
        });
        return future.get(15, TimeUnit.SECONDS);
    }

    // ── Quick-fixes only (no symbol/column) ─────────────────────────

    private String collectQuickFixesOnly(String pathStr, int targetLine,
                                         @Nullable Map<Document, List<HighlightInfo>> analyzed) {
        VirtualFile vf = resolveVirtualFileWithFallback(pathStr);
        if (vf == null) return "Error: File not found: " + pathStr;

        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (doc == null) return "Error: Cannot get document for: " + pathStr;

        if (targetLine < 1 || targetLine > doc.getLineCount()) {
            return "Error: Line " + targetLine + " is out of bounds (file has " + doc.getLineCount() + " lines)";
        }

        List<HighlightInfo> lineHighlights = highlightsOnLine(doc, targetLine, analyzed);
        DiagnosticFilterSettings filter = DiagnosticFilterSettings.getInstance(project);
        List<String> entries = new ArrayList<>();
        Set<String> allFixNames = new LinkedHashSet<>();

        for (var h : lineHighlights) {
            String desc = h.getDescription();
            if (desc == null || !filter.shouldInclude(h)) continue;

            int actualLine = doc.getLineNumber(h.getStartOffset()) + 1;
            String severity = h.getSeverity().getName();
            // For a file analyzed on demand an empty list can mean "not computed", which must not look like "none".
            List<String> fixes = analyzed == null ? collectQuickFixNames(h) : collectQuickFixNamesOrPending(h);

            StringBuilder entry = new StringBuilder(pathStr).append(':').append(actualLine)
                .append(" [").append(severity).append("] ").append(desc);
            for (String fix : fixes) {
                entry.append("\n    Fix: ").append(fix);
                if (!FIXES_NOT_COMPUTED.equals(fix)) allFixNames.add(fix);
            }
            entries.add(entry.toString());
        }

        if (entries.isEmpty()) {
            return noHighlightsMessage(pathStr, targetLine, analyzed != null);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[QUICK FIX] at ").append(pathStr).append(LINE_LABEL).append(targetLine).append(":\n\n");
        sb.append(String.join("\n", entries));
        if (!allFixNames.isEmpty()) {
            sb.append("\n\nCopy the action name exactly as shown (after 'Fix:') and pass to apply_action.");
        }
        String why = pendingLazyFixesExplanation(doc, targetLine, analyzed);
        if (why != null) sb.append("\n\nNote: ").append(why);
        return sb.toString();
    }

    /**
     * "No highlights" answer. For a file analyzed on demand the analysis did run, so the old advice to wait for the
     * daemon would be wrong.
     */
    private static String noHighlightsMessage(String pathStr, int targetLine, boolean analyzedOnDemand) {
        String where = "at " + pathStr + LINE_LABEL + targetLine;
        String tip = "Tip: provide 'symbol' or 'column' to also query intention actions (refactoring, conversions, etc.).";
        if (analyzedOnDemand) {
            return "No problems with quick-fixes found " + where + " (the file was analyzed on demand). " + tip;
        }
        return "No highlights found " + where + ". "
            + "The daemon may not have analyzed this file yet - open it in the editor or call get_highlights first. "
            + tip;
    }

    // ── Quick-fixes + intentions (with symbol or column) ───────────────────

    private String collectActionsWithIntentions(String pathStr, int targetLine,
                                                @Nullable String symbol, @Nullable Integer targetCol,
                                                @Nullable Map<Document, List<HighlightInfo>> analyzed) {
        VirtualFile vf = resolveVirtualFileWithFallback(pathStr);
        if (vf == null) return "Error: File not found: " + pathStr;

        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (doc == null) return "Error: Cannot get document for: " + pathStr;

        if (targetLine < 1 || targetLine > doc.getLineCount()) {
            return "Error: Line " + targetLine + " is out of bounds (file has " + doc.getLineCount() + " lines)";
        }

        String accessError = checkEditorAccess(vf);
        if (accessError != null) return accessError;

        ToolEditor toolEditor = openEditorForTool(vf);
        if (toolEditor == null) return "Error: Could not open editor for " + pathStr;
        try {
            Editor editor = toolEditor.editor();
            int col = resolveColumn(doc, targetLine, symbol, targetCol);
            editor.getCaretModel().moveToLogicalPosition(new LogicalPosition(targetLine - 1, col));

            PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
            if (psiFile == null) return "Error: Cannot parse file: " + pathStr;

            DiagnosticFilterSettings filter = DiagnosticFilterSettings.getInstance(project);
            List<HighlightInfo> lineHighlights = highlightsOnLine(doc, targetLine, analyzed);
            List<String> quickFixes = lineHighlights.stream()
                .filter(filter::shouldInclude)
                .flatMap(h -> collectQuickFixNames(h).stream())
                .distinct()
                .toList();

            List<String> intentions = collectIntentionNames(editor, psiFile);
            String pendingFixes = pendingLazyFixesExplanation(doc, targetLine, analyzed);

            if (quickFixes.isEmpty() && intentions.isEmpty()) {
                // Diagnose why nothing was found to help with non-Java IDEs (e.g. CLion C++).
                int totalRegistered = com.intellij.codeInsight.intention.IntentionManager.getInstance()
                    .getAvailableIntentions().size();
                int highlightCount = lineHighlights.size();
                boolean hasSymbol = symbol != null && !symbol.isBlank();
                return "No actions available at " + pathStr + LINE_LABEL + targetLine
                    + (hasSymbol ? " (symbol: '" + symbol + "')" : " col " + (col + 1)) + "."
                    + " Checked " + totalRegistered + " registered intentions and "
                    + highlightCount + " daemon highlight(s) on this line."
                    + (highlightCount == 0 && analyzed == null
                    ? " The daemon may not have analyzed this file yet — open it in the editor first, wait a moment, then retry."
                    : "")
                    + (pendingFixes == null ? "" : " Note: " + pendingFixes);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Actions at ").append(pathStr).append(LINE_LABEL).append(targetLine);
            boolean hasSymbol = symbol != null && !symbol.isBlank();
            if (hasSymbol) {
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
            if (pendingFixes != null) sb.append("\n\nNote: ").append(pendingFixes);
            return sb.toString();
        } finally {
            releaseToolEditor(toolEditor);
        }
    }

}
