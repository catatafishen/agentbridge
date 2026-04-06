package com.github.catatafishen.agentbridge.psi.tools.project;

import com.github.catatafishen.agentbridge.psi.RunConfigurationService;
import com.github.catatafishen.agentbridge.ui.renderers.RunConfigRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

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
    public @NotNull Kind kind() {
        return Kind.READ;
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
