package com.github.catatafishen.ideagentforcopilot.psi.tools.project;

import com.github.catatafishen.ideagentforcopilot.psi.RunConfigurationService;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.github.catatafishen.ideagentforcopilot.ui.renderers.RunConfigCrudRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Executes an existing run configuration by name.
 */
public final class RunConfigurationTool extends ProjectTool {

    private final RunConfigurationService runConfigService;

    public RunConfigurationTool(Project project, RunConfigurationService runConfigService) {
        super(project);
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
    public @NotNull String kind() {
        return "edit";
    }
@Override
    public @NotNull String permissionTemplate() {
        return "Run: {name}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
            {"name", TYPE_STRING, "Exact name of the run configuration"}
        }, "name");
    }

    @Override
    public @NotNull Object resultRenderer() {
        return RunConfigCrudRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        return runConfigService.runConfiguration(args);
    }
}
