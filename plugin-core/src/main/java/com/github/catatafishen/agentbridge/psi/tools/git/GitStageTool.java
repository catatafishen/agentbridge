package com.github.catatafishen.agentbridge.psi.tools.git;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.github.catatafishen.agentbridge.ui.renderers.GitStageRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.changes.ChangesViewManager;
import com.intellij.openapi.wm.ToolWindowManager;
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
        return "Stage one or more files for the next commit. Returns a confirmation of what was "
            + "staged, along with counts of remaining staged, unstaged, and untracked files.";
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

        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add("add");

        List<String> stagedFiles = collectStagePaths(args, cmdArgs);
        if (stagedFiles == null) {
            return "Error: provide 'path', 'paths', or 'all' parameter";
        }

        String result = runGit(cmdArgs.toArray(String[]::new));

        refreshAndActivateCommitPanel();

        String base;
        if (result == null || result.isBlank()) {
            base = "Staged: " + String.join(", ", stagedFiles);
        } else {
            base = result;
        }

        if (base.startsWith("Error")) return base;

        return base + getBranchSummary();
    }

    /**
     * Refreshes VCS status and opens the Commit tool window so the user sees
     * the newly staged files. Uses {@link ChangesViewManager#getLocalChangesToolWindowName}
     * to resolve the correct tool window ID regardless of commit UI mode.
     */
    private void refreshAndActivateCommitPanel() {
        if (!ToolLayerSettings.getInstance(project).getFollowAgentFiles()) {
            return;
        }
        ChangesViewManager.getInstance(project).scheduleRefresh();
        EdtUtil.invokeLater(() -> {
            String toolWindowName = ChangesViewManager.getLocalChangesToolWindowName(project);
            var tw = ToolWindowManager.getInstance(project).getToolWindow(toolWindowName);
            if (tw != null) tw.activate(null);
        });
    }

    /**
     * Parses stage target from args (all / paths / path) and populates cmdArgs.
     * Returns the list of staged file descriptions, or null if no valid target.
     */
    @Nullable
    private static List<String> collectStagePaths(JsonObject args, List<String> cmdArgs) {
        List<String> stagedFiles = new ArrayList<>();
        if (args.has("all") && args.get("all").getAsBoolean()) {
            cmdArgs.add("--all");
            stagedFiles.add("all changes");
        } else if (args.has(PARAM_PATHS) && args.get(PARAM_PATHS).isJsonArray()) {
            for (var p : args.getAsJsonArray(PARAM_PATHS)) {
                String file = p.getAsString();
                cmdArgs.add(file);
                stagedFiles.add(file);
            }
        } else if (args.has("path") && !args.get("path").getAsString().isEmpty()) {
            String file = args.get("path").getAsString();
            cmdArgs.add(file);
            stagedFiles.add(file);
        } else {
            return null;
        }
        return stagedFiles;
    }

    @Override
    public @NotNull Object resultRenderer() {
        return GitStageRenderer.INSTANCE;
    }
}
