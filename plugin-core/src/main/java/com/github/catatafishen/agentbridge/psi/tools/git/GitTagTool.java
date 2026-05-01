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
        return schema(
            Param.optional(PARAM_ACTION, TYPE_STRING, "Action: 'list' (default), 'create', 'delete'"),
            Param.optional("name", TYPE_STRING, "Tag name (required for create/delete)"),
            Param.optional(PARAM_COMMIT, TYPE_STRING, "Commit to tag (default: HEAD, for create)"),
            Param.optional(PARAM_MESSAGE, TYPE_STRING, "Tag message (for annotated tags)"),
            Param.optional(PARAM_ANNOTATE, TYPE_BOOLEAN, "Create an annotated tag (requires message)"),
            Param.optional(PARAM_PATTERN, TYPE_STRING, "Glob pattern to filter tags (for list)"),
            Param.optional("sort", TYPE_STRING, "Sort field for list (e.g., '-creatordate' for newest first)"),
            Param.optional(PARAM_REPO, TYPE_STRING, REPO_PARAM_DESCRIPTION)
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String repoParam = args.has(PARAM_REPO) ? args.get(PARAM_REPO).getAsString() : null;
        String root = resolveRepoRootOrError(repoParam);
        if (root.startsWith("Error")) return root;

        String action = args.has(PARAM_ACTION)
            ? args.get(PARAM_ACTION).getAsString()
            : "list";

        return switch (action) {
            case "list" -> runGitIn(root, listTagArgs(args));
            case "create" -> createTag(args, repoParam, root);
            case "delete" -> deleteTag(args, repoParam, root);
            default -> "Error: unknown action '" + action + "'. Use: list, create, delete";
        };
    }

    private static String[] listTagArgs(@NotNull JsonObject args) {
        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add("tag");
        cmdArgs.add("-l");
        addTextArg(args, PARAM_PATTERN, cmdArgs);
        addSortArg(args, cmdArgs);
        return cmdArgs.toArray(String[]::new);
    }

    private static void addTextArg(@NotNull JsonObject args, @NotNull String parameter, @NotNull List<String> cmdArgs) {
        if (args.has(parameter) && !args.get(parameter).getAsString().isEmpty()) {
            cmdArgs.add(args.get(parameter).getAsString());
        }
    }

    private static void addSortArg(@NotNull JsonObject args, @NotNull List<String> cmdArgs) {
        if (args.has("sort") && !args.get("sort").getAsString().isEmpty()) {
            cmdArgs.add("--sort=" + args.get("sort").getAsString());
        }
    }

    private String createTag(@NotNull JsonObject args, @Nullable String repoParam, @NotNull String root) throws Exception {
        String ambiError = requireUnambiguousRepo(repoParam, "git_tag create");
        if (ambiError != null) return ambiError;

        String name = requireName(args);
        if (name == null) return "Error: 'name' parameter is required for 'create'";

        boolean annotate = args.has(PARAM_ANNOTATE) && args.get(PARAM_ANNOTATE).getAsBoolean();
        String message = args.has(PARAM_MESSAGE) ? args.get(PARAM_MESSAGE).getAsString() : "";
        if (annotate && message.isEmpty()) {
            return "Error: 'message' is required for annotated tags (annotated tags without a message open an editor, which is unsupported)";
        }
        return runGitIn(root, createTagArgs(args, name, annotate, message));
    }

    private static String[] createTagArgs(
        @NotNull JsonObject args,
        @NotNull String name,
        boolean annotate,
        @NotNull String message
    ) {
        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add("tag");
        if (annotate) cmdArgs.add("-a");
        cmdArgs.add(name);
        addTextArg(args, PARAM_COMMIT, cmdArgs);
        if (!message.isEmpty()) {
            cmdArgs.add("-m");
            cmdArgs.add(message);
        }
        return cmdArgs.toArray(String[]::new);
    }

    private String deleteTag(@NotNull JsonObject args, @Nullable String repoParam, @NotNull String root) throws Exception {
        String ambiError = requireUnambiguousRepo(repoParam, "git_tag delete");
        if (ambiError != null) return ambiError;

        String name = requireName(args);
        if (name == null) return "Error: 'name' parameter is required for 'delete'";
        return runGitIn(root, "tag", "-d", name);
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
