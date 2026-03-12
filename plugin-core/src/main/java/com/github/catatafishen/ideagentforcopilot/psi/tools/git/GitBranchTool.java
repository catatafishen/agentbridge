package com.github.catatafishen.ideagentforcopilot.psi.tools.git;

import com.github.catatafishen.ideagentforcopilot.psi.GitToolHandler;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Lists, creates, switches, or deletes branches.
 */
@SuppressWarnings("java:S112")
public final class GitBranchTool extends GitTool {

    private static final String CMD_BRANCH = "branch";

    public GitBranchTool(Project project, GitToolHandler git) {
        super(project, git);
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
        return "List, create, switch, or delete branches";
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "{action} branch {name}";
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"action", TYPE_STRING, "Action: 'list' (default), 'create', 'switch', 'delete'"},
            {"name", TYPE_STRING, "Branch name (required for create/switch/delete)"},
            {"base", TYPE_STRING, "Base ref for create (default: HEAD)"},
            {"all", TYPE_BOOLEAN, "For list: include remote branches"},
            {"force", TYPE_BOOLEAN, "For delete: force delete unmerged branches"}
        });
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        String action = args.has("action")
            ? args.get("action").getAsString()
            : "list";

        return switch (action) {
            case "list" -> {
                boolean all = args.has("all") && args.get("all").getAsBoolean();
                yield git.runGit(CMD_BRANCH, all ? "--all" : "--list", "-v");
            }
            case "create" -> {
                String name = requireName(args);
                if (name == null) yield "Error: 'name' parameter is required for 'create'";
                String base = args.has("base") && !args.get("base").getAsString().isEmpty()
                    ? args.get("base").getAsString()
                    : "HEAD";
                yield git.runGit(CMD_BRANCH, name, base);
            }
            case "switch", "checkout" -> {
                String name = requireName(args);
                if (name == null) yield "Error: 'name' parameter is required for 'switch'";
                yield git.runGit("switch", name);
            }
            case "delete" -> {
                String name = requireName(args);
                if (name == null) yield "Error: 'name' parameter is required for 'delete'";
                boolean force = args.has("force") && args.get("force").getAsBoolean();
                yield git.runGit(CMD_BRANCH, force ? "-D" : "-d", name);
            }
            default -> "Error: unknown action '" + action + "'. Use: list, create, switch, delete";
        };
    }

    private static @Nullable String requireName(JsonObject args) {
        if (!args.has("name") || args.get("name").getAsString().isEmpty()) {
            return null;
        }
        return args.get("name").getAsString();
    }
}
