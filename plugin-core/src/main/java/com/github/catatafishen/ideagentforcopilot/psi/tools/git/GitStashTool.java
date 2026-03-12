package com.github.catatafishen.ideagentforcopilot.psi.tools.git;

import com.github.catatafishen.ideagentforcopilot.ui.renderers.GitStashRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Pushes, pops, applies, lists, or drops stashed changes.
 */
@SuppressWarnings("java:S112")
public final class GitStashTool extends GitTool {

    public GitStashTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "git_stash";
    }

    @Override
    public @NotNull String displayName() {
        return "Git Stash";
    }

    @Override
    public @NotNull String description() {
        return "Push, pop, apply, list, or drop stashed changes";
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "{action} stash";
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"action", TYPE_STRING, "Action: 'list' (default), 'push', 'pop', 'apply', 'drop'"},
            {"message", TYPE_STRING, "Stash message (for push action)"},
            {"index", TYPE_STRING, "Stash index (for pop/apply/drop, e.g., 'stash@{0}')"},
            {"include_untracked", TYPE_BOOLEAN, "For push: include untracked files"}
        });
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        String action = args.has("action")
            ? args.get("action").getAsString()
            : "list";

        return switch (action) {
            case "list" -> runGit("stash", "list");
            case "push", "save" -> {
                List<String> cmdArgs = new ArrayList<>();
                cmdArgs.add("stash");
                cmdArgs.add("push");

                if (args.has("message") && !args.get("message").getAsString().isEmpty()) {
                    cmdArgs.add("-m");
                    cmdArgs.add(args.get("message").getAsString());
                }

                if (args.has("include_untracked") && args.get("include_untracked").getAsBoolean()) {
                    cmdArgs.add("--include-untracked");
                }

                yield runGit(cmdArgs.toArray(String[]::new));
            }
            case "pop" -> {
                String index = stashRef(args);
                yield index != null
                    ? runGit("stash", "pop", index)
                    : runGit("stash", "pop");
            }
            case "apply" -> {
                String index = stashRef(args);
                yield index != null
                    ? runGit("stash", "apply", index)
                    : runGit("stash", "apply");
            }
            case "drop" -> {
                String index = stashRef(args);
                yield index != null
                    ? runGit("stash", "drop", index)
                    : runGit("stash", "drop");
            }
            default -> "Error: unknown action '" + action + "'. Use: list, push, pop, apply, drop";
        };
    }

    private static @Nullable String stashRef(JsonObject args) {
        if (args.has("index") && !args.get("index").getAsString().isEmpty()) {
            return "stash@{" + args.get("index").getAsString() + "}";
        }
        return null;
    }

    @Override
    public @NotNull Object resultRenderer() {
        return GitStashRenderer.INSTANCE;
    }
}
