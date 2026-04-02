package com.github.catatafishen.ideagentforcopilot.psi.tools.git;

import com.github.catatafishen.ideagentforcopilot.ui.renderers.GitStageRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Stages one or more files for the next commit.
 */
@SuppressWarnings("java:S112")
public final class GitStageTool extends GitTool {

    private static final String PARAM_PATHS = "paths";

    public GitStageTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "git_stage";
    }

    @Override
    public @NotNull String displayName() {
        return "Git Stage";
    }

    @Override
    public @NotNull String description() {
        return "Stage one or more files for the next commit";
    }



    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }
@Override
    public @NotNull String permissionTemplate() {
        return "Stage {path}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        JsonObject s = schema(new Object[][]{
            {"path", TYPE_STRING, "Single file path to stage"},
            {PARAM_PATHS, TYPE_ARRAY, "Multiple file paths to stage"},
            {"all", TYPE_BOOLEAN, "If true, stage all changes (including untracked files)"}
        });
        addArrayItems(s, PARAM_PATHS);
        return s;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        flushAndSave();

        // Open Version Control tool window in follow mode
        if (com.github.catatafishen.ideagentforcopilot.psi.ToolLayerSettings.getInstance(project).getFollowAgentFiles()) {
            com.github.catatafishen.ideagentforcopilot.psi.EdtUtil.invokeLater(() -> {
                var tw = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("Version Control");
                if (tw != null) tw.activate(null);
            });
        }

        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add("add");

        List<String> stagedFiles = new ArrayList<>();

        if (args.has("all") && args.get("all").getAsBoolean()) {
            cmdArgs.add("--all");
            stagedFiles.add("all changes");
        } else if (args.has(PARAM_PATHS) && args.get(PARAM_PATHS).isJsonArray()) {
            var paths = args.getAsJsonArray(PARAM_PATHS);
            for (var p : paths) {
                String file = p.getAsString();
                cmdArgs.add(file);
                stagedFiles.add(file);
            }
        } else if (args.has("path") && !args.get("path").getAsString().isEmpty()) {
            String file = args.get("path").getAsString();
            cmdArgs.add(file);
            stagedFiles.add(file);
        } else {
            return "Error: provide 'path', 'paths', or 'all' parameter";
        }

        String result = runGit(cmdArgs.toArray(String[]::new));

        if (result == null || result.isBlank()) {
            return "Staged: " + String.join(", ", stagedFiles);
        }
        return result;
    }

    @Override
    public @NotNull Object resultRenderer() {
        return GitStageRenderer.INSTANCE;
    }
}
