package com.github.catatafishen.ideagentforcopilot.psi.tools.navigation;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Returns a tree representation of a project directory (like the tree utility).
 * Skips excluded directories (e.g. node_modules, build output).
 */
public final class ListDirectoryTreeTool extends NavigationTool {

    private static final String PARAM_PATH = "path";
    private static final String PARAM_MAX_DEPTH = "max_depth";
    private static final int DEFAULT_MAX_DEPTH = 3;

    public ListDirectoryTreeTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "list_directory_tree";
    }

    @Override
    public @NotNull String displayName() {
        return "List Directory Tree";
    }

    @Override
    public @NotNull String description() {
        return "Returns a tree-formatted view of a directory's contents (like the 'tree' utility). "
            + "Excluded directories (e.g. node_modules, build output) are skipped. "
            + "Prefer this over shell 'ls' or 'find' for directory exploration.";
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
        return schema(new Object[][]{
            {PARAM_PATH, TYPE_STRING, "Directory path (absolute or project-relative). Defaults to project root if omitted."},
            {PARAM_MAX_DEPTH, TYPE_INTEGER, "Maximum depth to recurse (default: 3). Use 0 for unlimited."},
        });
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        int maxDepth = args.has(PARAM_MAX_DEPTH) ? args.get(PARAM_MAX_DEPTH).getAsInt() : DEFAULT_MAX_DEPTH;
        if (maxDepth == 0) maxDepth = Integer.MAX_VALUE;

        final int resolvedMaxDepth = maxDepth;
        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            VirtualFile root = resolveRoot(args);
            if (root == null) return "Error: directory not found";
            if (!root.isDirectory()) return "Error: path is not a directory: " + root.getPath();

            ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
            StringBuilder sb = new StringBuilder(root.getName()).append('\n');
            appendChildren(sb, root, "", resolvedMaxDepth, 1, fileIndex);
            return sb.toString();
        });
    }

    @Nullable
    private VirtualFile resolveRoot(@NotNull JsonObject args) {
        if (args.has(PARAM_PATH)) {
            return resolveVirtualFile(args.get(PARAM_PATH).getAsString());
        }
        String basePath = project.getBasePath();
        if (basePath == null) return null;
        return resolveVirtualFile(basePath);
    }

    private static void appendChildren(@NotNull StringBuilder sb, @NotNull VirtualFile dir,
                                       @NotNull String prefix, int maxDepth, int depth,
                                       @NotNull ProjectFileIndex fileIndex) {
        if (depth > maxDepth) return;
        VirtualFile[] children = dir.getChildren();
        for (int i = 0; i < children.length; i++) {
            VirtualFile child = children[i];
            boolean last = (i == children.length - 1);
            String connector = last ? "`-- " : "|-- ";
            sb.append(prefix).append(connector).append(child.getName());
            if (child.isDirectory()) {
                if (fileIndex.isExcluded(child)) {
                    sb.append(" [excluded]\n");
                } else {
                    sb.append('\n');
                    String childPrefix = prefix + (last ? "    " : "|   ");
                    appendChildren(sb, child, childPrefix, maxDepth, depth + 1, fileIndex);
                }
            } else {
                sb.append('\n');
            }
        }
    }
}
