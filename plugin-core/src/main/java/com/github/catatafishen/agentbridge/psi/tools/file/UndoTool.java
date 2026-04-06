package com.github.catatafishen.agentbridge.psi.tools.file;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.ui.renderers.SimpleStatusRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Undoes the last N edit actions on a file using IntelliJ's UndoManager.
 */
@SuppressWarnings("java:S112")
public final class UndoTool extends FileTool {

    private static final String PARAM_COUNT = "count";

    public UndoTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "undo";
    }

    @Override
    public @NotNull String displayName() {
        return "Undo";
    }

    @Override
    public @NotNull String description() {
        return "Undo the last N edit actions on a file using IntelliJ's UndoManager";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
            {"path", TYPE_STRING, "Path to the file to undo changes on"},
            {PARAM_COUNT, TYPE_INTEGER, "Number of undo steps (default: 1). Each write + auto-format counts as 2 steps"}
        }, "path");
    }

    @Override
    public @NotNull Object resultRenderer() {
        return SimpleStatusRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        if (!args.has("path")) return ToolUtils.ERROR_PATH_REQUIRED;
        String pathStr = args.get("path").getAsString();
        int count = args.has(PARAM_COUNT) ? args.get(PARAM_COUNT).getAsInt() : 1;

        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        EdtUtil.invokeLater(() -> performUndo(pathStr, count, resultFuture));
        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    private void performUndo(String pathStr, int count, CompletableFuture<String> resultFuture) {
        try {
            VirtualFile vf = resolveVirtualFile(pathStr);
            if (vf == null) {
                resultFuture.complete(ToolUtils.ERROR_FILE_NOT_FOUND + pathStr);
                return;
            }
            var fileEditor = findFileEditor(vf);
            UndoManager undoManager = UndoManager.getInstance(project);
            String result = executeUndoSteps(undoManager, fileEditor, count, pathStr);
            resultFuture.complete(result);
        } catch (Exception e) {
            resultFuture.complete("Undo failed: " + e.getMessage());
        }
    }

    private com.intellij.openapi.fileEditor.FileEditor findFileEditor(VirtualFile vf) {
        var editors = FileEditorManager.getInstance(project).getEditors(vf);
        for (var ed : editors) {
            if (ed instanceof TextEditor) return ed;
        }
        return editors.length > 0 ? editors[0] : null;
    }

    private String executeUndoSteps(UndoManager undoManager,
                                    com.intellij.openapi.fileEditor.FileEditor fileEditor,
                                    int count, String pathStr) {
        StringBuilder actions = new StringBuilder();
        int undone = 0;
        for (int i = 0; i < count; i++) {
            if (!undoManager.isUndoAvailable(fileEditor)) break;
            String actionName = PlatformApiCompat.getUndoActionName(undoManager, fileEditor);
            PlatformApiCompat.undoOrRedoSilently(undoManager, fileEditor, true);
            undone++;
            if (!actions.isEmpty()) actions.append(", ");
            actions.append(actionName != null && !actionName.isEmpty() ? actionName : "unknown");
        }
        if (undone == 0) {
            return "Nothing to undo for " + pathStr;
        }
        FileDocumentManager.getInstance().saveAllDocuments();
        return "Undid " + undone + " action(s) on " + pathStr + ": " + actions;
    }
}
