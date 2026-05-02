package com.github.catatafishen.agentbridge.psi.tools.file;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.McpErrorCode;
import com.github.catatafishen.agentbridge.psi.ToolError;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.ui.renderers.SimpleStatusRenderer;
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
        return "Delete a file from the project. This is permanent and cannot be undone with the undo tool.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.DELETE;
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
        return schema(
            Param.required("path", TYPE_STRING, "Path to the file to delete (absolute or project-relative)")
        );
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
                    resultFuture.complete(ToolError.of(McpErrorCode.FILE_NOT_FOUND, pathStr,
                        "Check the path and try again. Use find_file to search by name."));
                    return null;
                }
                if (vf.isDirectory()) {
                    resultFuture.complete(ToolError.of(McpErrorCode.INVALID_PARAM,
                        "Cannot delete directories. Path is a directory: " + pathStr));
                    return null;
                }
                scheduleFileDeletion(vf, pathStr, resultFuture);
                return null;
            } catch (Exception e) {
                resultFuture.complete(ToolError.of(McpErrorCode.INTERNAL_ERROR, e.getMessage()));
                return null;
            }
        }).inSmartMode(project).submit(AppExecutorUtil.getAppExecutorService());

        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    private void scheduleFileDeletion(VirtualFile vf, String pathStr, CompletableFuture<String> resultFuture) {
        notifyBeforeDelete(project, vf);
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
