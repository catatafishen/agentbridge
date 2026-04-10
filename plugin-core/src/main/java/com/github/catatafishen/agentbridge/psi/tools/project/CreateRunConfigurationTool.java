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
        return "Create a new run configuration of any type supported by the IDE (e.g., 'application', 'junit', 'gradle', 'maven', 'npm', 'python'). "
            + "If unknown, an error will list all available types. "
            + "For config types with no public Java API (e.g. Shell Script), pass 'raw_xml' with the full XML content instead — "
            + "it is written directly to .idea/runConfigurations/<name>.xml.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        JsonObject s = schema(
            Param.required("name", TYPE_STRING, "Name for the new run configuration"),
            Param.optional("type", TYPE_STRING, "Configuration type (e.g., 'application', 'junit', 'gradle', 'Shell Script'). Required unless 'raw_xml' is provided. If unknown, an error will list all available types."),
            Param.optional("jvm_args", TYPE_STRING, "Optional: JVM arguments (e.g., '-Xmx512m')"),
            Param.optional("program_args", TYPE_STRING, "Optional: program arguments"),
            Param.optional("working_dir", TYPE_STRING, "Optional: working directory path"),
            Param.optional("main_class", TYPE_STRING, "Optional: main class (for Application configs)"),
            Param.optional("test_class", TYPE_STRING, "Optional: test class (for JUnit configs)"),
            Param.optional("module_name", TYPE_STRING, "Optional: IntelliJ module name (from project structure)"),
            Param.optional("tasks", TYPE_STRING, "Optional: Gradle task names, space-separated (e.g., ':plugin-core:buildPlugin')"),
            Param.optional("script_parameters", TYPE_STRING, "Optional: Gradle script parameters (e.g., '--info')"),
            Param.optional("script_path", TYPE_STRING, "Optional: path to the script file (for Shell Script configs)"),
            Param.optional("raw_xml", TYPE_STRING, "Optional: full XML content to write to .idea/runConfigurations/<name>.xml. Use for Shell Script or any config type whose Java API is inaccessible. When provided, 'type' is not required."),
            Param.optional("shared", TYPE_BOOLEAN, "Store as shared project file (default: true). If false, stored in workspace only")
        );
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
