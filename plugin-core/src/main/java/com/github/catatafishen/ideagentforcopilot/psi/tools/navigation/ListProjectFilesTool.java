package com.github.catatafishen.ideagentforcopilot.psi.tools.navigation;

import com.github.catatafishen.ideagentforcopilot.psi.CodeNavigationTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Lists files in a project directory, optionally filtered by glob pattern.
 */
@SuppressWarnings("java:S112")
public final class ListProjectFilesTool extends NavigationTool {

    public ListProjectFilesTool(Project project, CodeNavigationTools navTools) {
        super(project, navTools);
    }

    @Override
    public @NotNull String id() {
        return "list_project_files";
    }

    @Override
    public @NotNull String displayName() {
        return "List Project Files";
    }

    @Override
    public @NotNull String description() {
        return "List files in a project directory, optionally filtered by glob pattern";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"directory", TYPE_STRING, "Optional subdirectory to list (relative to project root)", ""},
            {"pattern", TYPE_STRING, "Optional glob pattern (e.g., '*.java', 'src/**/*.kt')", ""},
            {"sort", TYPE_STRING, "Sort order: 'name' (default, alphabetical), 'size' (largest first), 'modified' (most recently modified first)", ""},
            {"min_size", TYPE_INTEGER, "Only include files at least this many bytes", ""},
            {"max_size", TYPE_INTEGER, "Only include files at most this many bytes", ""},
            {"modified_after", TYPE_STRING, "Only include files modified after this date (yyyy-MM-dd, UTC)", ""},
            {"modified_before", TYPE_STRING, "Only include files modified before this date (yyyy-MM-dd, UTC)", ""}
        });
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return navTools.listProjectFiles(args);
    }
}
