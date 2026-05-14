package com.github.catatafishen.agentbridge.psi.tools.project;

import com.github.catatafishen.agentbridge.psi.RunConfigurationService;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Lists all run configuration types registered in the IDE (including plugin-provided ones).
 */
public final class ListRunConfigurationTypesTool extends ProjectTool {

    private final RunConfigurationService runConfigService;

    public ListRunConfigurationTypesTool(Project project, RunConfigurationService runConfigService) {
        super(project);
        this.runConfigService = runConfigService;
    }

    @Override
    public @NotNull String id() {
        return "list_run_configuration_types";
    }

    @Override
    public @NotNull String displayName() {
        return "List Run Config Types";
    }

    @Override
    public @NotNull String description() {
        return "List all run configuration types registered in the IDE, including types from installed plugins "
                + "(e.g. Node.js, Python, Flask, Gradle, JUnit, Micronaut, etc.). "
                + "Returns each type's ID, display name, and available factory names. "
                + "Use this before create_run_configuration to discover valid type IDs and factory names. "
                + "Then use get_run_configuration_template to see the options available for a specific type.";
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
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        return runConfigService.listRunConfigurationTypes();
    }
}
