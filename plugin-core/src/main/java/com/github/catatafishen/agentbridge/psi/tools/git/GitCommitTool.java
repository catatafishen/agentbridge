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
        return "Commit staged changes with a message. By default stages ALL changes first "
            + "(modified, deleted, and new untracked files) — equivalent to 'git add -A && git commit'. "
            + "Set all: false to commit only what is already staged. "
            + "Returns the commit result with the list of committed files, branch, tracking status, "
            + "ahead/behind counts, total commits on the branch, and remaining uncommitted changes.";
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
            Param.optional("all", TYPE_BOOLEAN, "Stage all changes (modified, deleted, and new untracked files) before committing. Default: true. Set false to commit only already-staged changes.")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        flushAndSave();

        if (!args.has(PARAM_MESSAGE) || args.get(PARAM_MESSAGE).getAsString().isEmpty()) {
            return "Error: 'message' parameter is required";
        }

        boolean commitAll = resolveCommitAll(args);

        if (commitAll) {
            // Stage all changes including new untracked files (equivalent to git add -A)
            runGit("add", "-A");
        }

        // Pre-commit check: verify there are staged changes
        String staged = runGitQuiet("diff", "--cached", "--name-only");
        if (staged != null && staged.isEmpty()) {
            String unstaged = runGitQuiet("diff", "--name-only");
            StringBuilder hint = new StringBuilder("Error: nothing to commit.");
            if (unstaged != null && !unstaged.isEmpty()) {
                hint.append(" There are unstaged changes not picked up by --all (e.g. ignored files).");
            } else {
                hint.append(" The working tree is clean.");
            }
            return hint.toString();
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

        if (args.has(PARAM_AUTHOR) && !args.get(PARAM_AUTHOR).getAsString().isEmpty()) {
            cmdArgs.add("--author");
            cmdArgs.add(args.get(PARAM_AUTHOR).getAsString());
        }

        cmdArgs.add("-m");
        cmdArgs.add(args.get(PARAM_MESSAGE).getAsString());

        String result = runGit(cmdArgs.toArray(String[]::new));
        showNewCommitInLog();

        if (result.startsWith("Error")) return result;

        // Append committed file list so agent can verify what was included
        String fileStats = runGitQuiet("show", "--stat", "--format=", "HEAD");
        if (fileStats != null && !fileStats.isBlank()) {
            result += "\n\nCommitted files:\n" + fileStats.trim();
        }

        // Warn if committing directly to default branch
        String branch = runGitQuiet("rev-parse", "--abbrev-ref", "HEAD");
        if ("main".equals(branch) || "master".equals(branch)) {
            result += "\n\n⚠️ Warning: you committed directly to " + branch
                + ". Consider using a feature branch instead.";
        }

        return result + getBranchContext();
    }

    /**
     * Resolves the "all" parameter: defaults to true (stage everything) unless explicitly set to false.
     */
    static boolean resolveCommitAll(JsonObject args) {
        return !args.has("all") || args.get("all").getAsBoolean();
    }

    @Override
    public @NotNull Object resultRenderer() {
        return GitCommitRenderer.INSTANCE;
    }
}
