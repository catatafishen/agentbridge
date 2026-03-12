package com.github.catatafishen.ideagentforcopilot.psi.tools.git;

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
        return "Download objects and refs from a remote";
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
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"remote", TYPE_STRING, "Remote name (default: origin)"},
            {"branch", TYPE_STRING, "Specific branch to fetch"},
            {"prune", TYPE_BOOLEAN, "Remove remote-tracking refs that no longer exist on the remote"},
            {"tags", TYPE_BOOLEAN, "Fetch all tags from the remote"}
        });
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add("fetch");

        if (args.has("prune") && args.get("prune").getAsBoolean()) {
            cmdArgs.add("--prune");
        }

        if (args.has("tags") && args.get("tags").getAsBoolean()) {
            cmdArgs.add("--tags");
        }

        if (args.has("remote") && !args.get("remote").getAsString().isEmpty()) {
            cmdArgs.add(args.get("remote").getAsString());
        }

        if (args.has("branch") && !args.get("branch").getAsString().isEmpty()) {
            cmdArgs.add(args.get("branch").getAsString());
        }

        String result = runGit(cmdArgs.toArray(String[]::new));
        return result.isBlank() ? "Fetch completed successfully." : result;
    }
}
