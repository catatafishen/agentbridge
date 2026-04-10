package com.github.catatafishen.agentbridge.psi.tools.git;

import com.github.catatafishen.agentbridge.ui.renderers.GitStashRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("java:S112")
public final class GitStashTool extends GitTool {

    private static final String CMD_STASH = "stash";
    private static final String PARAM_ACTION = "action";
    private static final String PARAM_MESSAGE = "message";
    private static final String PARAM_INDEX = "index";
    private static final String PARAM_INCLUDE_UNTRACKED = "include_untracked";
    private static final String ACTION_APPLY = "apply";

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
        return "Push, pop, apply, list, or drop stashed changes. Stash saves uncommitted work temporarily without committing. " +
            "Use include_untracked: true to also stash new files.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "{action} stash";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.optional(PARAM_ACTION, TYPE_STRING, "Action: 'list' (default), 'push', 'pop', 'apply', 'drop'"),
            Param.optional(PARAM_MESSAGE, TYPE_STRING, "Stash message (for push action)"),
            Param.optional(PARAM_INDEX, TYPE_STRING, "Stash index (for pop/apply/drop, e.g., 'stash@{0}')"),
            Param.optional(PARAM_INCLUDE_UNTRACKED, TYPE_BOOLEAN, "For push: include untracked files")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String action = args.has(PARAM_ACTION)
            ? args.get(PARAM_ACTION).getAsString()
            : "list";

        return switch (action) {
            case "list" -> runGit(CMD_STASH, "list");
            case "push", "save" -> {
                List<String> cmdArgs = new ArrayList<>();
                cmdArgs.add(CMD_STASH);
                cmdArgs.add("push");

                if (args.has(PARAM_MESSAGE) && !args.get(PARAM_MESSAGE).getAsString().isEmpty()) {
                    cmdArgs.add("-m");
                    cmdArgs.add(args.get(PARAM_MESSAGE).getAsString());
                }

                if (args.has(PARAM_INCLUDE_UNTRACKED) && args.get(PARAM_INCLUDE_UNTRACKED).getAsBoolean()) {
                    cmdArgs.add("--include-untracked");
                }

                yield runGit(cmdArgs.toArray(String[]::new));
            }
            case "pop" -> {
                String index = stashRef(args);
                yield index != null
                    ? runGit(CMD_STASH, "pop", index)
                    : runGit(CMD_STASH, "pop");
            }
            case ACTION_APPLY -> {
                String index = stashRef(args);
                yield index != null
                    ? runGit(CMD_STASH, ACTION_APPLY, index)
                    : runGit(CMD_STASH, ACTION_APPLY);
            }
            case "drop" -> {
                String index = stashRef(args);
                yield index != null
                    ? runGit(CMD_STASH, "drop", index)
                    : runGit(CMD_STASH, "drop");
            }
            default -> "Error: unknown action '" + action + "'. Use: list, push, pop, apply, drop";
        };
    }

    private static @Nullable String stashRef(JsonObject args) {
        if (args.has(PARAM_INDEX) && !args.get(PARAM_INDEX).getAsString().isEmpty()) {
            String idx = args.get(PARAM_INDEX).getAsString();
            // Accept both "0" (numeric index) and "stash@{0}" (full ref) without double-wrapping.
            return idx.startsWith("stash@{") ? idx : "stash@{" + idx + "}";
        }
        return null;
    }

    @Override
    public @NotNull Object resultRenderer() {
        return GitStashRenderer.INSTANCE;
    }
}
