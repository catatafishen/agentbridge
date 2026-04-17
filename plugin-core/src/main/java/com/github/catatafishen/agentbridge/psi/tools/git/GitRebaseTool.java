package com.github.catatafishen.agentbridge.psi.tools.git;

import com.github.catatafishen.agentbridge.psi.review.AgentEditSession;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("java:S112")
public final class GitRebaseTool extends GitTool {

    private static final String CMD_REBASE = "rebase";
    private static final String PARAM_BRANCH = "branch";
    private static final String PARAM_INTERACTIVE = "interactive";
    private static final String PARAM_AUTOSQUASH = "autosquash";
    private static final String PARAM_ABORT = "abort";
    private static final String PARAM_CONTINUE_REBASE = "continue_rebase";
    private static final String PARAM_EXEC = "exec";

    public GitRebaseTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "git_rebase";
    }

    @Override
    public @NotNull String displayName() {
        return "Git Rebase";
    }

    @Override
    public @NotNull String description() {
        return "Rebase current branch onto another. Auto-fetches from origin when rebasing "
            + "onto a remote branch (origin/*). Returns rebase result with branch context.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EXECUTE;
    }

    @Override
    public boolean isDestructive() {
        return true;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Rebase onto {branch}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.optional(PARAM_BRANCH, TYPE_STRING, "Branch to rebase onto"),
            Param.optional("onto", TYPE_STRING, "Rebase onto a specific commit (used with --onto)"),
            Param.optional(PARAM_INTERACTIVE, TYPE_BOOLEAN, "Start an interactive rebase"),
            Param.optional(PARAM_AUTOSQUASH, TYPE_BOOLEAN, "Automatically squash fixup! and squash! commits (requires interactive)"),
            Param.optional(PARAM_EXEC, TYPE_STRING, "Shell command to run after each rebase step (e.g. 'make test')"),
            Param.optional(PARAM_ABORT, TYPE_BOOLEAN, "Abort an in-progress rebase"),
            Param.optional(PARAM_CONTINUE_REBASE, TYPE_BOOLEAN, "Continue a paused rebase after resolving conflicts"),
            Param.optional("skip", TYPE_BOOLEAN, "Skip the current patch and continue rebase")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        flushAndSave();

        String controlResult = handleControlArgs(args);
        if (controlResult != null) return controlResult;

        // Auto-fetch when rebasing onto a remote branch
        String branchArg = args.has(PARAM_BRANCH) ? args.get(PARAM_BRANCH).getAsString() : null;
        String ontoArg = args.has("onto") ? args.get("onto").getAsString() : null;
        String fetchNote = autoFetchForRemoteRef(branchArg);
        if (fetchNote.isEmpty()) fetchNote = autoFetchForRemoteRef(ontoArg);

        String reviewError = AgentEditSession.getInstance(project)
            .awaitReviewCompletion("git rebase");
        if (reviewError != null) return reviewError;

        String result = runGit(buildRebaseArgs(args).toArray(String[]::new));
        if (result.startsWith("Error")) return fetchNote + result;

        AgentEditSession.getInstance(project).invalidateOnWorktreeChange("git rebase");
        return fetchNote + result + getBranchContext();
    }

    private @Nullable String handleControlArgs(@NotNull JsonObject args) throws Exception {
        if (args.has(PARAM_ABORT) && args.get(PARAM_ABORT).getAsBoolean()) {
            return runGit(CMD_REBASE, "--abort");
        }
        if (args.has(PARAM_CONTINUE_REBASE) && args.get(PARAM_CONTINUE_REBASE).getAsBoolean()) {
            return runGit(CMD_REBASE, "--continue");
        }
        if (args.has("skip") && args.get("skip").getAsBoolean()) {
            return runGit(CMD_REBASE, "--skip");
        }
        if (args.has(PARAM_INTERACTIVE) && args.get(PARAM_INTERACTIVE).getAsBoolean()) {
            return "Error: interactive rebase requires a terminal text editor and cannot run in the plugin context. " +
                "Use the IDE's Git > Rebase... dialog instead.";
        }
        return null;
    }

    private @NotNull List<String> buildRebaseArgs(@NotNull JsonObject args) {
        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add(CMD_REBASE);
        if (args.has(PARAM_AUTOSQUASH) && args.get(PARAM_AUTOSQUASH).getAsBoolean()) {
            cmdArgs.add("--autosquash");
        }
        if (args.has("onto") && !args.get("onto").getAsString().isEmpty()) {
            cmdArgs.add("--onto");
            cmdArgs.add(args.get("onto").getAsString());
        }
        if (args.has(PARAM_EXEC) && !args.get(PARAM_EXEC).getAsString().isEmpty()) {
            cmdArgs.add("--exec");
            cmdArgs.add(args.get(PARAM_EXEC).getAsString());
        }
        if (args.has(PARAM_BRANCH) && !args.get(PARAM_BRANCH).getAsString().isEmpty()) {
            cmdArgs.add(args.get(PARAM_BRANCH).getAsString());
        }
        return cmdArgs;
    }
}
