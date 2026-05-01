package com.github.catatafishen.agentbridge.psi.tools.git;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Tool for 'git config' command.
 * Allows reading and setting git configuration options.
 */
public final class GitConfigTool extends GitTool {

    private static final String PARAM_KEY = "key";
    private static final String PARAM_VALUE = "value";
    private static final String PARAM_GLOBAL = "global";
    private static final String PARAM_UNSET = "unset";
    private static final String PARAM_LIST = "list";

    public GitConfigTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "git_config";
    }

    @Override
    public @NotNull String displayName() {
        return "Git Config";
    }

    @Override
    public @NotNull String description() {
        return "Get or set git configuration options. Use list: true to see all config. " +
            "Use global: true for user-level settings, omit for repository-level. Returns the current value when getting.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.WRITE; // Can be both, but setting is WRITE
    }

    @Override
    public boolean isReadOnly() {
        return false; // Can modify state
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.optional(PARAM_KEY, TYPE_STRING, "The configuration key (e.g. 'user.email')"),
            Param.optional(PARAM_VALUE, TYPE_STRING, "The value to set. If omitted and unset/list are false, performs a get operation."),
            Param.optional(PARAM_GLOBAL, TYPE_BOOLEAN, "If true, uses --global flag"),
            Param.optional(PARAM_UNSET, TYPE_BOOLEAN, "If true, unsets the given key"),
            Param.optional(PARAM_LIST, TYPE_BOOLEAN, "If true, lists all configuration options"),
            Param.optional(PARAM_REPO, TYPE_STRING, REPO_PARAM_DESCRIPTION)
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String repoParam = args.has(PARAM_REPO) ? args.get(PARAM_REPO).getAsString() : null;
        if (isList(args)) {
            return runConfigCommand(repoParam, baseConfigArgs(args, "--list"));
        }
        if (!args.has(PARAM_KEY)) {
            return "Error: 'key' is required for get/set/unset operations";
        }

        String key = args.get(PARAM_KEY).getAsString();
        String ambiError = validateRepoScopedWrite(args, repoParam);
        if (ambiError != null) return ambiError;
        return runConfigCommand(repoParam, configCommandArgs(args, key));
    }

    private String runConfigCommand(@Nullable String repoParam, @NotNull List<String> cmdArgs) throws Exception {
        String root = resolveRepoRootOrError(repoParam);
        if (root.startsWith("Error")) return root;
        return runGitIn(root, cmdArgs.toArray(new String[0]));
    }

    private @Nullable String validateRepoScopedWrite(@NotNull JsonObject args, @Nullable String repoParam) {
        if (isGlobal(args) || !isWrite(args)) return null;
        return requireUnambiguousRepo(repoParam, "git_config");
    }

    private static List<String> configCommandArgs(@NotNull JsonObject args, @NotNull String key) {
        List<String> cmdArgs = baseConfigArgs(args);
        if (isUnset(args)) {
            cmdArgs.add("--unset");
            cmdArgs.add(key);
        } else if (args.has(PARAM_VALUE)) {
            cmdArgs.add(key);
            cmdArgs.add(args.get(PARAM_VALUE).getAsString());
        } else {
            cmdArgs.add(key);
        }
        return cmdArgs;
    }

    private static List<String> baseConfigArgs(@NotNull JsonObject args, String... extraArgs) {
        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add("config");
        if (isGlobal(args)) cmdArgs.add("--global");
        cmdArgs.addAll(List.of(extraArgs));
        return cmdArgs;
    }

    private static boolean isList(@NotNull JsonObject args) {
        return args.has(PARAM_LIST) && args.get(PARAM_LIST).getAsBoolean();
    }

    private static boolean isGlobal(@NotNull JsonObject args) {
        return args.has(PARAM_GLOBAL) && args.get(PARAM_GLOBAL).getAsBoolean();
    }

    private static boolean isUnset(@NotNull JsonObject args) {
        return args.has(PARAM_UNSET) && args.get(PARAM_UNSET).getAsBoolean();
    }

    private static boolean isWrite(@NotNull JsonObject args) {
        return isUnset(args) || args.has(PARAM_VALUE);
    }
}
