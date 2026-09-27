package com.github.catatafishen.agentbridge.psi.tools.git;

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

        int offset = args.has(PARAM_OFFSET) ? args.get(PARAM_OFFSET).getAsInt() : 0;
        if (offset < 0) return "Error: offset must be zero or greater.";
        int maxChars = args.has(PARAM_MAX_CHARS) ? args.get(PARAM_MAX_CHARS).getAsInt() : MAX_PAGE_CHARS;
        if (maxChars <= 0 || maxChars > MAX_PAGE_CHARS) {
            return "Error: max_chars must be between 1 and " + MAX_PAGE_CHARS + ".";
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
        return paginate(result, offset, maxChars);
    }

    static @NotNull String paginate(@NotNull String text, int offset, int maxChars) {
        if (text.isEmpty()) return text;
        int totalLength = text.length();
        if (offset >= totalLength) {
            return "No git_show output at offset " + offset
                + " (total length: " + totalLength + " characters).";
        }

        int end = (int) Math.min(totalLength, (long) offset + maxChars);
        if (offset == 0 && end == totalLength) return text;

        StringBuilder page = new StringBuilder(maxChars + 160);
        if (offset > 0) {
            page.append("[Showing characters ").append(offset).append('-').append(end)
                .append(" of ").append(totalLength).append("]\n\n");
        }
        page.append(text, offset, end);
        if (end < totalLength) {
            page.append("\n\n[Output paginated: showing characters ").append(offset).append('-').append(end)
                .append(" of ").append(totalLength).append(". Use offset=").append(end)
                .append(" to continue.]");
        }
        return page.toString();
    }

    @Override
    public @NotNull Object resultRenderer() {
        return GitShowRenderer.INSTANCE;
    }
}
