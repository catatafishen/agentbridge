package com.github.catatafishen.agentbridge.psi.tools.git;

import com.github.catatafishen.agentbridge.services.PermissionTemplateUtil;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("java:S112")
public final class GitPullTool extends GitTool {

    private static final String PARAM_REBASE = "rebase";
    private static final String PARAM_REMOTE = "remote";
    private static final String PARAM_BRANCH = "branch";
    private static final String PARAM_FF_ONLY = "ff_only";

    public GitPullTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "git_pull";
    }

    @Override
    public @NotNull String displayName() {
        return "Git Pull";
    }

    @Override
    public @NotNull String description() {
        return "Fetch and integrate changes into the current branch. Supports rebase mode "
            + "(rebase: true) and fast-forward-only (ff_only: true). Returns pull result "
            + "with branch context.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public boolean isOpenWorld() {
        return true;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Pull {remote}/{branch}";
    }

    @Override
    public @Nullable String resolvePermissionQuestion(@Nullable JsonObject args) {
        JsonObject enriched = args != null ? args.deepCopy() : new JsonObject();
        if (!enriched.has(PARAM_REMOTE)) {
            enriched.addProperty(PARAM_REMOTE, "origin");
        }
        if (!enriched.has(PARAM_BRANCH)) {
            String branch = detectCurrentBranch();
            enriched.addProperty(PARAM_BRANCH, branch != null ? branch : "current");
        }
        String resolved = PermissionTemplateUtil.substituteArgs(permissionTemplate(), enriched);
        return PermissionTemplateUtil.stripPlaceholders(resolved);
    }

    @Nullable
    private String detectCurrentBranch() {
        try {
            return runGit("rev-parse", "--abbrev-ref", "HEAD").trim();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.optional(PARAM_REMOTE, TYPE_STRING, "Remote name (default: origin)"),
            Param.optional(PARAM_BRANCH, TYPE_STRING, "Branch to pull (default: current tracking branch)"),
            Param.optional(PARAM_REBASE, TYPE_BOOLEAN, "If true, rebase instead of merge when pulling"),
            Param.optional(PARAM_FF_ONLY, TYPE_BOOLEAN, "If true, only fast-forward (abort if not possible)")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        flushAndSave();

        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add("pull");

        if (args.has(PARAM_REBASE) && args.get(PARAM_REBASE).getAsBoolean()) {
            cmdArgs.add("--rebase");
        }

        if (args.has(PARAM_FF_ONLY) && args.get(PARAM_FF_ONLY).getAsBoolean()) {
            cmdArgs.add("--ff-only");
        }

        if (args.has(PARAM_REMOTE) && !args.get(PARAM_REMOTE).getAsString().isEmpty()) {
            cmdArgs.add(args.get(PARAM_REMOTE).getAsString());
        }

        if (args.has(PARAM_BRANCH) && !args.get(PARAM_BRANCH).getAsString().isEmpty()) {
            cmdArgs.add(args.get(PARAM_BRANCH).getAsString());
        }

        String result = runGit(cmdArgs.toArray(String[]::new));
        if (result.startsWith("Error")) return result;

        return result + getBranchContext();
    }
}
