package com.github.catatafishen.ideagentforcopilot.psi.tools.file;

import com.github.catatafishen.ideagentforcopilot.psi.EdtUtil;
import com.github.catatafishen.ideagentforcopilot.psi.ToolUtils;
import com.github.catatafishen.ideagentforcopilot.ui.renderers.SimpleStatusRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Deletes a file from the project via IntelliJ.
 */
@SuppressWarnings("java:S112")
public final class DeleteFileTool extends FileTool {

    public DeleteFileTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "delete_file";
    }

    @Override
    public @NotNull String displayName() {
        return "Delete File";
    }

    @Override
    public @NotNull String description() {
        return "Delete a file from the project via IntelliJ";
    }



    @Override
    public @NotNull Kind kind() {
        return Kind.EXECUTE;
    }
@Override
    public boolean isDestructive() {
        return true;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Delete {path}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
            {"path", TYPE_STRING, "Path to the file to delete (absolute or project-relative)"}
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

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        ReadAction.nonBlocking(() -> {
            try {
                VirtualFile vf = resolveVirtualFile(pathStr);
                if (vf == null) {
                    resultFuture.complete(ToolUtils.ERROR_PREFIX + ToolUtils.ERROR_FILE_NOT_FOUND + pathStr);
                    return null;
                }
                if (vf.isDirectory()) {
                    resultFuture.complete("Error: Cannot delete directories. Path is a directory: " + pathStr);
                    return null;
                }
                scheduleFileDeletion(vf, pathStr, resultFuture);
                return null;
            } catch (Exception e) {
                resultFuture.complete(ToolUtils.ERROR_PREFIX + e.getMessage());
                return null;
            }
        }).inSmartMode(project).submit(AppExecutorUtil.getAppExecutorService());

        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    private void scheduleFileDeletion(VirtualFile vf, String pathStr, CompletableFuture<String> resultFuture) {
        final DeleteFileTool requestor = this;
        EdtUtil.invokeLater(() ->
            WriteAction.run(() -> {
                try {
                    com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(
                        project,
                        () -> {
                            try {
                                vf.delete(requestor);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        },
                        "Delete File: " + vf.getName(),
                        null
                    );
                    resultFuture.complete("Deleted file: " + pathStr);
                } catch (Exception e) {
                    resultFuture.complete("Error deleting file: " + e.getMessage());
                }
            })
        );
    }
}
