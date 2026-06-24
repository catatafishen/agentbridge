package com.github.catatafishen.agentbridge.services.hooks;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/**
 * Classifies a file against the IntelliJ project model, returning the source-root category used by
 * the hook layer: one of {@code "sources"}, {@code "test_sources"}, {@code "resources"},
 * {@code "test_resources"}, {@code "generated_sources"}, {@code "generated_test_sources"},
 * {@code "excluded"}, {@code "content"}, or {@code ""} when the file is outside all content roots.
 *
 * <p>Shared by {@link HookHostApi#classify} (the in-process {@code Hook.classify} bridge) and
 * {@link HookQueryHandler} (the {@code /hooks/query} HTTP bridge) so both report identical
 * classifications. The read action is performed internally.
 */
final class SourceRootClassifier {

    private SourceRootClassifier() {
    }

    static @NotNull String classify(@NotNull Project project, @NotNull VirtualFile file) {
        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            ProjectFileIndex index = ProjectFileIndex.getInstance(project);
            if (index.isExcluded(file)) return "excluded";
            String sourceClass = PlatformApiCompat.classifyFileSourceRoot(index, file);
            if (!sourceClass.isEmpty()) return sourceClass;
            if (index.getContentRootForFile(file) != null) return "content";
            return "";
        });
    }
}
