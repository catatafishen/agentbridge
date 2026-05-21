package com.github.catatafishen.agentbridge.psi.tools.file;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.McpErrorCode;
import com.github.catatafishen.agentbridge.psi.ToolError;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class MoveFileTool extends FileTool {

    private static final String PARAM_DESTINATION = "destination";
    private static final int MOVE_TIMEOUT_SECONDS = 30;

    public MoveFileTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "move_file";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.MOVE;
    }

    @Override
    public @NotNull String displayName() {
        return "Move File";
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Move {path} -> {destination}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required("path", TYPE_STRING, "Path to the file to move (absolute or project-relative)"),
            Param.required(PARAM_DESTINATION, TYPE_STRING, "Destination directory path (absolute or project-relative)")
        );
    }

    @Override
    public @NotNull String description() {
        return "Move a file to a different directory using IntelliJ's refactoring engine when PSI is available. " +
            "Language-aware IDE move handlers update imports, package declarations, and references where supported. " +
            "Falls back to a plain VFS move only for files/directories the IDE cannot represent as PSI. " +
            "The destination directory is created automatically (including any missing parents) if it does not exist.";
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        if (!args.has("path") || !args.has(PARAM_DESTINATION))
            return ToolError.of(McpErrorCode.MISSING_PARAM,
                "'path' and 'destination' parameters are required");
        String pathStr = args.get("path").getAsString();
        String destStr = args.get(PARAM_DESTINATION).getAsString();

        // Resolve files outside ReadAction so refreshAndFindFileByPath can be used as a fallback
        // when the VFS cache is stale (same fix as RenameFileTool).
        VirtualFile vf = resolveVirtualFile(pathStr);
        if (vf == null) vf = refreshAndFindVirtualFile(pathStr);
        if (vf == null) return ToolError.of(McpErrorCode.FILE_NOT_FOUND, pathStr,
            "Check the path and try again. Use find_file to search by name.");

        VirtualFile destDir = resolveVirtualFile(destStr);
        if (destDir == null) destDir = refreshAndFindVirtualFile(destStr);

        // absoluteDestPath is non-null when we created the directory on disk.
        // VFS registration is deferred to the EDT inside performMoveOnEdt to avoid
        // creating an async refresh session from a pooled thread (which can deadlock in tests).
        String absoluteDestPath = null;
        if (destDir == null) {
            absoluteDestPath = createDirectoryOnDisk(destStr);
            if (absoluteDestPath == null)
                return ToolError.of(McpErrorCode.FILE_NOT_FOUND,
                    "Destination directory not found and could not be created: " + destStr);
        } else if (!destDir.isDirectory()) {
            return ToolError.of(McpErrorCode.FILE_NOT_FOUND,
                "Destination path is not a directory: " + destStr);
        }

        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        performMoveOnEdt(vf, destDir, absoluteDestPath, resultFuture);
        return resultFuture.get(MOVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private void performMoveOnEdt(VirtualFile vf, @Nullable VirtualFile destDir,
                                  @Nullable String absoluteDestPath,
                                  CompletableFuture<String> resultFuture) {
        EdtUtil.invokeLater(() -> {
            try {
                VirtualFile resolvedDestDir = destDir;
                if (resolvedDestDir == null && absoluteDestPath != null) {
                    // refreshAndFindFileByPath runs synchronously when called from the EDT
                    // (RefreshQueueImpl detects isDispatchThread() == true and runs inline),
                    // so no async session is created and no deadlock is possible.
                    resolvedDestDir = LocalFileSystem.getInstance()
                        .refreshAndFindFileByPath(absoluteDestPath);
                }
                if (resolvedDestDir == null || !resolvedDestDir.isDirectory()) {
                    resultFuture.complete(ToolError.of(McpErrorCode.FILE_NOT_FOUND,
                        "Destination directory could not be found after creation: " + absoluteDestPath));
                    return;
                }
                PsiMoveTarget target = resolvePsiMoveTarget(vf, resolvedDestDir);
                String result;
                if (target.canUseRefactoring()) {
                    result = performRefactoringMove(target);
                } else {
                    result = performPlainVfsMove(vf, resolvedDestDir);
                }
                resultFuture.complete(result);
            } catch (Exception e) {
                resultFuture.complete("Error moving file: " + e.getMessage());
            }
        });
    }

    /**
     * Creates the destination directory (and any missing parents) on disk using
     * {@link Files#createDirectories}. No VFS registration is performed here — that is
     * deferred to the EDT inside {@link #performMoveOnEdt}, where
     * {@code LocalFileSystem.refreshAndFindFileByPath} runs synchronously.
     *
     * @return the absolute path of the created directory, or {@code null} on failure
     */
    @Nullable
    private String createDirectoryOnDisk(String destStr) {
        Path dirPath = Path.of(destStr.replace('\\', '/'));
        if (!dirPath.isAbsolute()) {
            String basePath = project.getBasePath();
            if (basePath == null) return null;
            dirPath = Path.of(basePath, destStr);
        }
        try {
            Files.createDirectories(dirPath);
            return dirPath.toString();
        } catch (IOException e) {
            return null;
        }
    }

    private PsiMoveTarget resolvePsiMoveTarget(VirtualFile vf, VirtualFile destDir) {
        return ApplicationManager.getApplication().runReadAction(
            (com.intellij.openapi.util.Computable<PsiMoveTarget>) () -> {
                var psiManager = com.intellij.psi.PsiManager.getInstance(project);
                return new PsiMoveTarget(
                    vf,
                    destDir,
                    psiManager.findFile(vf),
                    psiManager.findDirectory(destDir)
                );
            });
    }

    private String performRefactoringMove(PsiMoveTarget target) {
        String oldPath = target.sourceFile().getPath();
        String newPath = com.intellij.openapi.util.io.FileUtil.join(
            target.destinationDirectory().getPath(),
            target.sourceFile().getName()
        );
        var document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(target.sourceFile());
        notifyBeforeEdit(project, target.sourceFile(), document);
        try {
            var processor = new com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesProcessor(
                project,
                new com.intellij.psi.PsiElement[]{target.psiFile()},
                target.psiDirectory(),
                true,
                true,
                true,
                null,
                null
            );
            processor.setPreviewUsages(false);
            processor.run();
            com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments();
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments();
            return "Moved " + oldPath + " to " + newPath + " using IntelliJ refactoring engine";
        } finally {
            notifyEditComplete();
        }
    }

    private String performPlainVfsMove(VirtualFile vf, VirtualFile destDir) {
        String oldPath = vf.getPath();
        MoveFileTool requestor = this;
        ApplicationManager.getApplication().runWriteAction(() ->
            com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(
                project,
                () -> {
                    try {
                        vf.move(requestor, destDir);
                    } catch (java.io.IOException e) {
                        throw new IllegalStateException("VFS move failed", e);
                    }
                },
                "Move File: " + vf.getName(),
                null
            )
        );
        return "Moved " + oldPath + " to " + destDir.getPath() + "/" + vf.getName() +
            " using plain VFS move (no PSI refactoring available)";
    }

    private record PsiMoveTarget(
        VirtualFile sourceFile,
        VirtualFile destinationDirectory,
        com.intellij.psi.PsiFile psiFile,
        com.intellij.psi.PsiDirectory psiDirectory) {

        private boolean canUseRefactoring() {
            return psiFile != null && psiDirectory != null && psiFile.isPhysical() && psiDirectory.isPhysical();
        }
    }
}
