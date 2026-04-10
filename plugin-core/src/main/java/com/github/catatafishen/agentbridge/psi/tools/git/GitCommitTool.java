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
        return "Commit staged changes with a message. Returns the commit result along with "
            + "branch context: current branch, tracking status, ahead/behind counts, "
            + "total commits on the branch, and remaining uncommitted changes.";
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
        return schema(
            Param.required(PARAM_MESSAGE, TYPE_STRING, "Commit message (use conventional commit format)"),
            Param.optional(PARAM_AMEND, TYPE_BOOLEAN, "If true, amend the previous commit instead of creating a new one"),
            Param.optional(PARAM_AUTHOR, TYPE_STRING, "Override the commit author (e.g. 'Name <email@example.com>')"),
            Param.optional("all", TYPE_BOOLEAN, "If true, automatically stage all modified and deleted files")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        flushAndSave();

        if (!args.has(PARAM_MESSAGE) || args.get(PARAM_MESSAGE).getAsString().isEmpty()) {
            return "Error: 'message' parameter is required";
        }

        boolean commitAll = args.has("all") && args.get("all").getAsBoolean();

        // Pre-commit check: verify there are staged changes (unless --all is used)
        if (!commitAll) {
            String staged = runGitQuiet("diff", "--cached", "--name-only");
            if (staged != null && staged.isEmpty()) {
                String unstaged = runGitQuiet("diff", "--name-only");
                String untracked = runGitQuiet("ls-files", "--others", "--exclude-standard");
                StringBuilder hint = new StringBuilder("Error: nothing staged for commit.");
                if (unstaged != null && !unstaged.isEmpty()) {
                    hint.append(" There are unstaged changes — use git_stage first,")
                        .append(" or pass all: true to auto-stage modified files.");
                } else if (untracked != null && !untracked.isEmpty()) {
                    hint.append(" There are untracked files — use git_stage to add them first.");
                } else {
                    hint.append(" The working tree is clean — there is nothing to commit.");
                }
                return hint.toString();
            }
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

        if (commitAll) {
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

        if (result.startsWith("Error")) return result;

        // Warn if committing directly to default branch
        String branch = runGitQuiet("rev-parse", "--abbrev-ref", "HEAD");
        if ("main".equals(branch) || "master".equals(branch)) {
            result += "\n\n⚠️ Warning: you committed directly to " + branch
                + ". Consider using a feature branch instead.";
        }

        return result + getBranchContext();
    }

    @Override
    public @NotNull Object resultRenderer() {
        return GitCommitRenderer.INSTANCE;
    }
}
