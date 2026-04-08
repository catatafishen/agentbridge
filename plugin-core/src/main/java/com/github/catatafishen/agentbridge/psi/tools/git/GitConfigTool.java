package com.github.catatafishen.agentbridge.psi.tools.git;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

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
        return schema(new Object[][]{
            {PARAM_KEY, TYPE_STRING, "The configuration key (e.g. 'user.email')"},
            {PARAM_VALUE, TYPE_STRING, "The value to set. If omitted and unset/list are false, performs a get operation."},
            {PARAM_GLOBAL, TYPE_BOOLEAN, "If true, uses --global flag"},
            {PARAM_UNSET, TYPE_BOOLEAN, "If true, unsets the given key"},
            {PARAM_LIST, TYPE_BOOLEAN, "If true, lists all configuration options"}
        });
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add("config");

        if (args.has(PARAM_GLOBAL) && args.get(PARAM_GLOBAL).getAsBoolean()) {
            cmdArgs.add("--global");
        }

        if (args.has(PARAM_LIST) && args.get(PARAM_LIST).getAsBoolean()) {
            cmdArgs.add("--list");
            return runGit(cmdArgs.toArray(new String[0]));
        }

        if (!args.has(PARAM_KEY)) {
            return "Error: 'key' is required for get/set/unset operations";
        }

        String key = args.get(PARAM_KEY).getAsString();

        if (args.has(PARAM_UNSET) && args.get(PARAM_UNSET).getAsBoolean()) {
            cmdArgs.add("--unset");
            cmdArgs.add(key);
        } else if (args.has(PARAM_VALUE)) {
            cmdArgs.add(key);
            cmdArgs.add(args.get(PARAM_VALUE).getAsString());
        } else {
            // Get operation
            cmdArgs.add(key);
        }

        return runGit(cmdArgs.toArray(new String[0]));
    }
}
