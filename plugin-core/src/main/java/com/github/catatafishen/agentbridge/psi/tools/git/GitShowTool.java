package com.github.catatafishen.agentbridge.psi.tools.git;

import com.github.catatafishen.agentbridge.psi.tools.ToolResultPaginator;
import com.github.catatafishen.agentbridge.ui.renderers.GitShowRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public final class GitShowTool extends GitTool {

    private static final String PARAM_STAT_ONLY = "stat_only";
    private static final String PARAM_OFFSET = "offset";
    private static final String PARAM_MAX_CHARS = "max_chars";
    private static final int MAX_PAGE_CHARS = 12_000;

    public GitShowTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "git_show";
    }

    @Override
    public @NotNull String displayName() {
        return "Git Show";
    }

    @Override
    public @NotNull String description() {
        return "Show details and diff for a specific commit. Returns commit message, author, date, and diff content. "
            + "Large diffs are paginated; use offset to continue and max_chars to select a page size up to "
            + MAX_PAGE_CHARS + " characters. Use stat_only: true for a file-level summary without diff content. "
            + "Use git_log for commit history.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.READ;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.optional("ref", TYPE_STRING, "Commit SHA, branch, tag, or ref (default: HEAD)"),
            Param.optional(PARAM_STAT_ONLY, TYPE_BOOLEAN, "If true, show only file stats, not full diff content"),
            Param.optional("path", TYPE_STRING, "Limit output to this file path"),
            Param.optional(PARAM_OFFSET, TYPE_INTEGER, "Character offset to start from (default: 0). Use the next offset from a paginated response to continue"),
            Param.optional(PARAM_MAX_CHARS, TYPE_INTEGER, "Maximum characters to return per page (default and maximum: " + MAX_PAGE_CHARS + ")"),
            Param.optional(PARAM_REPO, TYPE_STRING, REPO_PARAM_DESCRIPTION)
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String repoParam = args.has(PARAM_REPO) ? args.get(PARAM_REPO).getAsString() : null;
        String root = resolveRepoRootOrError(repoParam);
        if (root.startsWith("Error")) return root;

        ToolResultPaginator.PageRequest pageRequest;
        try {
            pageRequest = ToolResultPaginator.parsePageRequest(args, MAX_PAGE_CHARS);
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }

        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add("show");

        String ref = args.has("ref") && !args.get("ref").getAsString().isEmpty()
            ? args.get("ref").getAsString()
            : "HEAD";
        cmdArgs.add(ref);

        boolean statOnly = args.has(PARAM_STAT_ONLY)
            && args.get(PARAM_STAT_ONLY).getAsBoolean();
        if (statOnly) {
            cmdArgs.add("--stat");
        }

        appendPathSpec(cmdArgs, args);

        String result = runGitIn(root, cmdArgs.toArray(String[]::new));
        showFirstCommitInLog(root, result);
        return paginate(result, pageRequest.offset(), pageRequest.maxChars());
    }

    static @NotNull String paginate(@NotNull String text, int offset, int maxChars) {
        return ToolResultPaginator.paginate("git_show", text, offset, maxChars);
    }

    @Override
    public @NotNull Object resultRenderer() {
        return GitShowRenderer.INSTANCE;
    }
}
