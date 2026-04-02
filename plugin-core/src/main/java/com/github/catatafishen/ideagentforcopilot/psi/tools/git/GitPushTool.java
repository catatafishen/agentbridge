package com.github.catatafishen.ideagentforcopilot.psi.tools.git;

import com.github.catatafishen.ideagentforcopilot.services.PermissionTemplateUtil;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("java:S112")
public final class GitPushTool extends GitTool {

    private static final String PARAM_REMOTE = "remote";
    private static final String PARAM_BRANCH = "branch";
    private static final String PARAM_FORCE = "force";
    private static final String PARAM_SET_UPSTREAM = "set_upstream";

    public GitPushTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "git_push";
    }

    @Override
    public @NotNull String displayName() {
        return "Git Push";
    }

    @Override
    public @NotNull String description() {
        return "Push commits to a remote repository";
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
    public boolean isOpenWorld() {
        return true;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Push to {remote} ({branch})";
    }

    @Override
    public @Nullable String resolvePermissionQuestion(@Nullable JsonObject args) {
        JsonObject enriched = args != null ? args.deepCopy() : new JsonObject();
        if (!enriched.has(PARAM_REMOTE)) {
            enriched.addProperty(PARAM_REMOTE, "origin");
        }
        if (!enriched.has(PARAM_BRANCH)) {
            String branch = detectCurrentBranch();
            enriched.addProperty(PARAM_BRANCH, branch != null ? branch : "current branch");
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
        return schema(new Object[][]{
            {PARAM_REMOTE, TYPE_STRING, "Remote name (default: origin)"},
            {PARAM_BRANCH, TYPE_STRING, "Branch to push (default: current)"},
            {PARAM_FORCE, TYPE_BOOLEAN, "Force push"},
            {PARAM_SET_UPSTREAM, TYPE_BOOLEAN, "Set upstream tracking reference"},
            {"tags", TYPE_BOOLEAN, "Push all tags"}
        });
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add("push");

        boolean setUpstream = args.has(PARAM_SET_UPSTREAM) && args.get(PARAM_SET_UPSTREAM).getAsBoolean();

        if (args.has(PARAM_FORCE) && args.get(PARAM_FORCE).getAsBoolean()) {
            cmdArgs.add("--force");
        }
        if (setUpstream) {
            cmdArgs.add("--set-upstream");
        }

        String remote = args.has(PARAM_REMOTE) ? args.get(PARAM_REMOTE).getAsString() : null;
        String branch = args.has(PARAM_BRANCH) ? args.get(PARAM_BRANCH).getAsString() : null;

        if (setUpstream) {
            if (remote == null) {
                remote = "origin";
            }
            if (branch == null) {
                branch = runGit("rev-parse", "--abbrev-ref", "HEAD").trim();
            }
        }

        if (remote != null) {
            cmdArgs.add(remote);
        }
        if (branch != null) {
            cmdArgs.add(branch);
        }
        if (args.has("tags") && args.get("tags").getAsBoolean()) {
            cmdArgs.add("--tags");
        }

        return runGit(cmdArgs.toArray(String[]::new));
    }
}
