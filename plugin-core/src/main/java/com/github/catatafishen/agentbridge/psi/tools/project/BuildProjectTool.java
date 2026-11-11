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
    private static final String JSON_MODE = "mode";
    private static final String JSON_FILES = "files";

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
        return "Build the project exactly like the IDE's Build > Build Project menu action: the IDE chooses the "
            + "runner (Gradle, Maven or its own compiler), so Gradle-only steps such as code generation run. "
            + "Optionally build one module, rebuild everything (mode='rebuild'), or compile specific files. "
            + "Returns the outcome plus the Build tool window output. Use get_compilation_errors "
            + "for a faster check that doesn't trigger a full build.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EXECUTE;
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
            Param.optional(JSON_MODULE, TYPE_STRING, "Optional: build only a specific module (e.g., 'plugin-core')"),
            Param.optional(JSON_MODE, TYPE_STRING, "'build' (default, incremental) or 'rebuild' (full rebuild)"),
            Param.optional(JSON_FILES, TYPE_ARRAY, "Optional: compile only these files (absolute or project-relative paths). Overrides module and mode.")
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

        // Parse before taking the guard: a malformed argument (e.g. mode: null, files: [null]) must not
        // leave the guard set and block every later build.
        String moduleName;
        boolean rebuild;
        java.util.List<String> files = new java.util.ArrayList<>();
        try {
            moduleName = args.has(JSON_MODULE) ? args.get(JSON_MODULE).getAsString() : "";
            rebuild = args.has(JSON_MODE) && "rebuild".equalsIgnoreCase(args.get(JSON_MODE).getAsString());
            if (args.has(JSON_FILES) && args.get(JSON_FILES).isJsonArray()) {
                for (var e : args.getAsJsonArray(JSON_FILES)) {
                    files.add(e.getAsString());
                }
            }
        } catch (UnsupportedOperationException | IllegalStateException | ClassCastException e) {
            return "Error: Invalid arguments. 'module' and 'mode' must be strings and 'files' an array of strings"
                + " (null values are not allowed).";
        }

        if (!buildInProgress.compareAndSet(false, true)) {
            return "Error: Build already in progress. Please wait for the current build to complete before requesting another.";
        }

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
            project, moduleName, rebuild, files, buildInProgress);
    }
}
