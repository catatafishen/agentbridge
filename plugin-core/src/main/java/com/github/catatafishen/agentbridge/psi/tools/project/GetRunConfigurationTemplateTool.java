package com.github.catatafishen.agentbridge.psi.tools.project;

import com.github.catatafishen.agentbridge.psi.RunConfigurationService;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Returns the default XML template for a run configuration type, showing all available option names.
 */
public final class GetRunConfigurationTemplateTool extends ProjectTool {

    private final RunConfigurationService runConfigService;

    public GetRunConfigurationTemplateTool(Project project, RunConfigurationService runConfigService) {
        super(project);
        this.runConfigService = runConfigService;
    }

    @Override
    public @NotNull String id() {
        return "get_run_configuration_template";
    }

    @Override
    public @NotNull String displayName() {
        return "Get Run Config Template";
    }

    @Override
    public @NotNull String description() {
        return "Get a JSON schema describing all configurable options for a run configuration type. "
            + "Use list_run_configuration_types first to find valid type IDs. "
            + "The returned schema shows all available properties with their types, defaults, and descriptions. "
            + "Pass the schema as the 'config' parameter to create_run_configuration. "
            + "Example: get_run_configuration_template(type='Application') → schema → "
            + "create_run_configuration(name='My App', type='Application', config={...}).";
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
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required("type", TYPE_STRING, "Run configuration type ID (from list_run_configuration_types)"),
            Param.optional("factory_name", TYPE_STRING, "Optional factory name within the type (from list_run_configuration_types)")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        return runConfigService.getRunConfigTemplate(args);
    }
}
