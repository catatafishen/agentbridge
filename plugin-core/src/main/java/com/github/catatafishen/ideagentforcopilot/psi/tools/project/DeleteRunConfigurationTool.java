package com.github.catatafishen.ideagentforcopilot.psi.tools.project;

import com.github.catatafishen.ideagentforcopilot.psi.ProjectTools;
import com.github.catatafishen.ideagentforcopilot.psi.RunConfigurationService;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Deletes a run configuration by name.
 */
public final class DeleteRunConfigurationTool extends ProjectTool {

    private final RunConfigurationService runConfigService;

    public DeleteRunConfigurationTool(Project project, ProjectTools projectTools,
                                      RunConfigurationService runConfigService) {
        super(project, projectTools);
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
    public boolean isDestructive() {
        return true;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Delete run config: {name}";
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"name", TYPE_STRING, "Exact name of the run configuration to delete"}
        }, "name");
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return runConfigService.deleteRunConfiguration(args);
    }
}
