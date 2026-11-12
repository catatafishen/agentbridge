package com.github.catatafishen.agentbridge.psi.tools.navigation;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.intellij.openapi.fileTypes.FileTypeRegistry;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectCoreUtil;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.DelegatingGlobalSearchScope;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.ProjectScope;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Applies a {@code scope} {@link FileCategory.Selection} for the search and listing tools, so all of them
 * interpret it the same way.
 * <p>
 * Content-scanning tools use {@link #scan}; index-based tools use {@link #searchScope}. Directories marked
 * "Excluded" are deliberately never visited: the IDE does not index them, and the tools only bridge what the
 * IDE sees. {@link #excludedRoots} reports them so agents know they exist. Must be called inside a read action.
 */
public final class ProjectFileScanner {

    @FunctionalInterface
    public interface Visitor {
        /**
         * @return true to continue scanning, false to stop
         */
        boolean visit(@NotNull VirtualFile file, @NotNull FileCategory category);
    }

    private ProjectFileScanner() {
    }

    /**
     * Category of any file: {@link FileCategory#LIBRARIES} for files outside the project content, otherwise
     * the category of its marked root.
     */
    public static @NotNull FileCategory categorize(@NotNull ProjectFileIndex index, @NotNull VirtualFile vf) {
        if (!index.isInContent(vf)) return FileCategory.LIBRARIES;
        // Detect by name: vf.getFileType() may load content for auto-detection.
        boolean ide = ProjectCoreUtil.isProjectOrWorkspaceFile(vf,
            FileTypeRegistry.getInstance().getFileTypeByFileName(vf.getNameSequence()));
        String rootKind = ide ? "" : PlatformApiCompat.classifyFileSourceRoot(index, vf);
        return FileCategory.classify(ide, rootKind);
    }

    /**
     * Visits project content files in the selected categories. Library files are never visited.
     */
    public static void scan(@NotNull Project project, @NotNull FileCategory.Selection selection,
                            @NotNull Visitor visitor) {
        ProjectFileIndex index = ProjectFileIndex.getInstance(project);
        index.iterateContent(vf -> {
            if (vf.isDirectory()) return true;
            FileCategory c = categorize(index, vf);
            return !selection.includes(c) || visitor.visit(vf, c);
        });
    }

    /**
     * Index search scope for a selection: project content and/or libraries, narrowed to the selected
     * categories.
     */
    public static @NotNull GlobalSearchScope searchScope(@NotNull Project project,
                                                         @NotNull FileCategory.Selection selection) {
        boolean libs = selection.includes(FileCategory.LIBRARIES);
        boolean projectFiles = selection.includesProject();
        GlobalSearchScope base;
        if (libs && projectFiles) base = GlobalSearchScope.allScope(project);
        else if (libs) base = ProjectScope.getLibrariesScope(project);
        else base = GlobalSearchScope.projectScope(project);
        return new CategoryScope(base, ProjectFileIndex.getInstance(project), selection);
    }

    /**
     * Narrows a base scope to the files whose {@link #categorize category} is selected.
     */
    private static final class CategoryScope extends DelegatingGlobalSearchScope {
        private final ProjectFileIndex index;
        private final FileCategory.Selection selection;

        CategoryScope(GlobalSearchScope base, ProjectFileIndex index, FileCategory.Selection selection) {
            super(base);
            this.index = index;
            this.selection = selection;
        }

        @Override
        public boolean contains(@NotNull VirtualFile file) {
            return super.contains(file) && selection.includes(categorize(index, file));
        }

        // Scopes are used as cache keys by the index; two scopes are equal only when they wrap the
        // same base scope and select the same categories. The matching hash lives in calcHashCode():
        // GlobalSearchScope.hashCode() is not overridable (platform API), caches the value and
        // delegates to calcHashCode(), so Sonar's "override hashCode too" rule does not apply.
        @SuppressWarnings("java:S1206")
        @Override
        public boolean equals(Object o) {
            return o instanceof CategoryScope other
                && super.equals(other)
                && selection.categories().equals(other.selection.categories());
        }

        @Override
        public int calcHashCode() {
            return 31 * super.calcHashCode() + selection.categories().hashCode();
        }
    }

    /**
     * Directories marked "Excluded" in any module, sorted by path. Only the roots are returned; their
     * contents are never read.
     */
    public static @NotNull List<VirtualFile> excludedRoots(@NotNull Project project) {
        List<VirtualFile> roots = new ArrayList<>();
        for (var module : ModuleManager.getInstance(project).getModules()) {
            for (var entry : ModuleRootManager.getInstance(module).getContentEntries()) {
                for (var folder : entry.getExcludeFolders()) {
                    VirtualFile f = folder.getFile();
                    if (f != null && !roots.contains(f)) roots.add(f);
                }
            }
        }
        roots.sort(Comparator.comparing(VirtualFile::getPath));
        return roots;
    }
}
