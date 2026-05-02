package com.github.catatafishen.agentbridge.psi.tools.git;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.github.catatafishen.agentbridge.psi.review.AgentEditSession;
import com.github.catatafishen.agentbridge.ui.renderers.GitBranchRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@SuppressWarnings("java:S112")
public final class GitBranchTool extends GitTool {

    private static final String CMD_BRANCH = "branch";
    private static final String CMD_CHECKOUT = "checkout";
    private static final String PARAM_ACTION = "action";
    private static final String PARAM_NAME = "name";
    private static final String PARAM_BASE = "base";
    private static final String PARAM_ALL = "all";
    private static final String PARAM_FORCE = "force";
    private static final String ERR_PREFIX = "Error";

    public GitBranchTool(Project project) {
        super(project);
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
        return "List, create, switch, or delete branches. After create or switch, returns "
            + "branch context: tracking status, ahead/behind counts, and uncommitted changes.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "{action} branch {name}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.optional(PARAM_ACTION, TYPE_STRING, "Action: 'list' (default), 'create', 'switch', 'delete'"),
            Param.optional(PARAM_NAME, TYPE_STRING, "Branch name (required for create/switch/delete)"),
            Param.optional(PARAM_BASE, TYPE_STRING, "Base ref for create (default: HEAD)"),
            Param.optional(PARAM_ALL, TYPE_BOOLEAN, "For list: include remote branches"),
            Param.optional(PARAM_FORCE, TYPE_BOOLEAN, "For delete: force delete unmerged branches"),
            Param.optional(PARAM_REPO, TYPE_STRING, REPO_PARAM_DESCRIPTION)
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String action = args.has(PARAM_ACTION) ? args.get(PARAM_ACTION).getAsString() : "list";
        String repoParam = args.has(PARAM_REPO) ? args.get(PARAM_REPO).getAsString() : null;

        return switch (action) {
            case "list" -> listBranches(args, repoParam);
            case "create" -> createBranch(args, repoParam);
            case "switch", CMD_CHECKOUT -> switchBranch(args, repoParam);
            case "delete" -> deleteBranch(args, repoParam);
            default -> "Error: unknown action '" + action + "'. Use: list, create, switch, delete";
        };
    }

    private String listBranches(@NotNull JsonObject args, @Nullable String repoParam) throws Exception {
        String root = resolveRepoRootOrError(repoParam);
        if (root.startsWith(ERR_PREFIX)) return root;
        boolean all = args.has(PARAM_ALL) && args.get(PARAM_ALL).getAsBoolean();

        String refPattern = all ? "refs/heads refs/remotes" : "refs/heads";
        String format = "%(if)%(HEAD)%(then)* %(else)  %(end)"
            + "%(refname:short)|%(objectname:short)|%(committerdate:relative)|%(upstream:short)|%(upstream:track)";
        String raw = runGitIn(root, "for-each-ref", "--sort=-committerdate",
            "--format=" + format, refPattern);

        return BranchListFormatter.formatBranchTable(raw)
            + "\n\n--- Context ---\n" + getBranchContextIn(root);
    }

    private String createBranch(@NotNull JsonObject args, @Nullable String repoParam) throws Exception {
        String name = requireName(args);
        if (name == null) return "Error: 'name' parameter is required for 'create'";
        String setupError = prepareBranchWrite(repoParam, "branch create");
        if (setupError.startsWith(ERR_PREFIX)) return setupError;
        String reviewError = AgentEditSession.getInstance(project)
            .awaitReviewCompletion("branch create '" + name + "'");
        if (reviewError != null) return reviewError;

        String base = args.has(PARAM_BASE) && !args.get(PARAM_BASE).getAsString().isEmpty()
            ? args.get(PARAM_BASE).getAsString()
            : null;
        String result = ideCreate(setupError, name, base);
        if (result.startsWith(ERR_PREFIX)) return result;
        AgentEditSession.getInstance(project).invalidateOnWorktreeChange("branch create");
        return "Created and switched to branch '" + name + "'\n" + getBranchContextIn(setupError);
    }

    private String switchBranch(@NotNull JsonObject args, @Nullable String repoParam) throws Exception {
        String name = requireName(args);
        if (name == null) return "Error: 'name' parameter is required for 'switch'";
        String setupError = prepareBranchWrite(repoParam, "branch switch");
        if (setupError.startsWith(ERR_PREFIX)) return setupError;
        String reviewError = AgentEditSession.getInstance(project)
            .awaitReviewCompletion("branch switch '" + name + "'");
        if (reviewError != null) return reviewError;

        String result = ideSwitch(setupError, name);
        if (result.startsWith(ERR_PREFIX)) return result;
        AgentEditSession.getInstance(project).invalidateOnWorktreeChange("branch switch");
        return "Switched to branch '" + name + "'\n" + getBranchContextIn(setupError);
    }

    private String deleteBranch(@NotNull JsonObject args, @Nullable String repoParam) throws Exception {
        String name = requireName(args);
        if (name == null) return "Error: 'name' parameter is required for 'delete'";
        String setupError = prepareBranchWrite(repoParam, "branch delete");
        if (setupError.startsWith(ERR_PREFIX)) return setupError;
        boolean force = args.has(PARAM_FORCE) && args.get(PARAM_FORCE).getAsBoolean();
        return ideDelete(setupError, name, force);
    }

    private String prepareBranchWrite(@Nullable String repoParam, @NotNull String action) {
        String ambiError = requireUnambiguousRepo(repoParam, action);
        if (ambiError != null) return ambiError;
        return resolveRepoRootOrError(repoParam);
    }

    /**
     * Create a new branch and switch to it. Prefers Git4Idea high-level API; falls back to CLI.
     */
    private String ideCreate(String root, String name, @Nullable String base) throws Exception {
        try {
            String result = PlatformApiCompat.ideCheckoutNewBranch(project, root, name, base);
            if (result != null) {
                refreshVcsState();
                return result;
            }
        } catch (NoClassDefFoundError ignored) {
            // Git4Idea unavailable
        }
        return base != null
            ? runGitIn(root, CMD_CHECKOUT, "-b", name, base)
            : runGitIn(root, CMD_CHECKOUT, "-b", name);
    }

    /**
     * Switch to an existing branch. Prefers Git4Idea high-level API; falls back to CLI.
     */
    private String ideSwitch(String root, String name) throws Exception {
        try {
            String result = PlatformApiCompat.ideCheckout(project, root, name);
            if (result != null) {
                refreshVcsState();
                return result;
            }
        } catch (NoClassDefFoundError ignored) {
            // Git4Idea unavailable
        }
        return runGitIn(root, CMD_CHECKOUT, name);
    }

    /**
     * Delete a branch. Prefers Git4Idea high-level API; falls back to CLI.
     */
    private String ideDelete(String root, String name, boolean force) throws Exception {
        try {
            String result = PlatformApiCompat.ideDeleteBranch(project, root, name, force);
            if (result != null) {
                refreshVcsState();
                return result;
            }
        } catch (NoClassDefFoundError ignored) {
            // Git4Idea unavailable
        }
        return runGitIn(root, CMD_BRANCH, force ? "-D" : "-d", name);
    }

    private static @Nullable String requireName(JsonObject args) {
        if (!args.has(PARAM_NAME) || args.get(PARAM_NAME).getAsString().isEmpty()) {
            return null;
        }
        return args.get(PARAM_NAME).getAsString();
    }

    @Override
    public @NotNull Object resultRenderer() {
        return GitBranchRenderer.INSTANCE;
    }
}
