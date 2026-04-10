package com.github.catatafishen.agentbridge.psi.tools.quality;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.ui.renderers.SimpleStatusRenderer;
import com.google.gson.JsonObject;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.openapi.command.WriteCommandAction;
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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs an intention action and intercepts any dialog it opens, returning the dialog options
 * without applying any changes. Useful when {@code apply_action} silently does nothing because
 * the action requires user input via a popup dialog.
 *
 * <p>The action is invoked on a copy of the document state inside a write-command action.
 * If a dialog appears, its radio buttons, checkboxes, text inputs and buttons are captured,
 * then the dialog is cancelled. Any partial changes are undone. The options are returned so
 * the agent can call {@code apply_action} with the {@code option} parameter to select one.</p>
 *
 * <p>If no dialog appears, the result shows what the action changed (as a unified diff).</p>
 */
public final class GetActionOptionsTool extends QualityTool {

    private static final Logger LOG = Logger.getInstance(GetActionOptionsTool.class);
    private static final String PARAM_ACTION_NAME = "action_name";
    private static final String PARAM_COLUMN = "column";
    private static final String PARAM_SYMBOL = "symbol";
    private static final String ACTION_PREFIX = "Action '";

    public GetActionOptionsTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "get_action_options";
    }

    @Override
    public @NotNull String displayName() {
        return "Get Action Options";
    }

    @Override
    public @NotNull String description() {
        return "Run an intention action and capture any dialog it shows, without applying changes. "
            + "Returns the radio-button / checkbox options from the dialog so the agent can call "
            + "apply_action with the 'option' parameter to select one. "
            + "If the action makes changes without a dialog, returns the diff preview and then undoes it. "
            + "Use 'symbol' (preferred) or 'column' to position the caret at the correct symbol.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required("file", TYPE_STRING, "Path to the file"),
            Param.required("line", TYPE_INTEGER, "Line number (1-based)"),
            Param.required(PARAM_ACTION_NAME, TYPE_STRING, "Exact action name from get_available_actions output"),
            Param.optional(PARAM_SYMBOL, TYPE_STRING, "Symbol name on the line (e.g. '_scrollRAF'). "
                + "Auto-detects the column — preferred over specifying 'column' manually."),
            Param.optional(PARAM_COLUMN, TYPE_INTEGER, "Column number (1-based, optional). Use 'symbol' instead when possible.")
        );
    }

    @Override
    public @NotNull Object resultRenderer() {
        return SimpleStatusRenderer.INSTANCE;
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

        CompletableFuture<String> future = new CompletableFuture<>();
        EdtUtil.invokeLater(() -> {
            try {
                future.complete(captureOptions(pathStr, targetLine, actionName, symbol, targetCol));
            } catch (Exception e) {
                LOG.warn("Error capturing options for '" + actionName + "' at " + pathStr + ":" + targetLine, e);
                future.complete(ToolUtils.ERROR_PREFIX + e.getMessage());
            }
        });
        return future.get(30, TimeUnit.SECONDS);
    }

    private String captureOptions(String pathStr, int targetLine, String actionName,
                                  @Nullable String symbol, @Nullable Integer targetCol) {
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
        if (editor == null) return "Error: Could not open editor for " + pathStr;

        int caretCol = resolveColumn(doc, targetLine, symbol, targetCol);
        editor.getCaretModel().moveToLogicalPosition(new LogicalPosition(targetLine - 1, caretCol));

        IntentionAction action = findIntentionByName(actionName, editor, psiFile);
        if (action == null) {
            return ACTION_PREFIX + actionName + "' not found at " + pathStr + ":" + targetLine
                + ". Use get_available_actions to list available actions.";
        }
        if (!action.isAvailable(project, editor, psiFile)) {
            return ACTION_PREFIX + actionName + "' is not currently applicable at " + pathStr + ":" + targetLine;
        }

        // Capture document text before running the action
        String before = doc.getText();

        // Run the action and intercept any dialog it opens
        DialogInterceptor.DialogInfo dialogInfo = DialogInterceptor.runAndCapture(
            () -> WriteCommandAction.runWriteCommandAction(project, actionName, null,
                () -> action.invoke(project, editor, psiFile))
        );

        PsiDocumentManager.getInstance(project).commitAllDocuments();
        String after = doc.getText();

        if (dialogInfo != null) {
            // A dialog was intercepted — no changes were committed (dialog was cancelled)
            return formatDialogOptions(actionName, pathStr, targetLine, dialogInfo);
        }

        // No dialog — action ran headlessly. Show the diff then undo.
        String diff = DiffUtils.unifiedDiff(before, after, pathStr);
        if (diff.isEmpty()) {
            return ACTION_PREFIX + actionName + "' made no changes and showed no dialog. "
                + "It may require a different caret position or context.";
        }

        // Undo the change so this was truly a preview
        undoLastAction(vf);

        return ACTION_PREFIX + actionName + "' makes the following changes (no dialog — use apply_action to apply):\n\n" + diff;
    }

    private String formatDialogOptions(String actionName, String pathStr, int line,
                                       DialogInterceptor.DialogInfo info) {
        StringBuilder sb = new StringBuilder();
        sb.append(ACTION_PREFIX).append(actionName).append("' at ").append(pathStr).append(':').append(line)
            .append(" shows a dialog with the following options:\n");

        if (!info.radioButtons().isEmpty()) {
            sb.append("\nRadio buttons (mutually exclusive — pick one):\n");
            for (int i = 0; i < info.radioButtons().size(); i++) {
                sb.append("  [").append(i).append("] ").append(info.radioButtons().get(i)).append('\n');
            }
        }
        if (!info.checkBoxes().isEmpty()) {
            sb.append("\nCheckboxes (can combine):\n");
            info.checkBoxes().forEach(c -> sb.append("  • ").append(c).append('\n'));
        }
        if (!info.textInputs().isEmpty()) {
            sb.append("\nText fields (current values):\n");
            info.textInputs().forEach(t -> sb.append("  \"").append(t).append("\"\n"));
        }
        if (!info.buttons().isEmpty()) {
            sb.append("\nDialog buttons: ").append(String.join(", ", info.buttons())).append('\n');
        }

        if (!info.radioButtons().isEmpty()) {
            sb.append("\nTo apply with a specific option, call:\n")
                .append("  apply_action(file, line, action_name=\"").append(actionName)
                .append("\", option=\"<option text>\")");
        }
        return sb.toString();
    }

    private void undoLastAction(VirtualFile vf) {
        try {
            var fem = FileEditorManager.getInstance(project);
            for (var fe : fem.getEditors(vf)) {
                if (fe instanceof TextEditor) {
                    var undoMgr = com.intellij.openapi.command.undo.UndoManager.getInstance(project);
                    if (undoMgr.isUndoAvailable(fe)) {
                        PlatformApiCompat.undoOrRedoSilently(undoMgr, fe, true);
                        FileDocumentManager.getInstance().saveAllDocuments();
                    }
                    return;
                }
            }
        } catch (Exception e) {
            LOG.warn("Could not undo preview action", e);
        }
    }

    /**
     * Resolves the 0-based caret column from either a symbol name or an explicit column.
     * Falls back to column 0 (start of line) if neither is provided.
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
