package com.github.catatafishen.ideagentforcopilot.psi.tools.project;

import com.github.catatafishen.ideagentforcopilot.psi.RunConfigurationService;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.github.catatafishen.ideagentforcopilot.ui.renderers.RunConfigCrudRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
    public @NotNull String kind() {
        return "edit";
    }
@Override
    public @NotNull JsonObject inputSchema() {
        JsonObject s = schema(new Object[][]{
            {"name", TYPE_STRING, "Name of the run configuration to edit"},
            {"jvm_args", TYPE_STRING, "Optional: new JVM arguments"},
            {"program_args", TYPE_STRING, "Optional: new program arguments"},
            {"working_dir", TYPE_STRING, "Optional: new working directory"},
            {"tasks", TYPE_STRING, "Optional: Gradle task names, space-separated (e.g., ':plugin-core:buildPlugin')"},
            {"script_parameters", TYPE_STRING, "Optional: Gradle script parameters (e.g., '--info')"},
            {"shared", TYPE_BOOLEAN, "Optional: toggle shared (project file) vs workspace-local storage"}
        }, "name");
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
