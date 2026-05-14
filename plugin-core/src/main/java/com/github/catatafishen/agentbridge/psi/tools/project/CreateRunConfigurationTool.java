package com.github.catatafishen.agentbridge.psi.tools.project;

import com.github.catatafishen.agentbridge.psi.RunConfigurationService;
import com.github.catatafishen.agentbridge.ui.renderers.RunConfigCrudRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Creates a new run configuration of any type supported by the IDE.
 */
public final class CreateRunConfigurationTool extends ProjectTool {

    private final RunConfigurationService runConfigService;

    public CreateRunConfigurationTool(Project project, RunConfigurationService runConfigService) {
        super(project);
        this.runConfigService = runConfigService;
    }

    @Override
    public @NotNull String id() {
        return "create_run_configuration";
    }

    @Override
    public @NotNull String displayName() {
        return "Create Run Config";
    }

    @Override
    public @NotNull String description() {
        return "Create a new run configuration of any type supported by the IDE. "
            + "Workflow: (1) call list_run_configuration_types to find the type ID, "
            + "(2) call get_run_configuration_template with that type ID to get a JSON schema "
            + "showing all configurable options and their defaults, "
            + "(3) call this tool with the type ID and a 'config' JSON object matching the schema. "
            + "The config object is validated against the schema — an error is returned immediately "
            + "if any key is unknown or has the wrong type.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required("name", TYPE_STRING, "Name for the new run configuration"),
            Param.required("type", TYPE_STRING, "Configuration type ID from list_run_configuration_types (e.g. 'Application', 'GradleRunConfiguration', 'NodeJSConfigurationType')"),
            Param.optional("factory_name", TYPE_STRING, "Factory name within the type (from list_run_configuration_types). Only needed when a type has multiple factories."),
            Param.optional("config", TYPE_OBJECT, "JSON object matching the schema from get_run_configuration_template. Each key maps to a configurable option; validated before saving."),
            Param.optional("working_dir", TYPE_STRING, "Optional: working directory path"),
            Param.optional("shared", TYPE_BOOLEAN, "Store as shared project file (default: true). If false, stored in workspace only")
        );
    }

    @Override
    public @NotNull Object resultRenderer() {
        return RunConfigCrudRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        return runConfigService.createRunConfiguration(args);
    }
}
