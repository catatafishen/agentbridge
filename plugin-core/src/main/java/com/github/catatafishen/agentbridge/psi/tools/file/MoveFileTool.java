package com.github.catatafishen.agentbridge.psi.tools.file;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

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
    public boolean requiresIndex() {
        return true;
    }

    @Override
    public @NotNull String displayName() {
        return "Move File";
    }

    @Override
    public @NotNull String description() {
        return "Move a file to a different directory using IntelliJ's refactoring engine when PSI is available. " +
            "Language-aware IDE move handlers update imports, package declarations, and references where supported. " +
            "Falls back to a plain VFS move only for files/directories the IDE cannot represent as PSI.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.MOVE;
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
        return resultFuture.get(MOVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private void performMoveOnEdt(VirtualFile vf, VirtualFile destDir, CompletableFuture<String> resultFuture) {
        EdtUtil.invokeLater(() -> {
            try {
                PsiMoveTarget target = resolvePsiMoveTarget(vf, destDir);
                String result;
                if (target.canUseRefactoring()) {
                    result = performRefactoringMove(target);
                } else {
                    result = performPlainVfsMove(vf, destDir);
                }
                resultFuture.complete(result);
            } catch (Exception e) {
                resultFuture.complete("Error moving file: " + e.getMessage());
            }
        });
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
        String newPath = target.destinationDirectory().getPath() + "/" + target.sourceFile().getName();
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
