package com.github.catatafishen.agentbridge.psi.tools.project;

import com.github.catatafishen.agentbridge.psi.RunConfigurationService;
import com.github.catatafishen.agentbridge.ui.renderers.RunConfigCrudRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

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
    public boolean requiresInteractiveEdt() {
        return true;
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
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Run: {name}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required("name", TYPE_STRING, "Exact name of the run configuration"),
            Param.optional("wait_seconds", TYPE_INTEGER, "(Optional) Wait up to this many seconds for the run to complete (default: fire-and-forget). Use read_run_output after to get full output.")
        );
    }

    @Override
    public @NotNull Object resultRenderer() {
        return RunConfigCrudRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        if (args.has("wait_seconds")) {
            return runConfigService.runConfigurationAndWait(args);
        }
        return runConfigService.runConfiguration(args);
    }
}
