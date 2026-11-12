package com.github.catatafishen.agentbridge.psi.tools.project;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.github.catatafishen.agentbridge.psi.tools.navigation.FileCategory;
import com.github.catatafishen.agentbridge.ui.renderers.SimpleStatusRenderer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ContentEntry;
import com.intellij.openapi.roots.ExcludeFolder;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.SourceFolder;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Marks a directory as source root, test root, resources, excluded, etc.
 */
@SuppressWarnings("java:S112")
public final class MarkDirectoryTool extends ProjectTool {

    public MarkDirectoryTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "mark_directory";
    }

    @Override
    public @NotNull String displayName() {
        return "Mark Directory";
    }

    @Override
    public @NotNull String description() {
        return "Mark a directory as sources, test_sources, resources, test_resources, generated_sources, "
            + "generated_test_sources or excluded, or 'unmark' it. The source types are the same category names "
            + "that the 'scope' parameter of search_text, list_project_files, find_file, search_symbols and "
            + "find_references uses. "
            + "'excluded' removes the directory from IDE indexing, so those tools no longer see its contents.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Mark {path} as {type}";
    }

    @Override
    public boolean isIdempotent() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        JsonObject s = schema(
            Param.required("path", TYPE_STRING, "Directory path (absolute or project-relative)"),
            Param.required("type", TYPE_STRING, "Directory type: one of " + String.join(", ", VALID_TYPES)
                + ". 'unmark' removes any marking.")
        );
        JsonArray values = new JsonArray();
        VALID_TYPES.forEach(values::add);
        s.getAsJsonObject("properties").getAsJsonObject("type").add("enum", values);
        return s;
    }

    /**
     * Markable categories (FileCategory ids except the derived 'ide' and 'other'), plus 'excluded' and 'unmark'.
     */
    static final List<String> VALID_TYPES = java.util.stream.Stream.concat(
        java.util.Arrays.stream(FileCategory.values())
            .filter(c -> c != FileCategory.IDE && c != FileCategory.OTHER && c != FileCategory.LIBRARIES)
            .map(FileCategory::id),
        java.util.stream.Stream.of(FileCategory.EXCLUDED_ID, "unmark")).toList();

    @Override
    public @NotNull Object resultRenderer() {
        return SimpleStatusRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String pathStr = args.get("path").getAsString();
        String type = args.get("type").getAsString();

        if (!VALID_TYPES.contains(type)) {
            return "Error: invalid type '" + type + "'. Must be one of: " + String.join(", ", VALID_TYPES);
        }

        String basePath = project.getBasePath();
        Path dirPath = Path.of(pathStr);
        if (!dirPath.isAbsolute() && basePath != null) {
            dirPath = Path.of(basePath).resolve(dirPath);
        }
        String absolutePath = dirPath.toAbsolutePath().toString();

        if (!Files.isDirectory(dirPath)) {
            Files.createDirectories(dirPath);
        }

        // refreshAndFindFileByPath must run on the EDT (it may acquire the write lock
        // internally for VFS refresh — calling it from a pooled thread while the EDT
        // holds a write-intent token causes a lock-ordering deadlock).
        CompletableFuture<String> future = new CompletableFuture<>();
        EdtUtil.invokeLater(() -> {
            try {
                VirtualFile vDir = LocalFileSystem.getInstance().refreshAndFindFileByPath(absolutePath);
                if (vDir == null) {
                    future.complete("Error: could not find directory in VFS: " + absolutePath);
                    return;
                }
                String result = WriteAction.compute(
                    () -> applyDirectoryMarking(absolutePath, vDir, type));
                future.complete(result);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        return future.get(10, TimeUnit.SECONDS);
    }

    private String applyDirectoryMarking(String absolutePath, VirtualFile vDir, String type) {
        Module[] modules = ModuleManager.getInstance(project).getModules();
        for (Module module : modules) {
            String result = tryMarkInModule(module, absolutePath, vDir, type);
            if (result != null) return result;
        }
        return "Error: directory '" + absolutePath + "' is not under any module's content root";
    }

    private @Nullable String tryMarkInModule(Module module, String absolutePath, VirtualFile vDir, String type) {
        ModifiableRootModel model = ModuleRootManager.getInstance(module).getModifiableModel();
        try {
            for (ContentEntry entry : model.getContentEntries()) {
                VirtualFile contentRoot = entry.getFile();
                if (contentRoot == null || !absolutePath.startsWith(contentRoot.getPath())) continue;

                if ("unmark".equals(type)) {
                    return unmarkDirectory(entry, model, module, vDir.getUrl(), absolutePath);
                }
                if ("excluded".equals(type)) {
                    entry.addExcludeFolder(vDir);
                    model.commit();
                    return "Marked '" + absolutePath + "' as excluded in module '" + module.getName() + "'";
                }
                return addSourceRoot(entry, model, module, vDir, absolutePath, type);
            }
        } finally {
            if (!model.isDisposed() && model.isWritable()) {
                model.dispose();
            }
        }
        return null;
    }

    private static String addSourceRoot(ContentEntry entry, ModifiableRootModel model,
                                        Module module, VirtualFile vDir, String absolutePath, String type) {
        PlatformApiCompat.addSourceFolder(entry, vDir, type);
        model.commit();
        return "Marked '" + absolutePath + "' as " + type + " in module '" + module.getName() + "'";
    }

    private static String unmarkDirectory(ContentEntry entry, ModifiableRootModel model,
                                          Module module, String url, String absolutePath) {
        boolean found = false;
        for (SourceFolder sf : entry.getSourceFolders()) {
            if (url.equals(sf.getUrl())) {
                entry.removeSourceFolder(sf);
                found = true;
            }
        }
        for (ExcludeFolder ef : entry.getExcludeFolders()) {
            if (url.equals(ef.getUrl())) {
                entry.removeExcludeFolder(ef);
                found = true;
            }
        }
        if (found) {
            model.commit();
            return "Unmarked '" + absolutePath + "' in module '" + module.getName() + "'";
        }
        model.dispose();
        return "Directory '" + absolutePath + "' was not marked in module '" + module.getName() + "'";
    }
}
