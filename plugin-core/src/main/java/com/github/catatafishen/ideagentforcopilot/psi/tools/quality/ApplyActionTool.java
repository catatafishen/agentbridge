package com.github.catatafishen.ideagentforcopilot.psi.tools.quality;

import com.github.catatafishen.ideagentforcopilot.psi.EdtUtil;
import com.github.catatafishen.ideagentforcopilot.psi.PlatformApiCompat;
import com.github.catatafishen.ideagentforcopilot.psi.ToolUtils;
import com.github.catatafishen.ideagentforcopilot.psi.tools.file.FileTool;
import com.github.catatafishen.ideagentforcopilot.ui.renderers.GitDiffRenderer;
import com.google.gson.JsonObject;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class ApplyActionTool extends QualityTool {

    private static final Logger LOG = Logger.getInstance(ApplyActionTool.class);
    private static final String PARAM_COLUMN = "column";
    private static final String PARAM_SYMBOL = "symbol";
    private static final String PARAM_ACTION_NAME = "action_name";
    private static final String PARAM_OPTION = "option";
    private static final String PARAM_DRY_RUN = "dry_run";
    private static final String LINE_LABEL = " line ";
    private static final String ACTION_PREFIX = "Action '";

    public ApplyActionTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "apply_action";
    }

    @Override
    public @NotNull String displayName() {
        return "Apply Action";
    }

    @Override
    public @NotNull String description() {
        return "Invoke a named IDE quick-fix or intention action at a specific file and line. "
            + "Action names come from get_highlights or get_available_actions output. "
            + "Use 'symbol' (preferred) or 'column' to position the caret at the correct symbol. "
            + "Use 'option' to select a radio-button or checkbox in a dialog the action may show "
            + "(get options first with get_action_options). "
            + "Use 'dry_run: true' to preview changes as a diff without applying them. "
            + "Tip: use optimize_imports to fix all missing imports at once.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
            {"file", TYPE_STRING, "Path to the file"},
            {"line", TYPE_INTEGER, "Line number (1-based)"},
            {PARAM_ACTION_NAME, TYPE_STRING, "Exact action name from get_highlights / get_available_actions output"},
            {PARAM_SYMBOL, TYPE_STRING, "Symbol name on the line (e.g. '_scrollRAF'). "
                + "Auto-detects the column — preferred over specifying 'column' manually."},
            {PARAM_COLUMN, TYPE_INTEGER, "Column number (1-based, optional). Use 'symbol' instead when possible."},
            {PARAM_OPTION, TYPE_STRING, "Option to select in a dialog the action may show "
                + "(radio button or checkbox text, from get_action_options output)."},
            {PARAM_DRY_RUN, TYPE_BOOLEAN, "If true, shows a diff of what the action would change "
                + "without actually applying it. The change is applied then immediately undone."}
        }, "file", "line", PARAM_ACTION_NAME);
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        if (!args.has("file") || !args.has("line") || !args.has(PARAM_ACTION_NAME)) {
            return "Error: 'file', 'line', and 'action_name' parameters are required";
        }
        String pathStr = args.get("file").getAsString();
        int targetLine = args.get("line").getAsInt();
        String actionName = args.get(PARAM_ACTION_NAME).getAsString();
        String symbol = args.has(PARAM_SYMBOL) ? args.get(PARAM_SYMBOL).getAsString() : null;
        Integer targetCol = args.has(PARAM_COLUMN) ? args.get(PARAM_COLUMN).getAsInt() : null;
        String option = args.has(PARAM_OPTION) ? args.get(PARAM_OPTION).getAsString() : null;
        boolean dryRun = args.has(PARAM_DRY_RUN) && args.get(PARAM_DRY_RUN).getAsBoolean();

        CompletableFuture<String> future = new CompletableFuture<>();
        EdtUtil.invokeLater(() -> {
            try {
                future.complete(invokeAction(pathStr, targetLine, actionName, symbol, targetCol, option, dryRun));
            } catch (AssertionError e) {
                // Some actions (e.g. SafeDeleteFix) trigger interactive dialogs via assertions
                // that fail headlessly. Catch AssertionError separately for a clear message.
                LOG.warn(ACTION_PREFIX + actionName + "' requires interactive UI at " + pathStr + ":" + targetLine, e);
                future.complete("Error: " + ACTION_PREFIX + actionName + "' requires an interactive dialog "
                    + "and cannot be applied non-interactively. "
                    + "Try get_action_options to see what dialog options it shows, "
                    + "or perform this action manually in the IDE.");
            } catch (Exception e) {
                LOG.warn("Error invoking action '" + actionName + "' at " + pathStr + ":" + targetLine, e);
                future.complete(ToolUtils.ERROR_PREFIX + e.getMessage());
            }
        });

        String result = future.get(30, TimeUnit.SECONDS);
        if (result == null) return "Error: action invocation returned no result";
        if (!dryRun && !result.startsWith("Error") && !result.startsWith("No ")
            && !result.startsWith(ACTION_PREFIX) && !result.startsWith("Preview")) {
            FileTool.followFileIfEnabled(project, pathStr, targetLine, targetLine,
                FileTool.HIGHLIGHT_EDIT, FileTool.agentLabel(project) + " applied action");
        }
        return result;
    }

    @Override
    public @NotNull Object resultRenderer() {
        return GitDiffRenderer.INSTANCE;
    }

    // ── Private helpers ──────────────────────────────────────

    private String invokeAction(String pathStr, int targetLine, String actionName,
                                @Nullable String symbol, @Nullable Integer targetCol,
                                @Nullable String option, boolean dryRun) {
        VirtualFile vf = resolveVirtualFile(pathStr);
        if (vf == null) return ToolUtils.ERROR_PREFIX + ToolUtils.ERROR_FILE_NOT_FOUND + pathStr;

        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (doc == null) return "Error: Cannot get document for: " + pathStr;

        if (targetLine < 1 || targetLine > doc.getLineCount()) {
            return "Error: Line " + targetLine + " is out of bounds (file has " + doc.getLineCount() + FORMAT_LINES_SUFFIX;
        }

        PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
        if (psiFile == null) return ToolUtils.ERROR_PREFIX + ToolUtils.ERROR_CANNOT_PARSE + pathStr;

        Editor editor = getOrOpenEditor(vf);
        if (editor == null) {
            return "Error: Could not open editor for " + pathStr + ". Ensure the file is open in the IDE.";
        }

        int caretCol = resolveColumn(doc, targetLine, symbol, targetCol);
        editor.getCaretModel().moveToLogicalPosition(new LogicalPosition(targetLine - 1, caretCol));

        IntentionAction action = findActionToApply(doc, targetLine, actionName, editor, psiFile);
        if (action == null) {
            List<String> available = collectAvailableActionNames(doc, targetLine, editor, psiFile);
            String hint = available.isEmpty() ? "none" : String.join(", ", available);
            return ACTION_PREFIX + actionName + "' not found at " + pathStr + LINE_LABEL + targetLine
                + ". Available: [" + hint + "]";
        }

        if (!action.isAvailable(project, editor, psiFile)) {
            return ACTION_PREFIX + actionName + "' is not currently applicable at " + pathStr
                + LINE_LABEL + targetLine + ".";
        }

        String before = doc.getText();
        ActionContext ctx = new ActionContext(action, editor, psiFile, doc);

        if (option != null) {
            return applyWithOption(option, actionName, pathStr, targetLine, before, ctx);
        }
        if (dryRun) {
            return applyAsDryRun(actionName, pathStr, before, ctx, vf);
        }
        return applyNormally(actionName, pathStr, targetLine, before, ctx);
    }

    private String applyWithOption(String option, String actionName, String pathStr, int targetLine,
                                   String before, ActionContext ctx) {
        boolean selected = DialogInterceptor.runAndSelectOption(
            () -> WriteCommandAction.runWriteCommandAction(project, actionName, null,
                () -> ctx.action().invoke(project, ctx.editor(), ctx.psiFile())),
            option
        );
        PsiDocumentManager.getInstance(project).commitAllDocuments();
        FileDocumentManager.getInstance().saveAllDocuments();
        String after = ctx.doc().getText();
        String diff = DiffUtils.unifiedDiff(before, after, pathStr);
        if (!selected) {
            return "Option '" + option + "' not found in dialog for action '" + actionName + "'. "
                + "Use get_action_options to see available options.";
        }
        return formatApplyResult(actionName, pathStr, targetLine, diff, false);
    }

    private String applyAsDryRun(String actionName, String pathStr, String before,
                                 ActionContext ctx, VirtualFile vf) {
        WriteCommandAction.runWriteCommandAction(project, actionName, null,
            () -> ctx.action().invoke(project, ctx.editor(), ctx.psiFile()));
        PsiDocumentManager.getInstance(project).commitAllDocuments();
        String after = ctx.doc().getText();
        String diff = DiffUtils.unifiedDiff(before, after, pathStr);
        undoLastAction(vf);
        if (diff.isEmpty()) {
            return "Preview: action '" + actionName + "' would make no changes (it may require a dialog — "
                + "use get_action_options to check).";
        }
        return "Preview (not applied):\n\n" + diff;
    }

    private String applyNormally(String actionName, String pathStr, int targetLine,
                                 String before, ActionContext ctx) {
        WriteCommandAction.runWriteCommandAction(project, actionName, null,
            () -> ctx.action().invoke(project, ctx.editor(), ctx.psiFile()));
        PsiDocumentManager.getInstance(project).commitAllDocuments();
        FileDocumentManager.getInstance().saveAllDocuments();
        String after = ctx.doc().getText();
        String diff = DiffUtils.unifiedDiff(before, after, pathStr);
        if (diff.isEmpty()) {
            return ACTION_PREFIX + actionName + "' made no changes. It may require user input via a dialog. "
                + "Try get_action_options to inspect what dialog options it shows.";
        }
        return formatApplyResult(actionName, pathStr, targetLine, diff, true);
    }

    private record ActionContext(IntentionAction action, Editor editor, PsiFile psiFile, Document doc) {
    }

    private String formatApplyResult(String actionName, String pathStr, int line,
                                     String diff, boolean applied) {
        if (diff.isEmpty()) {
            return (applied ? "Applied" : "Selected option for") + " action: " + actionName
                + "\n  File: " + pathStr + LINE_LABEL + line + "\n  (no file changes)";
        }
        return (applied ? "Applied" : "Applied with option") + " action: " + actionName
            + "\n  File: " + pathStr + LINE_LABEL + line + "\n\n" + diff;
    }

    private void undoLastAction(VirtualFile vf) {
        try {
            var fem = FileEditorManager.getInstance(project);
            for (var fe : fem.getEditors(vf)) {
                if (fe instanceof TextEditor) {
                    var undoMgr = UndoManager.getInstance(project);
                    if (undoMgr.isUndoAvailable(fe)) {
                        PlatformApiCompat.undoOrRedoSilently(undoMgr, fe, true);
                        FileDocumentManager.getInstance().saveAllDocuments();
                    }
                    return;
                }
            }
        } catch (Exception e) {
            LOG.warn("Could not undo dry-run action", e);
        }
    }

    /**
     * Finds the named action by first searching highlight quick-fixes, then falling back to
     * {@code IntentionManager} intention actions at the current caret position.
     */
    @Nullable
    private IntentionAction findActionToApply(Document doc, int targetLine, String name,
                                              Editor editor, PsiFile psiFile) {
        for (var h : highlightsOnLine(doc, targetLine)) {
            IntentionAction found = h.findRegisteredQuickFix((descriptor, range) -> {
                IntentionAction a = descriptor.getAction();
                if (name.equals(a.getText())) return a;
                return null;
            });
            if (found != null) return found;
        }
        return findIntentionByName(name, editor, psiFile);
    }

    /**
     * Collects names from both highlight quick-fixes and available intentions at the current
     * caret position, for use in "not found" error messages.
     */
    private List<String> collectAvailableActionNames(Document doc, int targetLine,
                                                     Editor editor, PsiFile psiFile) {
        List<String> names = new ArrayList<>();
        highlightsOnLine(doc, targetLine).forEach(h -> names.addAll(collectQuickFixNames(h)));
        names.addAll(collectIntentionNames(editor, psiFile));
        return names;
    }

    /**
     * Resolves the 0-based caret column from a symbol name, an explicit column, or defaults to 0.
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
