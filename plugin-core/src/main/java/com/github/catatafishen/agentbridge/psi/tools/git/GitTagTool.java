package com.github.catatafishen.agentbridge.psi.tools.git;

import com.github.catatafishen.agentbridge.ui.renderers.GitTagRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Lists, creates, or deletes tags.
 */
@SuppressWarnings("java:S112")
public final class GitTagTool extends GitTool {

    private static final String PARAM_ACTION = "action";
    private static final String PARAM_ANNOTATE = "annotate";
    private static final String PARAM_COMMIT = "commit";
    private static final String PARAM_MESSAGE = "message";
    private static final String PARAM_PATTERN = "pattern";

    public GitTagTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "git_tag";
    }

    @Override
    public @NotNull String displayName() {
        return "Git Tag";
    }

    @Override
    public @NotNull String description() {
        return "List, create, or delete tags. Supports annotated tags with messages, filtering by glob pattern, and sorting (e.g. '-creatordate' for newest first).";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "{action} tag {name}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
            {PARAM_ACTION, TYPE_STRING, "Action: 'list' (default), 'create', 'delete'"},
            {"name", TYPE_STRING, "Tag name (required for create/delete)"},
            {PARAM_COMMIT, TYPE_STRING, "Commit to tag (default: HEAD, for create)"},
            {PARAM_MESSAGE, TYPE_STRING, "Tag message (for annotated tags)"},
            {PARAM_ANNOTATE, TYPE_BOOLEAN, "Create an annotated tag (requires message)"},
            {PARAM_PATTERN, TYPE_STRING, "Glob pattern to filter tags (for list)"},
            {"sort", TYPE_STRING, "Sort field for list (e.g., '-creatordate' for newest first)"}
        });
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String action = args.has(PARAM_ACTION)
            ? args.get(PARAM_ACTION).getAsString()
            : "list";

        return switch (action) {
            case "list" -> {
                List<String> cmdArgs = new ArrayList<>();
                cmdArgs.add("tag");
                cmdArgs.add("-l");

                if (args.has(PARAM_PATTERN) && !args.get(PARAM_PATTERN).getAsString().isEmpty()) {
                    cmdArgs.add(args.get(PARAM_PATTERN).getAsString());
                }

                if (args.has("sort") && !args.get("sort").getAsString().isEmpty()) {
                    cmdArgs.add("--sort=" + args.get("sort").getAsString());
                }

                yield runGit(cmdArgs.toArray(String[]::new));
            }
            case "create" -> {
                String name = requireName(args);
                if (name == null) yield "Error: 'name' parameter is required for 'create'";

                boolean annotate = args.has(PARAM_ANNOTATE) && args.get(PARAM_ANNOTATE).getAsBoolean();
                String message = args.has(PARAM_MESSAGE) ? args.get(PARAM_MESSAGE).getAsString() : "";

                if (annotate && message.isEmpty()) {
                    yield "Error: 'message' is required for annotated tags (annotated tags without a message open an editor, which is unsupported)";
                }

                List<String> cmdArgs = new ArrayList<>();
                cmdArgs.add("tag");

                if (annotate) {
                    cmdArgs.add("-a");
                }

                cmdArgs.add(name);

                if (args.has(PARAM_COMMIT) && !args.get(PARAM_COMMIT).getAsString().isEmpty()) {
                    cmdArgs.add(args.get(PARAM_COMMIT).getAsString());
                }

                if (!message.isEmpty()) {
                    cmdArgs.add("-m");
                    cmdArgs.add(message);
                }

                yield runGit(cmdArgs.toArray(String[]::new));
            }
            case "delete" -> {
                String name = requireName(args);
                if (name == null) yield "Error: 'name' parameter is required for 'delete'";
                yield runGit("tag", "-d", name);
            }
            default -> "Error: unknown action '" + action + "'. Use: list, create, delete";
        };
    }

    private static @Nullable String requireName(JsonObject args) {
        if (!args.has("name") || args.get("name").getAsString().isEmpty()) {
            return null;
        }
        return args.get("name").getAsString();
    }

    @Override
    public @NotNull Object resultRenderer() {
        return GitTagRenderer.INSTANCE;
    }
}
