package com.github.catatafishen.agentbridge.psi.tools.editor;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.ui.renderers.IdeInfoRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Gets the path and content of the currently active editor file.
 */
public final class GetActiveFileTool extends EditorTool {

    public GetActiveFileTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "get_active_file";
    }

    @Override
    public @NotNull String displayName() {
        return "Get Active File";
    }

    @Override
    public @NotNull String description() {
        return "Get the path and content of the currently active editor file";
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
    public @NotNull Object resultRenderer() {
        return IdeInfoRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        EdtUtil.invokeLater(() -> {
            try {
                var editorManager = FileEditorManager.getInstance(project);
                var editor = editorManager.getSelectedTextEditor();
                if (editor == null) {
                    resultFuture.complete("No active editor");
                    return;
                }
                var doc = editor.getDocument();
                VirtualFile vf = FileDocumentManager.getInstance().getFile(doc);
                if (vf == null) {
                    resultFuture.complete("No file associated with active editor");
                    return;
                }

                String basePath = project.getBasePath();
                String filePath = vf.getPath();
                String displayPath = basePath != null ? relativize(basePath, filePath) : filePath;
                int line = editor.getCaretModel().getLogicalPosition().line + 1;
                int column = editor.getCaretModel().getLogicalPosition().column + 1;

                resultFuture.complete(displayPath + " (line " + line + ", column " + column + ")");
            } catch (Exception e) {
                resultFuture.complete("Error: " + e.getMessage());
            }
        });

        return resultFuture.get(10, TimeUnit.SECONDS);
    }
}
