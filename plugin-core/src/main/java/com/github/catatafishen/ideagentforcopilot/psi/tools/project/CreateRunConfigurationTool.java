package com.github.catatafishen.ideagentforcopilot.psi.tools.project;

import com.github.catatafishen.ideagentforcopilot.psi.RunConfigurationService;
import com.github.catatafishen.ideagentforcopilot.ui.renderers.RunConfigCrudRenderer;
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
        return "Create a new run configuration of any type supported by the IDE (e.g., 'application', 'junit', 'gradle', 'maven', 'npm', 'python')";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        JsonObject s = schema(new Object[][]{
            {"name", TYPE_STRING, "Name for the new run configuration"},
            {"type", TYPE_STRING, "Configuration type: any IDE-supported type name (e.g., 'application', 'junit', 'gradle', 'maven', 'npm', 'python'). If unknown, an error will list all available types."},
            {"jvm_args", TYPE_STRING, "Optional: JVM arguments (e.g., '-Xmx512m')"},
            {"program_args", TYPE_STRING, "Optional: program arguments"},
            {"working_dir", TYPE_STRING, "Optional: working directory path"},
            {"main_class", TYPE_STRING, "Optional: main class (for Application configs)"},
            {"test_class", TYPE_STRING, "Optional: test class (for JUnit configs)"},
            {"module_name", TYPE_STRING, "Optional: IntelliJ module name (from project structure)"},
            {"tasks", TYPE_STRING, "Optional: Gradle task names, space-separated (e.g., ':plugin-core:buildPlugin')"},
            {"script_parameters", TYPE_STRING, "Optional: Gradle script parameters (e.g., '--info')"},
            {"shared", TYPE_BOOLEAN, "Store as shared project file (default: true). If false, stored in workspace only"}
        }, "name", "type");
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
