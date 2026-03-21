package com.github.catatafishen.ideagentforcopilot.psi.tools.project;

import com.github.catatafishen.ideagentforcopilot.psi.RunConfigurationService;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.github.catatafishen.ideagentforcopilot.ui.renderers.RunConfigRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Lists all available run configurations in the project.
 */
public final class ListRunConfigurationsTool extends ProjectTool {

    private final RunConfigurationService runConfigService;

    public ListRunConfigurationsTool(Project project, RunConfigurationService runConfigService) {
        super(project);
        this.runConfigService = runConfigService;
    }

    @Override
    public @NotNull String id() {
        return "list_run_configurations";
    }

    @Override
    public @NotNull String displayName() {
        return "List Run Configs";
    }

    @Override
    public @NotNull String description() {
        return "List all available run configurations in the project";
    }

    

    @Override
    public @NotNull String kind() {
        return "read";
    }
@Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull Object resultRenderer() {
        return RunConfigRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        return runConfigService.listRunConfigurations();
    }
}
