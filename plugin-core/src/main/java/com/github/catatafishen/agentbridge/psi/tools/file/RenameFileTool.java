package com.github.catatafishen.agentbridge.psi.tools.file;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Renames a file in place without moving it to a different directory.
 */
@SuppressWarnings("java:S112")
public final class RenameFileTool extends FileTool {

    private static final String PARAM_NEW_NAME = "new_name";

    public RenameFileTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "rename_file";
    }

    @Override
    public @NotNull String displayName() {
        return "Rename File";
    }

    @Override
    public @NotNull String description() {
        return "Rename a file in place without moving it to a different directory";
    }

    

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }
@Override
    public @NotNull String permissionTemplate() {
        return "Rename {path} → {new_name}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
            {"path", TYPE_STRING, "Path to the file to rename (absolute or project-relative)"},
            {PARAM_NEW_NAME, TYPE_STRING, "New file name (just the filename, not a full path)"}
        }, "path", PARAM_NEW_NAME);
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        if (!args.has("path") || !args.has(PARAM_NEW_NAME))
            return ToolUtils.ERROR_PREFIX + "'path' and 'new_name' parameters are required";
        String pathStr = args.get("path").getAsString();
        String newName = args.get(PARAM_NEW_NAME).getAsString();

        // Resolve the file outside ReadAction so refreshAndFindFileByPath can be used as a fallback.
        // findFileByPath reads from the VFS cache only; if the cache is stale (e.g. the file was just
        // created by another tool and the file-watcher event hasn't fired yet) it returns null.
        // refreshAndFindFileByPath forces a synchronous VFS refresh for that specific path.
        VirtualFile vf = resolveVirtualFile(pathStr);
        if (vf == null) vf = refreshAndFindVirtualFile(pathStr);
        if (vf == null) return ToolUtils.ERROR_PREFIX + ToolUtils.ERROR_FILE_NOT_FOUND + pathStr;

        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        performRenameOnEdt(vf, newName, resultFuture);
        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    private void performRenameOnEdt(VirtualFile vf, String newName, CompletableFuture<String> resultFuture) {
        String oldName = vf.getName();
        RenameFileTool requestor = this;
        EdtUtil.invokeLater(() ->
            com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction(() -> {
                try {
                    com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(
                        project,
                        () -> {
                            try {
                                vf.rename(requestor, newName);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        },
                        "Rename File: " + oldName + " to " + newName,
                        null
                    );
                    resultFuture.complete("Renamed " + oldName + " to " + newName);
                } catch (Exception e) {
                    resultFuture.complete("Error renaming file: " + e.getMessage());
                }
            })
        );
    }
}
