package com.github.catatafishen.ideagentforcopilot.psi.tools.git;

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
        return "Rebase current branch onto another";
    }

    

    @Override
    public @NotNull String kind() {
        return "execute";
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
        return schema(new Object[][]{
            {PARAM_BRANCH, TYPE_STRING, "Branch to rebase onto"},
            {"onto", TYPE_STRING, "Rebase onto a specific commit (used with --onto)"},
            {PARAM_INTERACTIVE, TYPE_BOOLEAN, "Start an interactive rebase"},
            {PARAM_AUTOSQUASH, TYPE_BOOLEAN, "Automatically squash fixup! and squash! commits (requires interactive)"},
            {PARAM_ABORT, TYPE_BOOLEAN, "Abort an in-progress rebase"},
            {PARAM_CONTINUE_REBASE, TYPE_BOOLEAN, "Continue a paused rebase after resolving conflicts"},
            {"skip", TYPE_BOOLEAN, "Skip the current patch and continue rebase"}
        });
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        flushAndSave();

        if (args.has(PARAM_ABORT) && args.get(PARAM_ABORT).getAsBoolean()) {
            return runGit(CMD_REBASE, "--abort");
        }

        if (args.has(PARAM_CONTINUE_REBASE) && args.get(PARAM_CONTINUE_REBASE).getAsBoolean()) {
            return runGit(CMD_REBASE, "--continue");
        }

        if (args.has("skip") && args.get("skip").getAsBoolean()) {
            return runGit(CMD_REBASE, "--skip");
        }

        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add(CMD_REBASE);

        if (args.has(PARAM_INTERACTIVE) && args.get(PARAM_INTERACTIVE).getAsBoolean()) {
            cmdArgs.add("--interactive");
        }

        if (args.has(PARAM_AUTOSQUASH) && args.get(PARAM_AUTOSQUASH).getAsBoolean()) {
            cmdArgs.add("--autosquash");
        }

        if (args.has("onto") && !args.get("onto").getAsString().isEmpty()) {
            cmdArgs.add("--onto");
            cmdArgs.add(args.get("onto").getAsString());
        }

        if (args.has(PARAM_BRANCH) && !args.get(PARAM_BRANCH).getAsString().isEmpty()) {
            cmdArgs.add(args.get(PARAM_BRANCH).getAsString());
        }

        return runGit(cmdArgs.toArray(String[]::new));
    }
}
