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
            Param.required(PARAM_DESTINATION, TYPE_STRING,
                "Destination path. Can be a directory (to move into it keeping the filename) " +
                    "or a full target path including a new filename (to move + rename). " +
                    "The destination directory is created automatically (including any missing parents) if it does not exist.")
        );
    }

    @Override
    public @NotNull String description() {
        return "Move a file to a different directory using IntelliJ's refactoring engine when PSI is available. " +
            "Language-aware IDE move handlers update imports, package declarations, and references where supported. " +
            "Falls back to a plain VFS move only for files/directories the IDE cannot represent as PSI. " +
            "The destination can be a directory path (file keeps its name) or a full target path including a new " +
            "filename (move + rename in one step). The destination directory is created automatically " +
            "(including any missing parents) if it does not exist.";
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        if (!args.has("path") || !args.has(PARAM_DESTINATION))
            return ToolError.of(McpErrorCode.MISSING_PARAM,
                "'path' and 'destination' parameters are required");
        String pathStr = args.get("path").getAsString();
        String destStr = args.get(PARAM_DESTINATION).getAsString();

        VirtualFile vf = resolveVirtualFile(pathStr);
        if (vf == null) vf = refreshAndFindVirtualFile(pathStr);
        if (vf == null) return ToolError.of(McpErrorCode.FILE_NOT_FOUND, pathStr,
            "Check the path and try again. Use find_file to search by name.");

        VirtualFile destFile = resolveVirtualFile(destStr);
        if (destFile == null) destFile = refreshAndFindVirtualFile(destStr);

        VirtualFile destDir;
        String newFileName = null;   // null = keep source filename
        String absoluteDestPath = null;

        if (destFile != null && destFile.isDirectory()) {
            // Existing directory — move into it, keep filename.
            destDir = destFile;
        } else if (destFile != null) {
            // Destination exists but is a file, not a directory.
            return ToolError.of(McpErrorCode.FILE_NOT_FOUND,
                "Destination path exists but is not a directory: " + destStr);
        } else {
            // Destination doesn't exist — determine intent from whether the parent exists.
            Path destPath = Path.of(destStr.replace('\\', '/'));
            if (!destPath.isAbsolute()) {
                String basePath = project.getBasePath();
                if (basePath != null) destPath = Path.of(basePath).resolve(destPath);
            }
            Path parent = destPath.getParent();
            VirtualFile parentVf = null;
            if (parent != null) {
                parentVf = resolveVirtualFile(parent.toString());
                if (parentVf == null) parentVf = refreshAndFindVirtualFile(parent.toString());
            }
            if (parentVf != null && parentVf.isDirectory()) {
                String leaf = destPath.getFileName().toString();
                if (leaf.contains(".")) {
                    // Leaf has a file extension → treat as full target path (move + rename).
                    destDir = parentVf;
                    newFileName = leaf;
                } else {
                    // No extension → destination is a new directory; create it.
                    try {
                        absoluteDestPath = createDirectoryOnDisk(destStr);
                        destDir = null;
                    } catch (IOException e) {
                        return ToolError.of(McpErrorCode.INTERNAL_ERROR,
                            "Destination directory could not be created: " + destStr + " — " + e.getMessage());
                    }
                }
            } else {
                // No existing parent — create the full destination path as a new directory.
                try {
                    absoluteDestPath = createDirectoryOnDisk(destStr);
                    destDir = null;
                } catch (IOException e) {
                    return ToolError.of(McpErrorCode.INTERNAL_ERROR,
                        "Destination directory could not be created: " + destStr + " — " + e.getMessage());
                }
            }
        }

        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        performMoveOnEdt(vf, destDir, absoluteDestPath, newFileName, resultFuture);
        return resultFuture.get(MOVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private void performMoveOnEdt(VirtualFile vf, @Nullable VirtualFile destDir,
                                  @Nullable String absoluteDestPath,
                                  @Nullable String newFileName,
                                  CompletableFuture<String> resultFuture) {
        EdtUtil.invokeLater(() -> {
            try {
                VirtualFile resolvedDestDir = destDir;
                if (resolvedDestDir == null && absoluteDestPath != null) {
                    // refreshAndFindFileByPath runs synchronously on the EDT.
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
                    result = performRefactoringMove(target, newFileName);
                } else {
                    result = performPlainVfsMove(vf, resolvedDestDir, newFileName);
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
     * @return the absolute path of the created directory
     * @throws IOException if the directory could not be created (permissions, I/O failure, etc.)
     */
    @NotNull
    private String createDirectoryOnDisk(String destStr) throws IOException {
        Path dirPath = Path.of(destStr.replace('\\', '/'));
        if (!dirPath.isAbsolute()) {
            String basePath = project.getBasePath();
            if (basePath == null) throw new IOException("Cannot resolve relative path: project base path is unknown");
            dirPath = Path.of(basePath, destStr);
        }
        Files.createDirectories(dirPath);
        return dirPath.toString();
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

    private String performRefactoringMove(PsiMoveTarget target, @Nullable String newFileName) {
        String oldPath = target.sourceFile().getPath();
        String finalName = newFileName != null ? newFileName : target.sourceFile().getName();
        String newPath = com.intellij.openapi.util.io.FileUtil.join(
            target.destinationDirectory().getPath(),
            finalName
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
            // After the move, target.sourceFile() now lives in destinationDirectory.
            // If a rename was requested, run a separate PSI rename refactoring.
            if (newFileName != null) {
                var movedPsi = com.intellij.psi.PsiManager.getInstance(project).findFile(target.sourceFile());
                if (movedPsi != null && movedPsi.isValid()) {
                    var renameProcessor = new com.intellij.refactoring.rename.RenameProcessor(
                        project, movedPsi, newFileName, false, false);
                    renameProcessor.setPreviewUsages(false);
                    renameProcessor.run();
                }
            }
            com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments();
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments();
            return "Moved " + oldPath + " to " + newPath + " using IntelliJ refactoring engine";
        } finally {
            notifyEditComplete();
        }
    }

    private String performPlainVfsMove(VirtualFile vf, VirtualFile destDir, @Nullable String newFileName) {
        String oldPath = vf.getPath();
        String finalName = newFileName != null ? newFileName : vf.getName();
        MoveFileTool requestor = this;
        ApplicationManager.getApplication().runWriteAction(() ->
            com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(
                project,
                () -> {
                    try {
                        vf.move(requestor, destDir);
                        if (newFileName != null) {
                            vf.rename(requestor, newFileName);
                        }
                    } catch (java.io.IOException e) {
                        throw new IllegalStateException("VFS move failed", e);
                    }
                },
                "Move File: " + vf.getName() + (newFileName != null ? " -> " + newFileName : ""),
                null
            )
        );
        return "Moved " + oldPath + " to " + destDir.getPath() + "/" + finalName +
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
