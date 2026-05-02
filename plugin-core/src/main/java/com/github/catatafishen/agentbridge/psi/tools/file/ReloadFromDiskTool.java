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
public final class ReloadFromDiskTool extends FileTool {

    private static final String PARAM_PATH = "path";
    private static final String PARAM_PATHS = "paths";

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
            Param.optional(PARAM_PATH, TYPE_STRING,
                "Single file or directory path to reload (absolute or project-relative). " +
                    "Omit both 'path' and 'paths' to reload the entire project root."),
            Param.optional(PARAM_PATHS, TYPE_ARRAY,
                "Multiple file/directory paths to reload in one batch — array of strings. " +
                    "Use this for syncing many files at once (e.g., after a bulk external rewrite). " +
                    "Combined with 'path' if both are present.")
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

        java.util.List<String> requestedPaths = collectRequestedPaths(args);
        if (requestedPaths.isEmpty()) {
            return reloadProjectRoot(basePath);
        }

        java.util.List<VirtualFile> resolved = new java.util.ArrayList<>();
        java.util.List<String> notFound = new java.util.ArrayList<>();
        java.util.List<String> reloadedNames = new java.util.ArrayList<>();
        for (String pathStr : requestedPaths) {
            resolveOne(pathStr, basePath, resolved, reloadedNames, notFound);
        }

        if (!resolved.isEmpty()) {
            VfsUtil.markDirtyAndRefresh(false, true, true, resolved.toArray(new VirtualFile[0]));
            commitDocuments();
        }

        return formatResult(resolved, reloadedNames, notFound);
    }

    private @NotNull String reloadProjectRoot(@NotNull String basePath) {
        VirtualFile root = LocalFileSystem.getInstance().findFileByPath(basePath);
        if (root == null) return "Project root not found";
        VfsUtil.markDirtyAndRefresh(false, true, true, root);
        commitDocuments();
        return "Reloaded project root from disk (" + basePath + ")";
    }

    private void resolveOne(@NotNull String pathStr,
                            @NotNull String basePath,
                            @NotNull java.util.List<VirtualFile> resolved,
                            @NotNull java.util.List<String> reloadedNames,
                            @NotNull java.util.List<String> notFound) {
        VirtualFile vf = resolveVirtualFile(pathStr);
        if (vf != null) {
            resolved.add(vf);
            reloadedNames.add(vf.getPath());
            return;
        }
        // Fall back to refreshing the parent directory so a newly-created file shows up.
        java.io.File f = new java.io.File(pathStr);
        if (!f.isAbsolute()) f = new java.io.File(basePath, pathStr);
        java.io.File parent = f.getParentFile();
        VirtualFile parentVf = (parent == null) ? null
            : LocalFileSystem.getInstance().refreshAndFindFileByPath(parent.getAbsolutePath());
        if (parentVf != null) {
            resolved.add(parentVf);
            reloadedNames.add(parentVf.getPath() + " (parent of " + pathStr + ")");
        } else {
            notFound.add(pathStr);
        }
    }

    private @NotNull String formatResult(@NotNull java.util.List<VirtualFile> resolved,
                                         @NotNull java.util.List<String> reloadedNames,
                                         @NotNull java.util.List<String> notFound) {
        StringBuilder sb = new StringBuilder();
        if (resolved.isEmpty()) {
            sb.append("No paths could be resolved.");
        } else if (resolved.size() == 1 && notFound.isEmpty()) {
            sb.append("Reloaded from disk: ").append(reloadedNames.getFirst());
        } else {
            sb.append("Reloaded ").append(resolved.size()).append(" path(s) from disk:");
            for (String n : reloadedNames) sb.append("\n  - ").append(n);
        }
        if (!notFound.isEmpty()) {
            sb.append("\nNot found: ").append(String.join(", ", notFound));
        }
        return sb.toString();
    }

    private @NotNull java.util.List<String> collectRequestedPaths(@NotNull JsonObject args) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (args.has(PARAM_PATHS) && args.get(PARAM_PATHS).isJsonArray()) {
            for (var el : args.getAsJsonArray(PARAM_PATHS)) {
                if (el.isJsonPrimitive()) {
                    String s = el.getAsString();
                    if (!s.isBlank()) out.add(s);
                }
            }
        }
        if (args.has(PARAM_PATH) && !args.get(PARAM_PATH).isJsonNull()) {
            String s = args.get(PARAM_PATH).getAsString();
            if (!s.isBlank()) out.add(s);
        }
        return out;
    }

    private void commitDocuments() {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait(() ->
            com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments());
    }
}
