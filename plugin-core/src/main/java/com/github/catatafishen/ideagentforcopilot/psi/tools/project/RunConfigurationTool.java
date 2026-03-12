package com.github.catatafishen.ideagentforcopilot.psi.tools.project;

import com.github.catatafishen.ideagentforcopilot.psi.ProjectTools;
import com.github.catatafishen.ideagentforcopilot.psi.RunConfigurationService;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Executes an existing run configuration by name.
 */
public final class RunConfigurationTool extends ProjectTool {

    private final RunConfigurationService runConfigService;

    public RunConfigurationTool(Project project, ProjectTools projectTools,
                                RunConfigurationService runConfigService) {
        super(project, projectTools);
        this.runConfigService = runConfigService;
    }

    @Override
    public @NotNull String id() {
        return "run_configuration";
    }

    @Override
    public @NotNull String displayName() {
        return "Run Configuration";
    }

    @Override
    public @NotNull String description() {
        return "Execute an existing run configuration by name";
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Run: {name}";
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"name", TYPE_STRING, "Exact name of the run configuration"}
        }, "name");
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return runConfigService.runConfiguration(args);
    }
}
