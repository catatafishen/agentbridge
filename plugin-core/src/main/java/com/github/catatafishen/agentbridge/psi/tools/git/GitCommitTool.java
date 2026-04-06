package com.github.catatafishen.agentbridge.psi.tools.git;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.github.catatafishen.agentbridge.ui.renderers.GitCommitRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Commits staged changes with a message.
 */
@SuppressWarnings("java:S112")
public final class GitCommitTool extends GitTool {

    private static final String PARAM_MESSAGE = "message";
    private static final String PARAM_AMEND = "amend";
    private static final String PARAM_AUTHOR = "author";

    public GitCommitTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "git_commit";
    }

    @Override
    public @NotNull String displayName() {
        return "Git Commit";
    }

    @Override
    public @NotNull String description() {
        return "Commit staged changes with a message";
    }



    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }
@Override
    public @NotNull String permissionTemplate() {
        return "Commit: \"{message}\"";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
            {PARAM_MESSAGE, TYPE_STRING, "Commit message (use conventional commit format)"},
            {PARAM_AMEND, TYPE_BOOLEAN, "If true, amend the previous commit instead of creating a new one"},
            {PARAM_AUTHOR, TYPE_STRING, "Override the commit author (e.g. 'Name <email@example.com>')"},
            {"all", TYPE_BOOLEAN, "If true, automatically stage all modified and deleted files"}
        }, PARAM_MESSAGE);
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        flushAndSave();

        if (!args.has(PARAM_MESSAGE) || args.get(PARAM_MESSAGE).getAsString().isEmpty()) {
            return "Error: 'message' parameter is required";
        }

        // Open VCS tool window in follow mode
        if (ToolLayerSettings.getInstance(project).getFollowAgentFiles()) {
            EdtUtil.invokeLater(() -> {
                var tw = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                    .getToolWindow(com.intellij.openapi.wm.ToolWindowId.VCS);
                if (tw != null) tw.activate(null);
            });
        }

        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add("commit");

        if (args.has(PARAM_AMEND) && args.get(PARAM_AMEND).getAsBoolean()) {
            cmdArgs.add("--amend");
        }

        if (args.has("all") && args.get("all").getAsBoolean()) {
            cmdArgs.add("--all");
        }

        if (args.has(PARAM_AUTHOR) && !args.get(PARAM_AUTHOR).getAsString().isEmpty()) {
            cmdArgs.add("--author");
            cmdArgs.add(args.get(PARAM_AUTHOR).getAsString());
        }

        cmdArgs.add("-m");
        cmdArgs.add(args.get(PARAM_MESSAGE).getAsString());

        String result = runGit(cmdArgs.toArray(String[]::new));
        showNewCommitInLog();
        return result;
    }

    @Override
    public @NotNull Object resultRenderer() {
        return GitCommitRenderer.INSTANCE;
    }
}
