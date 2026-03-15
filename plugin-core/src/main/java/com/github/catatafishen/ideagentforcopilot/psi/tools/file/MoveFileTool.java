package com.github.catatafishen.ideagentforcopilot.psi.tools.file;

import com.github.catatafishen.ideagentforcopilot.psi.EdtUtil;
import com.github.catatafishen.ideagentforcopilot.psi.ToolUtils;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Moves a file to a different directory.
 */
@SuppressWarnings("java:S112")
public final class MoveFileTool extends FileTool {

    private static final String PARAM_DESTINATION = "destination";

    public MoveFileTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "move_file";
    }

    @Override
    public @NotNull String displayName() {
        return "Move File";
    }

    @Override
    public @NotNull String description() {
        return "Move a file to a different directory";
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Move {path} → {destination}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
            {"path", TYPE_STRING, "Path to the file to move (absolute or project-relative)"},
            {PARAM_DESTINATION, TYPE_STRING, "Destination directory path (absolute or project-relative)"}
        }, "path", PARAM_DESTINATION);
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        if (!args.has("path") || !args.has(PARAM_DESTINATION))
            return ToolUtils.ERROR_PREFIX + "'path' and 'destination' parameters are required";
        String pathStr = args.get("path").getAsString();
        String destStr = args.get(PARAM_DESTINATION).getAsString();

        // Resolve files outside ReadAction so refreshAndFindFileByPath can be used as a fallback
        // when the VFS cache is stale (same fix as RenameFileTool).
        VirtualFile vf = resolveVirtualFile(pathStr);
        if (vf == null) vf = refreshAndFindVirtualFile(pathStr);
        if (vf == null) return ToolUtils.ERROR_PREFIX + ToolUtils.ERROR_FILE_NOT_FOUND + pathStr;

        VirtualFile destDir = resolveVirtualFile(destStr);
        if (destDir == null) destDir = refreshAndFindVirtualFile(destStr);
        if (destDir == null || !destDir.isDirectory())
            return ToolUtils.ERROR_PREFIX + "Destination directory not found: " + destStr;

        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        performMoveOnEdt(vf, destDir, resultFuture);
        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    private void performMoveOnEdt(VirtualFile vf, VirtualFile destDir, CompletableFuture<String> resultFuture) {
        String oldPath = vf.getPath();
        MoveFileTool requestor = this;
        EdtUtil.invokeLater(() ->
            ApplicationManager.getApplication().runWriteAction(() -> {
                try {
                    com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(
                        project,
                        () -> {
                            try {
                                vf.move(requestor, destDir);
                            } catch (java.io.IOException e) {
                                throw new RuntimeException(e);
                            }
                        },
                        "Move File: " + vf.getName(),
                        null
                    );
                    resultFuture.complete("Moved " + oldPath + " to " + destDir.getPath() + "/" + vf.getName());
                } catch (Exception e) {
                    resultFuture.complete("Error moving file: " + e.getMessage());
                }
            })
        );
    }
}
