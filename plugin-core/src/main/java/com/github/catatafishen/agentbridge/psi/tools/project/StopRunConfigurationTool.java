package com.github.catatafishen.agentbridge.psi.tools.project;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.google.gson.JsonObject;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

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
            + "Matches the run tab display name (which is the configuration name for standard configs; "
            + "may differ if the tab was renamed or the config uses a custom presentable name). "
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
        var ref = new AtomicReference<String>();
        EdtUtil.invokeAndWait(() -> ref.set(stopNamed(name)));
        return ref.get();
    }

    private String stopNamed(String name) {
        List<RunContentDescriptor> descriptors = RunContentManager.getInstance(project).getAllDescriptors();
        RunContentDescriptor match = findByDisplayName(descriptors, name);
        if (match == null) {
            List<String> running = descriptors.stream()
                .filter(StopRunConfigurationTool::isRunning)
                .map(d -> d.getDisplayName() != null ? d.getDisplayName() : "(unnamed)")
                .toList();
            if (running.isEmpty()) {
                return "Error: No running processes found. '" + name + "' is not currently running.";
            }
            return "Error: '" + name + "' is not currently running. Currently running: "
                + String.join(", ", running);
        }
        var handler = match.getProcessHandler();
        if (handler == null) {
            return "Error: '" + name + "' has no process handler and cannot be stopped.";
        }
        handler.destroyProcess();
        return "Stopped run configuration: " + name;
    }

    /**
     * Matches a descriptor by its tab display name. This is the only stable, non-internal
     * correlator between a {@link RunContentDescriptor} and a run configuration name — the
     * precise accessor {@code RunContentDescriptor#getRunConfigurationName()} is marked
     * {@code @ApiStatus.Internal} and cannot be used by third-party plugins, and the same
     * restriction applies to {@code ExecutionManager#getRunningDescriptors}.
     */
    @Nullable
    private static RunContentDescriptor findByDisplayName(List<RunContentDescriptor> descriptors, String name) {
        for (RunContentDescriptor d : descriptors) {
            if (Objects.equals(name, d.getDisplayName()) && isRunning(d)) return d;
        }
        return null;
    }

    private static boolean isRunning(RunContentDescriptor d) {
        var handler = d.getProcessHandler();
        return handler != null && !handler.isProcessTerminated();
    }
}
