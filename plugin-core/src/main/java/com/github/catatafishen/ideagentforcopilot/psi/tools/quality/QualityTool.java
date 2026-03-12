package com.github.catatafishen.ideagentforcopilot.psi.tools.quality;

import com.github.catatafishen.ideagentforcopilot.psi.ToolUtils;
import com.github.catatafishen.ideagentforcopilot.psi.tools.Tool;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.github.catatafishen.ideagentforcopilot.ui.renderers.InspectionResultRenderer;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;

/**
 * Abstract base for code quality tools. Provides shared constants and
 * utility methods used by multiple quality tool implementations.
 */
public abstract class QualityTool extends Tool {

    protected static final String ERROR_IDE_INITIALIZING =
        "{\"error\": \"IDE is still initializing. Please wait a moment and try again.\"}";
    protected static final String FORMAT_LOCATION = "%s:%d [%s] %s";
    protected static final String FORMAT_LINES_SUFFIX = " lines)";
    protected static final String PARAM_LIMIT = "limit";
    protected static final String PARAM_INSPECTION_ID = "inspection_id";
    protected static final String PARAM_SCOPE = "scope";
    protected static final String PARAM_OFFSET = "offset";

    protected QualityTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull ToolRegistry.Category category() {
        return ToolRegistry.Category.CODE_QUALITY;
    }

    @Override
    public @NotNull Object resultRenderer() {
        return InspectionResultRenderer.INSTANCE;
    }

    // ── Shared utilities ─────────────────────────────────────

    protected record FilePair(VirtualFile vf, PsiFile psiFile) {
    }

    protected FilePair resolveFilePair(String pathStr, CompletableFuture<String> future) {
        VirtualFile vf = resolveVirtualFile(pathStr);
        if (vf == null) {
            future.complete(ToolUtils.ERROR_FILE_NOT_FOUND + pathStr);
            return null;
        }
        PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
        if (psiFile == null) {
            future.complete(ToolUtils.ERROR_CANNOT_PARSE + pathStr);
            return null;
        }
        return new FilePair(vf, psiFile);
    }

    /**
     * Collects files for highlight/compilation-error analysis.
     * If pathStr is given, resolves that single file; otherwise iterates all source content.
     */
    protected Collection<VirtualFile> collectFilesForHighlightAnalysis(
        String pathStr, boolean includeUnindexed, ProjectFileIndex fileIndex,
        CompletableFuture<String> resultFuture) {
        Collection<VirtualFile> files = new ArrayList<>();
        if (pathStr != null && !pathStr.isEmpty()) {
            VirtualFile vf = resolveVirtualFile(pathStr);
            if (vf == null) {
                resultFuture.complete("Error: File not found: " + pathStr);
                return Collections.emptyList();
            }
            if (includeUnindexed || fileIndex.isInSourceContent(vf)) {
                files.add(vf);
            }
        } else {
            fileIndex.iterateContent(file -> {
                if (!file.isDirectory() && fileIndex.isInSourceContent(file)) {
                    files.add(file);
                }
                return true;
            });
        }
        return files;
    }
}
