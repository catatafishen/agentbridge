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
            + "if any key is unknown or has the wrong type. "
            + "Shell Script configs (type 'ShConfigurationType' or 'Shell Script') support "
            + "script_path (file) or script_text (inline), plus interpreter_path, script_options, "
            + "and execute_in_terminal — no template needed for Shell Script.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        JsonObject s = schema(
            Param.required("name", TYPE_STRING, "Name for the new run configuration"),
            Param.required("type", TYPE_STRING, "Configuration type ID from list_run_configuration_types (e.g. 'Application', 'GradleRunConfiguration', 'NodeJSConfigurationType')"),
            Param.optional("factory_name", TYPE_STRING, "Factory name within the type (from list_run_configuration_types). Only needed when a type has multiple factories."),
            Param.optional("config", TYPE_OBJECT, "JSON object matching the schema from get_run_configuration_template. Each key maps to a configurable option; validated before saving."),
            Param.optional("working_dir", TYPE_STRING, "Optional: working directory path"),
            Param.optional("shared", TYPE_BOOLEAN, "Store as shared project file (default: true). If false, stored in workspace only"),
            Param.optional("jvm_args", TYPE_STRING, "Optional: JVM arguments (e.g. '-Xmx512m'). Prefer setting via 'config' when using get_run_configuration_template."),
            Param.optional("program_args", TYPE_STRING, "Optional: program arguments. Prefer setting via 'config' when using get_run_configuration_template."),
            Param.optional("main_class", TYPE_STRING, "Optional: fully-qualified main class name for Application/JUnit types. Prefer 'config' when using the template workflow."),
            Param.optional("test_class", TYPE_STRING, "Optional: fully-qualified test class name for JUnit types. Prefer 'config' when using the template workflow."),
            Param.optional("tasks", TYPE_STRING, "Optional: Gradle task names, space-separated (e.g. ':app:test'). Prefer 'config' when using the template workflow."),
            Param.optional("script_path", TYPE_STRING, "Optional: path to the script file for Shell Script configs. Prefer 'config' when using the template workflow."),
            Param.optional("script_parameters", TYPE_STRING, "Optional: Gradle script parameters (e.g. '--info'). Prefer 'config' when using the template workflow."),
            Param.optional("script_text", TYPE_STRING, "Optional: inline script body for Shell Script configs (mutually exclusive with script_path)"),
            Param.optional("script_options", TYPE_STRING, "Optional: arguments passed to the Shell Script"),
            Param.optional("interpreter_path", TYPE_STRING, "Optional: path to the interpreter for Shell Script configs (default: /bin/bash)"),
            Param.optional("execute_in_terminal", TYPE_BOOLEAN, "Optional: run Shell Script in integrated terminal (default: true)"),
            Param.optional("module", TYPE_STRING, "Optional: module name for classpath resolution. Prefer 'config' when using the template workflow.")
        );
        // config accepts any key/value type — schema is determined at runtime by get_run_configuration_template
        s.getAsJsonObject("properties").getAsJsonObject("config")
            .add("additionalProperties", new JsonObject());
        addDictProperty(s, "env", "Environment variables as key-value pairs");
        return s;
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
