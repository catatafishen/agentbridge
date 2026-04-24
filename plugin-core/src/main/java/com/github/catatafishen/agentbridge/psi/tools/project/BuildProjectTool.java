package com.github.catatafishen.agentbridge.psi.tools.project;

import com.github.catatafishen.agentbridge.psi.PsiBridgeService;
import com.github.catatafishen.agentbridge.ui.renderers.BuildResultRenderer;
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
        return "Trigger incremental compilation of the project or a specific module. "
            + "Returns build result with error/warning counts. Use get_compilation_errors "
            + "for a faster check that doesn't trigger a full build.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public boolean needsWriteLock() {
        return false;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Build project";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.optional(JSON_MODULE, TYPE_STRING, "Optional: build only a specific module (e.g., 'plugin-core')")
        );
    }

    @Override
    public @NotNull Object resultRenderer() {
        return BuildResultRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String externalBuildErr = com.github.catatafishen.agentbridge.psi.ToolReadinessGate
            .checkNoBuildInProgress(project, id());
        if (externalBuildErr != null) return externalBuildErr;

        if (!buildInProgress.compareAndSet(false, true)) {
            return "Error: Build already in progress. Please wait for the current build to complete before requesting another.";
        }

        String moduleName = args.has(JSON_MODULE) ? args.get(JSON_MODULE).getAsString() : "";

        // Show Build tool window in follow mode without stealing focus from chat prompt
        if (com.github.catatafishen.agentbridge.psi.ToolLayerSettings.getInstance(project).getFollowAgentFiles()) {
            com.github.catatafishen.agentbridge.psi.EdtUtil.invokeLater(() -> {
                var tw = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("Build");
                if (tw == null) return;
                if (PsiBridgeService.isChatToolWindowActive(project)) {
                    tw.show();
                } else {
                    tw.activate(null);
                }
            });
        }

        return com.github.catatafishen.agentbridge.psi.java.ProjectBuildSupport.buildProject(
            project, moduleName, buildInProgress);
    }
}
