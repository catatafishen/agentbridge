package com.github.catatafishen.ideagentforcopilot.psi.tools.git;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Lists, adds, removes, or updates remote repositories.
 */
@SuppressWarnings("java:S112")
public final class GitRemoteTool extends GitTool {

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
        return "List, add, remove, or update remote repositories";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"action", TYPE_STRING, "Action: 'list' (default), 'add', 'remove', 'set_url', 'get_url'"},
            {"name", TYPE_STRING, "Remote name (required for add/remove/set_url/get_url)"},
            {"url", TYPE_STRING, "Remote URL (required for add/set_url)"}
        });
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        String action = args.has("action")
            ? args.get("action").getAsString()
            : "list";

        return switch (action) {
            case "list" -> runGit("remote", "-v");
            case "add" -> {
                String name = requireString(args, "name");
                String url = requireString(args, "url");
                if (name == null) yield "Error: 'name' parameter is required for 'add'";
                if (url == null) yield "Error: 'url' parameter is required for 'add'";
                yield runGit("remote", "add", name, url);
            }
            case "remove" -> {
                String name = requireString(args, "name");
                if (name == null) yield "Error: 'name' parameter is required for 'remove'";
                yield runGit("remote", "remove", name);
            }
            case "set_url" -> {
                String name = requireString(args, "name");
                String url = requireString(args, "url");
                if (name == null) yield "Error: 'name' parameter is required for 'set_url'";
                if (url == null) yield "Error: 'url' parameter is required for 'set_url'";
                yield runGit("remote", "set-url", name, url);
            }
            case "get_url" -> {
                String name = requireString(args, "name");
                if (name == null) yield "Error: 'name' parameter is required for 'get_url'";
                yield runGit("remote", "get-url", name);
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
