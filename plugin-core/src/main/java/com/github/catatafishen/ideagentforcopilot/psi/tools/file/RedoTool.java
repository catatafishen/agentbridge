package com.github.catatafishen.ideagentforcopilot.psi.tools.file;

import com.github.catatafishen.ideagentforcopilot.psi.EdtUtil;
import com.github.catatafishen.ideagentforcopilot.psi.PlatformApiCompat;
import com.github.catatafishen.ideagentforcopilot.psi.ToolUtils;
import com.google.gson.JsonObject;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Redoes the last N undone actions on a file using IntelliJ's UndoManager.
 */
@SuppressWarnings("java:S112")
public final class RedoTool extends FileTool {

    private static final String PARAM_COUNT = "count";

    public RedoTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "redo";
    }

    @Override
    public @NotNull String displayName() {
        return "Redo";
    }

    @Override
    public @NotNull String description() {
        return "Redo the last N undone actions on a file using IntelliJ's UndoManager";
    }

    

    @Override
    public @NotNull String kind() {
        return "edit";
    }
@Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
            {"path", TYPE_STRING, "Path to the file to redo changes on"},
            {PARAM_COUNT, TYPE_INTEGER, "Number of redo steps (default: 1)"}
        }, "path");
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        if (!args.has("path")) return ToolUtils.ERROR_PATH_REQUIRED;
        String pathStr = args.get("path").getAsString();
        int count = args.has(PARAM_COUNT) ? args.get(PARAM_COUNT).getAsInt() : 1;

        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        EdtUtil.invokeLater(() -> performRedo(pathStr, count, resultFuture));
        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    private void performRedo(String pathStr, int count, CompletableFuture<String> resultFuture) {
        try {
            VirtualFile vf = resolveVirtualFile(pathStr);
            if (vf == null) {
                resultFuture.complete(ToolUtils.ERROR_FILE_NOT_FOUND + pathStr);
                return;
            }
            var fileEditor = findFileEditor(vf);
            UndoManager undoManager = UndoManager.getInstance(project);
            String result = executeRedoSteps(undoManager, fileEditor, count, pathStr);
            resultFuture.complete(result);
        } catch (Exception e) {
            resultFuture.complete("Redo failed: " + e.getMessage());
        }
    }

    private com.intellij.openapi.fileEditor.FileEditor findFileEditor(VirtualFile vf) {
        var editors = FileEditorManager.getInstance(project).getEditors(vf);
        for (var ed : editors) {
            if (ed instanceof TextEditor) return ed;
        }
        return editors.length > 0 ? editors[0] : null;
    }

    private String executeRedoSteps(UndoManager undoManager,
                                    com.intellij.openapi.fileEditor.FileEditor fileEditor,
                                    int count, String pathStr) {
        StringBuilder actions = new StringBuilder();
        int redone = 0;
        for (int i = 0; i < count; i++) {
            if (!undoManager.isRedoAvailable(fileEditor)) break;
            String actionName = PlatformApiCompat.getRedoActionName(undoManager, fileEditor);
            undoManager.redo(fileEditor);
            redone++;
            if (!actions.isEmpty()) actions.append(", ");
            actions.append(actionName != null && !actionName.isEmpty() ? actionName : "unknown");
        }
        if (redone == 0) {
            return "Nothing to redo for " + pathStr;
        }
        FileDocumentManager.getInstance().saveAllDocuments();
        return "Redid " + redone + " action(s) on " + pathStr + ": " + actions;
    }
}
