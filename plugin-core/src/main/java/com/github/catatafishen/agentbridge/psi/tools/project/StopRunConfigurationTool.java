package com.github.catatafishen.agentbridge.psi.tools.project;

import com.google.gson.JsonObject;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Stops a currently running run configuration by name.
 */
public final class StopRunConfigurationTool extends ProjectTool {

    public StopRunConfigurationTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "stop_run_configuration";
    }

    @Override
    public @NotNull String displayName() {
        return "Stop Run Configuration";
    }

    @Override
    public @NotNull String description() {
        return "Stop a currently running run configuration by name. "
            + "Matches the configuration name first; falls back to the tab display name. "
            + "Returns an error listing currently running processes if the target is not found. "
            + "Use list_run_configurations to find config names, or run_configuration to restart.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EXECUTE;
    }

    @Override
    public boolean isDestructive() {
        return false;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Stop: {name}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required("name", TYPE_STRING, "Name of the run configuration to stop")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String name = args.get("name").getAsString();
        List<RunContentDescriptor> descriptors = RunContentManager.getInstance(project).getAllDescriptors();

        RunContentDescriptor match = findRunning(descriptors, name);
        if (match == null) {
            List<String> running = descriptors.stream()
                .filter(d -> d.getProcessHandler() != null && !d.getProcessHandler().isProcessTerminated())
                .map(RunContentDescriptor::getDisplayName)
                .toList();
            if (running.isEmpty()) {
                return "Error: No running processes found. '" + name + "' is not currently running.";
            }
            return "Error: '" + name + "' is not currently running. Currently running: " + String.join(", ", running);
        }

        match.getProcessHandler().destroyProcess();
        return "Stopped run configuration: " + name;
    }

    @Nullable
    private static RunContentDescriptor findRunning(List<RunContentDescriptor> descriptors, String name) {
        // Prefer matching against the stored run-configuration name (precise)
        for (RunContentDescriptor d : descriptors) {
            if (name.equals(d.getRunConfigurationName()) && isRunning(d)) return d;
        }
        // Fall back to the tab display name
        for (RunContentDescriptor d : descriptors) {
            if (name.equals(d.getDisplayName()) && isRunning(d)) return d;
        }
        return null;
    }

    private static boolean isRunning(RunContentDescriptor d) {
        var handler = d.getProcessHandler();
        return handler != null && !handler.isProcessTerminated();
    }
}
