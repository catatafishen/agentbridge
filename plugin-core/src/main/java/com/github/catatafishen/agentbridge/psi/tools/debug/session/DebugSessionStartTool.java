package com.github.catatafishen.agentbridge.psi.tools.debug.session;

import com.github.catatafishen.agentbridge.psi.tools.debug.DebugTool;
import com.google.gson.JsonObject;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.stream.Collectors;

public final class DebugSessionStartTool extends DebugTool {

    public DebugSessionStartTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "debug_session_start";
    }

    @Override
    public @NotNull String displayName() {
        return "Start Debug Session";
    }

    @Override
    public @NotNull String description() {
        return """
            Start a debug session for an existing run configuration by name. Equivalent to \
            clicking the Debug button in the IDE toolbar.

            Use list_run_configurations to find available configuration names.

            Returns immediately after launching — use debug_session_list to check the session \
            status, or add a breakpoint with breakpoint_manage first so the session pauses \
            automatically.""";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.WRITE;
    }

    @Override
    public boolean requiresInteractiveEdt() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required("name", TYPE_STRING, "Exact name of the run configuration to launch in debug mode")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String name = args.get("name").getAsString();
        RunManager runManager = RunManager.getInstance(project);
        RunnerAndConfigurationSettings settings = runManager.findConfigurationByName(name);
        if (settings == null) {
            String available = runManager.getAllSettings().stream()
                .map(RunnerAndConfigurationSettings::getName)
                .sorted()
                .collect(Collectors.joining(", "));
            return "Error: Run configuration '" + name + "' not found."
                + (available.isEmpty()
                    ? " No run configurations exist in this project."
                    : " Available: " + available);
        }

        var executor = DefaultDebugExecutor.getDebugExecutorInstance();
        var envBuilder = ExecutionEnvironmentBuilder.createOrNull(executor, settings);
        if (envBuilder == null) {
            return "Error: Run configuration '" + name + "' [" + settings.getType().getDisplayName()
                + "] does not support debug mode.";
        }
        ExecutionEnvironment env = envBuilder.build();
        ApplicationManager.getApplication().invokeLater(
            () -> ExecutionManager.getInstance(project).restartRunProfile(env));
        return "Started debug session: " + name
            + " [" + settings.getType().getDisplayName() + "]\n"
            + "The session is launching — use debug_session_list to check status.";
    }
}
