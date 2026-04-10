package com.github.catatafishen.agentbridge.psi.tools.project;

import com.github.catatafishen.agentbridge.psi.RunConfigurationService;
import com.github.catatafishen.agentbridge.ui.renderers.RunConfigCrudRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Edits an existing run configuration's arguments, environment, or working directory.
 */
public final class EditRunConfigurationTool extends ProjectTool {

    private final RunConfigurationService runConfigService;

    public EditRunConfigurationTool(Project project, RunConfigurationService runConfigService) {
        super(project);
        this.runConfigService = runConfigService;
    }

    @Override
    public @NotNull String id() {
        return "edit_run_configuration";
    }

    @Override
    public @NotNull String displayName() {
        return "Edit Run Config";
    }

    @Override
    public @NotNull String description() {
        return "Edit an existing run configuration's arguments, environment, or working directory";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        JsonObject s = schema(
            Param.required("name", TYPE_STRING, "Name of the run configuration to edit"),
            Param.optional("jvm_args", TYPE_STRING, "Optional: new JVM arguments"),
            Param.optional("program_args", TYPE_STRING, "Optional: new program arguments"),
            Param.optional("working_dir", TYPE_STRING, "Optional: new working directory"),
            Param.optional("tasks", TYPE_STRING, "Optional: Gradle task names, space-separated (e.g., ':plugin-core:buildPlugin')"),
            Param.optional("script_parameters", TYPE_STRING, "Optional: Gradle script parameters (e.g., '--info')"),
            Param.optional("script_path", TYPE_STRING, "Optional: path to the script file (for Shell Script configs)"),
            Param.optional("shared", TYPE_BOOLEAN, "Optional: toggle shared (project file) vs workspace-local storage")
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
        return runConfigService.editRunConfiguration(args);
    }
}
