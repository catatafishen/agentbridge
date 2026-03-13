package com.github.catatafishen.ideagentforcopilot.psi.tools.file;

import com.github.catatafishen.ideagentforcopilot.psi.EdtUtil;
import com.github.catatafishen.ideagentforcopilot.psi.ToolUtils;
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

        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        final RenameFileTool requestor = this;

        ReadAction.nonBlocking(() -> {
            try {
                VirtualFile vf = resolveVirtualFile(pathStr);
                if (vf == null) {
                    resultFuture.complete(ToolUtils.ERROR_PREFIX + ToolUtils.ERROR_FILE_NOT_FOUND + pathStr);
                    return null;
                }
                String oldName = vf.getName();
                EdtUtil.invokeLater(() ->
                    WriteAction.run(() -> {
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
                return null;
            } catch (Exception e) {
                resultFuture.complete(ToolUtils.ERROR_PREFIX + e.getMessage());
                return null;
            }
        }).inSmartMode(project).submit(AppExecutorUtil.getAppExecutorService());

        return resultFuture.get(10, TimeUnit.SECONDS);
    }
}
