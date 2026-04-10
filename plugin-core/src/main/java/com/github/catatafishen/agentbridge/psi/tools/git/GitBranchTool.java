package com.github.catatafishen.agentbridge.psi.tools.git;

import com.github.catatafishen.agentbridge.ui.renderers.GitBranchRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@SuppressWarnings("java:S112")
public final class GitBranchTool extends GitTool {

    private static final String CMD_BRANCH = "branch";
    private static final String PARAM_ACTION = "action";
    private static final String PARAM_NAME = "name";
    private static final String PARAM_BASE = "base";
    private static final String PARAM_ALL = "all";
    private static final String PARAM_FORCE = "force";

    public GitBranchTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "git_branch";
    }

    @Override
    public @NotNull String displayName() {
        return "Git Branch";
    }

    @Override
    public @NotNull String description() {
        return "List, create, switch, or delete branches. After create or switch, returns "
            + "branch context: tracking status, ahead/behind counts, and uncommitted changes.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "{action} branch {name}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.optional(PARAM_ACTION, TYPE_STRING, "Action: 'list' (default), 'create', 'switch', 'delete'"),
            Param.optional(PARAM_NAME, TYPE_STRING, "Branch name (required for create/switch/delete)"),
            Param.optional(PARAM_BASE, TYPE_STRING, "Base ref for create (default: HEAD)"),
            Param.optional(PARAM_ALL, TYPE_BOOLEAN, "For list: include remote branches"),
            Param.optional(PARAM_FORCE, TYPE_BOOLEAN, "For delete: force delete unmerged branches")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String action = args.has(PARAM_ACTION)
            ? args.get(PARAM_ACTION).getAsString()
            : "list";

        return switch (action) {
            case "list" -> {
                boolean all = args.has(PARAM_ALL) && args.get(PARAM_ALL).getAsBoolean();
                yield runGit(CMD_BRANCH, all ? "--all" : "--list", "-v");
            }
            case "create" -> {
                String name = requireName(args);
                if (name == null) yield "Error: 'name' parameter is required for 'create'";
                String base = args.has(PARAM_BASE) && !args.get(PARAM_BASE).getAsString().isEmpty()
                    ? args.get(PARAM_BASE).getAsString()
                    : "HEAD";
                String result = runGit(CMD_BRANCH, name, base);
                if (result.startsWith("Error")) yield result;

                String baseDesc = "HEAD".equals(base) ? runGitQuiet("rev-parse", "--short", "HEAD") : base;
                yield (result.isBlank() ? "Created branch '" + name + "'" : result)
                    + "\n\n--- Context ---\n"
                    + "Base: " + (baseDesc != null ? baseDesc : base) + "\n"
                    + "No tracking branch set — use git_push with set_upstream: true to push\n";
            }
            case "switch", "checkout" -> {
                String name = requireName(args);
                if (name == null) yield "Error: 'name' parameter is required for 'switch'";
                String result = runGit("switch", name);
                if (result.startsWith("Error")) yield result;
                yield result + getBranchContext();
            }
            case "delete" -> {
                String name = requireName(args);
                if (name == null) yield "Error: 'name' parameter is required for 'delete'";
                boolean force = args.has(PARAM_FORCE) && args.get(PARAM_FORCE).getAsBoolean();
                yield runGit(CMD_BRANCH, force ? "-D" : "-d", name);
            }
            default -> "Error: unknown action '" + action + "'. Use: list, create, switch, delete";
        };
    }

    private static @Nullable String requireName(JsonObject args) {
        if (!args.has(PARAM_NAME) || args.get(PARAM_NAME).getAsString().isEmpty()) {
            return null;
        }
        return args.get(PARAM_NAME).getAsString();
    }

    @Override
    public @NotNull Object resultRenderer() {
        return GitBranchRenderer.INSTANCE;
    }
}
