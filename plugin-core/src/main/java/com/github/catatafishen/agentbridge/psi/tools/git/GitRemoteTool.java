package com.github.catatafishen.agentbridge.psi.tools.git;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Lists, adds, removes, or updates remote repositories.
 */
@SuppressWarnings("java:S112")
public final class GitRemoteTool extends GitTool {

    private static final String PARAM_ACTION = "action";
    private static final String ACTION_REMOVE = "remove";
    private static final String GIT_REMOTE = "remote";

    public GitRemoteTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "git_remote";
    }

    @Override
    public @NotNull String displayName() {
        return "Git Remote";
    }

    @Override
    public @NotNull String description() {
        return "List, add, remove, or update remote repositories. Returns remote names and URLs.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "{action} remote {name}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.optional(PARAM_ACTION, TYPE_STRING, "Action: 'list' (default), 'add', 'remove', 'set_url', 'get_url'"),
            Param.optional("name", TYPE_STRING, "Remote name (required for add/remove/set_url/get_url)"),
            Param.optional("url", TYPE_STRING, "Remote URL (required for add/set_url)"),
            Param.optional(PARAM_REPO, TYPE_STRING, REPO_PARAM_DESCRIPTION)
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String repoParam = args.has(PARAM_REPO) ? args.get(PARAM_REPO).getAsString() : null;

        String action = args.has(PARAM_ACTION)
            ? args.get(PARAM_ACTION).getAsString()
            : "list";

        // Write actions require an unambiguous repo
        if (action.equals("add") || action.equals(ACTION_REMOVE) || action.equals("set_url")) {
            String ambiError = requireUnambiguousRepo(repoParam, "git_remote " + action);
            if (ambiError != null) return ambiError;
        }

        String root = resolveRepoRootOrError(repoParam);
        if (root.startsWith("Error")) return root;

        return switch (action) {
            case "list" -> runGitIn(root, GIT_REMOTE, "-v");
            case "add" -> {
                String name = requireString(args, "name");
                String url = requireString(args, "url");
                if (name == null) yield "Error: 'name' parameter is required for 'add'";
                if (url == null) yield "Error: 'url' parameter is required for 'add'";
                yield runGitIn(root, GIT_REMOTE, "add", name, url);
            }
            case ACTION_REMOVE -> {
                String name = requireString(args, "name");
                if (name == null) yield "Error: 'name' parameter is required for '" + ACTION_REMOVE + "'";
                yield runGitIn(root, GIT_REMOTE, ACTION_REMOVE, name);
            }
            case "set_url" -> {
                String name = requireString(args, "name");
                String url = requireString(args, "url");
                if (name == null) yield "Error: 'name' parameter is required for 'set_url'";
                if (url == null) yield "Error: 'url' parameter is required for 'set_url'";
                yield runGitIn(root, GIT_REMOTE, "set-url", name, url);
            }
            case "get_url" -> {
                String name = requireString(args, "name");
                if (name == null) yield "Error: 'name' parameter is required for 'get_url'";
                yield runGitIn(root, GIT_REMOTE, "get-url", name);
            }
            default -> "Error: unknown action '" + action + "'. Use: list, add, remove, set_url, get_url";
        };
    }

    private static @Nullable String requireString(JsonObject args, String key) {
        if (!args.has(key) || args.get(key).getAsString().isEmpty()) {
            return null;
        }
        return args.get(key).getAsString();
    }
}
