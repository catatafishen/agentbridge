package com.github.catatafishen.ideagentforcopilot.psi.tools.project;

import com.github.catatafishen.ideagentforcopilot.psi.EdtUtil;
import com.github.catatafishen.ideagentforcopilot.psi.PlatformApiCompat;
import com.github.catatafishen.ideagentforcopilot.ui.renderers.SimpleStatusRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ContentEntry;
import com.intellij.openapi.roots.ExcludeFolder;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.SourceFolder;
import com.intellij.openapi.util.Computable;
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
        return "Mark a directory as source root, test root, resources, excluded, etc.";
    }

    

    @Override
    public @NotNull String kind() {
        return "edit";
    }
@Override
    public @NotNull String permissionTemplate() {
        return "Mark {path} as {type}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
            {"path", TYPE_STRING, "Directory path (absolute or project-relative)"},
            {"type", TYPE_STRING, "Directory type: 'sources', 'test_sources', 'resources', 'test_resources', 'generated_sources', 'excluded', or 'unmark' to remove marking"}
        }, "path", "type");
    }

    @Override
    public @NotNull Object resultRenderer() {
        return SimpleStatusRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String pathStr = args.get("path").getAsString();
        String type = args.get("type").getAsString();

        List<String> validTypes = List.of("sources", "test_sources", "resources",
            "test_resources", "generated_sources", "excluded", "unmark");
        if (!validTypes.contains(type)) {
            return "Error: invalid type '" + type + "'. Must be one of: " + String.join(", ", validTypes);
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

        VirtualFile vDir = LocalFileSystem.getInstance().refreshAndFindFileByPath(absolutePath);
        if (vDir == null) {
            return "Error: could not find directory in VFS: " + absolutePath;
        }

        CompletableFuture<String> future = new CompletableFuture<>();
        EdtUtil.invokeLater(() -> {
            try {
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
