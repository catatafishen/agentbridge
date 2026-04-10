package com.github.catatafishen.agentbridge.psi.tools.file;

import com.github.catatafishen.agentbridge.ui.renderers.SimpleStatusRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/**
 * Forces IntelliJ to refresh a file or directory from disk,
 * picking up changes made by external tools.
 */
@SuppressWarnings("java:S112")
public final class ReloadFromDiskTool extends FileTool {

    public ReloadFromDiskTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "reload_from_disk";
    }

    @Override
    public @NotNull String displayName() {
        return "Reload from Disk";
    }

    @Override
    public @NotNull String description() {
        return "Force IntelliJ to refresh a file or directory from disk, picking up changes made by external tools";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.READ;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.optional("path", TYPE_STRING, "File or directory path to reload (absolute or project-relative). Omit to reload the entire project root.")
        );
    }

    @Override
    public @NotNull Object resultRenderer() {
        return SimpleStatusRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        String basePath = project.getBasePath();
        if (basePath == null) return "No project base path";

        if (!args.has("path") || args.get("path").isJsonNull()) {
            VirtualFile root = LocalFileSystem.getInstance().findFileByPath(basePath);
            if (root == null) return "Project root not found";
            VfsUtil.markDirtyAndRefresh(false, true, true, root);
            return "Reloaded project root from disk (" + basePath + ")";
        }

        String pathStr = args.get("path").getAsString();
        VirtualFile vf = resolveVirtualFile(pathStr);
        if (vf == null) {
            java.io.File f = new java.io.File(pathStr);
            if (!f.isAbsolute()) f = new java.io.File(basePath, pathStr);
            java.io.File parent = f.getParentFile();
            if (parent != null) {
                VirtualFile parentVf = LocalFileSystem.getInstance().refreshAndFindFileByPath(parent.getAbsolutePath());
                if (parentVf != null) return "Reloaded parent directory: " + parent.getAbsolutePath();
            }
            return "File not found: " + pathStr;
        }

        VfsUtil.markDirtyAndRefresh(false, vf.isDirectory(), true, vf);
        return "Reloaded from disk: " + vf.getPath();
    }
}
