package com.github.catatafishen.ideagentforcopilot.psi.tools.git;

import com.github.catatafishen.ideagentforcopilot.psi.GitToolHandler;
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

    private static final String PARAM_COMMIT = "commit";
    private static final String PARAM_MESSAGE = "message";
    private static final String PARAM_PATTERN = "pattern";

    public GitTagTool(Project project, GitToolHandler git) {
        super(project, git);
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
        return "List, create, or delete tags";
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "{action} tag {name}";
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"action", TYPE_STRING, "Action: 'list' (default), 'create', 'delete'"},
            {"name", TYPE_STRING, "Tag name (required for create/delete)"},
            {"commit", TYPE_STRING, "Commit to tag (default: HEAD, for create)"},
            {"message", TYPE_STRING, "Tag message (for annotated tags)"},
            {"annotate", TYPE_BOOLEAN, "Create an annotated tag (requires message)"},
            {"pattern", TYPE_STRING, "Glob pattern to filter tags (for list)"},
            {"sort", TYPE_STRING, "Sort field for list (e.g., '-creatordate' for newest first)"}
        });
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        String action = args.has("action")
            ? args.get("action").getAsString()
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

                yield git.runGit(cmdArgs.toArray(String[]::new));
            }
            case "create" -> {
                String name = requireName(args);
                if (name == null) yield "Error: 'name' parameter is required for 'create'";

                List<String> cmdArgs = new ArrayList<>();
                cmdArgs.add("tag");

                if (args.has("annotate") && args.get("annotate").getAsBoolean()) {
                    cmdArgs.add("-a");
                }

                cmdArgs.add(name);

                if (args.has(PARAM_COMMIT) && !args.get(PARAM_COMMIT).getAsString().isEmpty()) {
                    cmdArgs.add(args.get(PARAM_COMMIT).getAsString());
                }

                if (args.has(PARAM_MESSAGE) && !args.get(PARAM_MESSAGE).getAsString().isEmpty()) {
                    cmdArgs.add("-m");
                    cmdArgs.add(args.get(PARAM_MESSAGE).getAsString());
                }

                yield git.runGit(cmdArgs.toArray(String[]::new));
            }
            case "delete" -> {
                String name = requireName(args);
                if (name == null) yield "Error: 'name' parameter is required for 'delete'";
                yield git.runGit("tag", "-d", name);
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
}
