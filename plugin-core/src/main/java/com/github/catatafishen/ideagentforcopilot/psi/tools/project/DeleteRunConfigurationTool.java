package com.github.catatafishen.ideagentforcopilot.psi.tools.project;

import com.github.catatafishen.ideagentforcopilot.psi.RunConfigurationService;
import com.github.catatafishen.ideagentforcopilot.ui.renderers.RunConfigCrudRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Deletes a run configuration by name.
 */
public final class DeleteRunConfigurationTool extends ProjectTool {

    private final RunConfigurationService runConfigService;

    public DeleteRunConfigurationTool(Project project, RunConfigurationService runConfigService) {
        super(project);
        this.runConfigService = runConfigService;
    }

    @Override
    public @NotNull String id() {
        return "delete_run_configuration";
    }

    @Override
    public @NotNull String displayName() {
        return "Delete Run Config";
    }

    @Override
    public @NotNull String description() {
        return "Delete a run configuration by name";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EXECUTE;
    }

    @Override
    public boolean isDestructive() {
        return true;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Delete run config: {name}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
            {"name", TYPE_STRING, "Exact name of the run configuration to delete"}
        }, "name");
    }

    @Override
    public @NotNull Object resultRenderer() {
        return RunConfigCrudRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        return runConfigService.deleteRunConfiguration(args);
    }
}
