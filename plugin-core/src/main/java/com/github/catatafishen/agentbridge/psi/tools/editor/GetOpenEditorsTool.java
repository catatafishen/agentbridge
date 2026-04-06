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
 * Lists all currently open editor tabs.
 */
public final class GetOpenEditorsTool extends EditorTool {

    public GetOpenEditorsTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "get_open_editors";
    }

    @Override
    public @NotNull String displayName() {
        return "Get Open Editors";
    }

    @Override
    public @NotNull String description() {
        return "List all currently open editor tabs";
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
                resultFuture.complete(buildEditorListOnEdt());
            } catch (Exception e) {
                resultFuture.complete("Error: " + e.getMessage());
            }
        });
        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    private String buildEditorListOnEdt() {
        var editorManager = FileEditorManager.getInstance(project);
        VirtualFile[] openFiles = editorManager.getOpenFiles();
        if (openFiles.length == 0) return "No open editors";

        var selectedEditor = editorManager.getSelectedTextEditor();
        VirtualFile activeFile = selectedEditor != null
            ? FileDocumentManager.getInstance().getFile(selectedEditor.getDocument())
            : null;

        String basePath = project.getBasePath();
        StringBuilder sb = new StringBuilder();
        sb.append("Open editors (").append(openFiles.length).append("):\n");
        for (VirtualFile file : openFiles) {
            String filePath = file.getPath();
            String displayPath = basePath != null ? relativize(basePath, filePath) : filePath;
            boolean isActive = file.equals(activeFile);
            boolean isModified = FileDocumentManager.getInstance().isFileModified(file);
            sb.append(isActive ? "* " : "  ");
            sb.append(displayPath);
            if (isModified) sb.append(" [modified]");
            sb.append('\n');
        }
        return sb.toString().trim();
    }
}
