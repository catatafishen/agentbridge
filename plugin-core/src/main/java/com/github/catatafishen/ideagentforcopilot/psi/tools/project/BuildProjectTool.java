package com.github.catatafishen.ideagentforcopilot.psi.tools.project;

import com.github.catatafishen.ideagentforcopilot.psi.ProjectTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Triggers incremental compilation of the project or a specific module.
 */
@SuppressWarnings("java:S112")
public final class BuildProjectTool extends ProjectTool {

    public BuildProjectTool(Project project, ProjectTools projectTools) {
        super(project, projectTools);
    }

    @Override
    public @NotNull String id() {
        return "build_project";
    }

    @Override
    public @NotNull String displayName() {
        return "Build Project";
    }

    @Override
    public @NotNull String description() {
        return "Trigger incremental compilation of the project or a specific module";
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Build project";
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"module", TYPE_STRING, "Optional: build only a specific module (e.g., 'plugin-core')"}
        });
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return projectTools.buildProject(args);
    }
}
