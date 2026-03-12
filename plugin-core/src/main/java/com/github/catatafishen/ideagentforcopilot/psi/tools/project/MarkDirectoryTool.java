package com.github.catatafishen.ideagentforcopilot.psi.tools.project;

import com.github.catatafishen.ideagentforcopilot.psi.ProjectTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Marks a directory as source root, test root, resources, excluded, etc.
 */
@SuppressWarnings("java:S112")
public final class MarkDirectoryTool extends ProjectTool {

    public MarkDirectoryTool(Project project, ProjectTools projectTools) {
        super(project, projectTools);
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
    public @NotNull String permissionTemplate() {
        return "Mark {path} as {type}";
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"path", TYPE_STRING, "Directory path (absolute or project-relative)"},
            {"type", TYPE_STRING, "Directory type: 'sources', 'test_sources', 'resources', 'test_resources', 'generated_sources', 'excluded', or 'unmark' to remove marking"}
        }, "path", "type");
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return projectTools.markDirectory(args);
    }
}
