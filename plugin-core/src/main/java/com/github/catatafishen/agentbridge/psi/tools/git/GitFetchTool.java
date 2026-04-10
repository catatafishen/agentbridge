package com.github.catatafishen.agentbridge.psi.tools.git;

import com.github.catatafishen.agentbridge.services.PermissionTemplateUtil;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Downloads objects and refs from a remote without merging.
 */
@SuppressWarnings("java:S112")
public final class GitFetchTool extends GitTool {

    private static final String PARAM_REMOTE = "remote";
    private static final String PARAM_BRANCH = "branch";
    private static final String PARAM_PRUNE = "prune";

    public GitFetchTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "git_fetch";
    }

    @Override
    public @NotNull String displayName() {
        return "Git Fetch";
    }

    @Override
    public @NotNull String description() {
        return "Download objects and refs from a remote without merging. "
            + "Most git tools auto-fetch when needed — use this only for explicit sync. "
            + "Returns fetch result with branch context.";
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
        return "Fetch {remote}";
    }

    @Override
    public @Nullable String resolvePermissionQuestion(@Nullable JsonObject args) {
        JsonObject enriched = args != null ? args.deepCopy() : new JsonObject();
        if (!enriched.has(PARAM_REMOTE)) {
            enriched.addProperty(PARAM_REMOTE, "origin");
        }
        String resolved = PermissionTemplateUtil.substituteArgs(permissionTemplate(), enriched);
        return PermissionTemplateUtil.stripPlaceholders(resolved);
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.optional(PARAM_REMOTE, TYPE_STRING, "Remote name (default: origin)"),
            Param.optional(PARAM_BRANCH, TYPE_STRING, "Specific branch to fetch"),
            Param.optional(PARAM_PRUNE, TYPE_BOOLEAN, "Remove remote-tracking refs that no longer exist on the remote"),
            Param.optional("tags", TYPE_BOOLEAN, "Fetch all tags from the remote")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add("fetch");

        if (args.has(PARAM_PRUNE) && args.get(PARAM_PRUNE).getAsBoolean()) {
            cmdArgs.add("--prune");
        }

        if (args.has("tags") && args.get("tags").getAsBoolean()) {
            cmdArgs.add("--tags");
        }

        if (args.has(PARAM_REMOTE) && !args.get(PARAM_REMOTE).getAsString().isEmpty()) {
            cmdArgs.add(args.get(PARAM_REMOTE).getAsString());
        }

        if (args.has(PARAM_BRANCH) && !args.get(PARAM_BRANCH).getAsString().isEmpty()) {
            cmdArgs.add(args.get(PARAM_BRANCH).getAsString());
        }

        String result = runGit(cmdArgs.toArray(String[]::new));
        String output = result.isBlank() ? "Fetch completed successfully." : result;

        // Reset throttle so subsequent tools see the fresh state
        lastFetchTime.set(System.currentTimeMillis());

        return output + getBranchContext();
    }
}
