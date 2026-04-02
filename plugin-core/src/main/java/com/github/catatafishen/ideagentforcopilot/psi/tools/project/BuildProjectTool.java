package com.github.catatafishen.ideagentforcopilot.psi.tools.project;

import com.github.catatafishen.ideagentforcopilot.ui.renderers.BuildResultRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Triggers incremental compilation of the project or a specific module.
 */
@SuppressWarnings("java:S112")
public final class BuildProjectTool extends ProjectTool {

    private static final String JSON_MODULE = "module";

    private final AtomicBoolean buildInProgress = new AtomicBoolean(false);

    public BuildProjectTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "build_project";
    }

    @Override
    public @NotNull String displayName() {
        return "Build Project";
    }

    @Override
    public @NotNull String description() {
        return "Trigger incremental compilation of the project or a specific module";
    }



    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }
@Override
    public @NotNull String permissionTemplate() {
        return "Build project";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
            {JSON_MODULE, TYPE_STRING, "Optional: build only a specific module (e.g., 'plugin-core')"}
        });
    }

    @Override
    public @NotNull Object resultRenderer() {
        return BuildResultRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        if (!buildInProgress.compareAndSet(false, true)) {
            return "Build already in progress. Please wait for the current build to complete before requesting another.";
        }

        String moduleName = args.has(JSON_MODULE) ? args.get(JSON_MODULE).getAsString() : "";

        // Open Build tool window in follow mode
        if (com.github.catatafishen.ideagentforcopilot.psi.ToolLayerSettings.getInstance(project).getFollowAgentFiles()) {
            com.github.catatafishen.ideagentforcopilot.psi.EdtUtil.invokeLater(() -> {
                var tw = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("Build");
                if (tw != null) tw.activate(null);
            });
        }

        return com.github.catatafishen.ideagentforcopilot.psi.java.ProjectBuildSupport.buildProject(
            project, moduleName, buildInProgress);
    }
}
