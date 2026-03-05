package com.github.catatafishen.ideagentforcopilot.psi;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Handles all git-related tool calls for the PSI Bridge.
 */
@SuppressWarnings("java:S112") // generic exceptions are caught at the JSON-RPC dispatch level
final class GitToolHandler {
    private static final String ERROR_PATH_REQUIRED = "Error: 'path' parameter is required";

    private static final String PARAM_COMMIT = "commit";
    private static final String PARAM_STAT_ONLY = "stat_only";
    private static final String PARAM_BRANCH = "branch";
    private static final String PARAM_MESSAGE = "message";
    private static final String GIT_FLAG_ALL = "--all";

    private static final String JSON_PATHS = "paths";
    private static final String JSON_ACTION = "action";
    private static final String JSON_STASH = "stash";
    private static final String JSON_INDEX = "index";
    private static final String JSON_STASH_PREFIX = "stash@{";
    private static final String JSON_APPLY = "apply";

    private static final String STATUS_PARAM = "status";

    /**
     * Git subcommands that modify the repository and require a VCS refresh.
     */
    private static final Set<String> WRITE_COMMANDS = Set.of(
        "add", "branch", "checkout", "cherry-pick", "commit", "fetch", "merge",
        "pull", "push", "rebase", "remote", "reset", "restore",
        "revert", "stash", "switch", "tag"
    );

    private final Project project;
    private final FileTools fileTools;

    GitToolHandler(Project project, FileTools fileTools) {
        this.project = project;
        this.fileTools = fileTools;
    }

    /**
     * Run a git command, preferring IntelliJ's Git4Idea infrastructure for proper VCS integration.
     * Falls back to ProcessBuilder if Git4Idea is unavailable or the command is not mapped.
     */
    private String runGit(String... args) throws Exception {
        if (args.length == 0) return "Error: no git command";

        String result;
        try {
            result = IdeGitSupport.run(project, args);
            if (result == null) {
                result = runGitProcess(args);
            }
        } catch (NoClassDefFoundError e) {
            // Git4Idea plugin not available — fall back to ProcessBuilder
            result = runGitProcess(args);
        }

        if (WRITE_COMMANDS.contains(args[0])) {
            refreshVcsState();
        }

        return result;
    }

    /**
     * Fallback: run git via ProcessBuilder (bypasses IntelliJ VCS layer).
     */
    private String runGitProcess(String... args) throws Exception {
        String basePath = project.getBasePath();
        if (basePath == null) return "Error: no project base path";

        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("--no-pager");
        cmd.addAll(Arrays.asList(args));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new java.io.File(basePath));
        pb.redirectErrorStream(false);
        Process p = pb.start();

        String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

        boolean finished = p.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            return "Error: git command timed out";
        }

        if (p.exitValue() != 0) {
            return "Error (exit " + p.exitValue() + "): " + stderr.trim();
        }
        return stdout;
    }

    /**
     * Refresh IntelliJ's VCS state after git write operations so the Git tool window
     * (Changes tab, Log tab, branch indicator) updates in real time.
     */
    private void refreshVcsState() {
        String basePath = project.getBasePath();
        if (basePath == null) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            var root = LocalFileSystem.getInstance().findFileByPath(basePath);
            if (root != null) {
                VfsUtil.markDirtyAndRefresh(true, true, true, root);
            }
            VcsDirtyScopeManager.getInstance(project).markEverythingDirty();
        });
    }

    /**
     * Flush all IntelliJ editor buffers to disk so git sees current content.
     * Commits any pending PSI changes first to catch async reformats from external tools.
     */
    private void saveAllDocuments() {
        EdtUtil.invokeAndWait(() ->
            ApplicationManager.getApplication().runWriteAction(() -> {
                com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments();
                FileDocumentManager.getInstance().saveAllDocuments();
            }));
    }

    String gitStatus(JsonObject args) throws Exception {
        fileTools.flushPendingAutoFormat();
        saveAllDocuments();
        boolean verbose = args.has("verbose") && args.get("verbose").getAsBoolean();
        if (verbose) {
            return runGit(STATUS_PARAM);
        }
        return runGit(STATUS_PARAM, "--short", "--branch");
    }

    String gitDiff(JsonObject args) throws Exception {
        fileTools.flushPendingAutoFormat();
        saveAllDocuments();
        List<String> gitArgs = new ArrayList<>();
        gitArgs.add("diff");

        if (args.has("staged") && args.get("staged").getAsBoolean()) {
            gitArgs.add("--cached");
        }
        if (args.has(PARAM_COMMIT)) {
            gitArgs.add(args.get(PARAM_COMMIT).getAsString());
        }
        if (args.has("path")) {
            gitArgs.add("--");
            gitArgs.add(args.get("path").getAsString());
        }
        if (args.has(PARAM_STAT_ONLY) && args.get(PARAM_STAT_ONLY).getAsBoolean()) {
            gitArgs.add(1, "--stat");
        }
        return runGit(gitArgs.toArray(new String[0]));
    }

    String gitLog(JsonObject args) throws Exception {
        List<String> gitArgs = new ArrayList<>();
        gitArgs.add("log");

        int maxCount = args.has("max_count") ? args.get("max_count").getAsInt() : 20;
        gitArgs.add("-" + maxCount);

        String format = args.has("format") ? args.get("format").getAsString() : "medium";
        switch (format) {
            case "oneline" -> gitArgs.add("--oneline");
            case "short" -> gitArgs.add("--format=%h %s (%an, %ar)");
            case "full" -> gitArgs.add("--format=commit %H%nAuthor: %an <%ae>%nDate:   %ad%n%n    %s%n%n%b");
            default -> {
                // "medium" is git default - no flag needed
            }
        }

        if (args.has("author")) {
            gitArgs.add("--author=" + args.get("author").getAsString());
        }
        if (args.has("since")) {
            gitArgs.add("--since=" + args.get("since").getAsString());
        }
        if (args.has("path")) {
            gitArgs.add("--");
            gitArgs.add(args.get("path").getAsString());
        }
        if (args.has(PARAM_BRANCH)) {
            gitArgs.add(2, args.get(PARAM_BRANCH).getAsString());
        }
        String result = runGit(gitArgs.toArray(new String[0]));
        showFirstCommitInLog(result);
        return result;
    }

    String gitBlame(JsonObject args) throws Exception {
        if (!args.has("path")) return ERROR_PATH_REQUIRED;

        List<String> gitArgs = new ArrayList<>();
        gitArgs.add("blame");

        if (args.has("line_start") && args.has("line_end")) {
            gitArgs.add("-L");
            gitArgs.add(args.get("line_start").getAsInt() + "," + args.get("line_end").getAsInt());
        }

        gitArgs.add(args.get("path").getAsString());
        return runGit(gitArgs.toArray(new String[0]));
    }

    String gitCommit(JsonObject args) throws Exception {
        // Flush pending auto-format and save all documents before committing
        fileTools.flushPendingAutoFormat();
        saveAllDocuments();

        if (!args.has(PARAM_MESSAGE)) return "Error: 'message' parameter is required";

        List<String> gitArgs = new ArrayList<>();
        gitArgs.add(PARAM_COMMIT);

        if (args.has("amend") && args.get("amend").getAsBoolean()) {
            gitArgs.add("--amend");
        }
        if (args.has("all") && args.get("all").getAsBoolean()) {
            gitArgs.add(GIT_FLAG_ALL);
        }

        gitArgs.add("-m");
        gitArgs.add(args.get(PARAM_MESSAGE).getAsString());

        String result = runGit(gitArgs.toArray(new String[0]));
        showNewCommitInLog();
        return result;
    }

    String gitStage(JsonObject args) throws Exception {
        // Flush pending auto-format and save all documents so staged files include formatting
        fileTools.flushPendingAutoFormat();
        saveAllDocuments();

        List<String> gitArgs = new ArrayList<>();
        gitArgs.add("add");

        boolean stageAll = false;
        List<String> stagedPaths = new ArrayList<>();

        if (args.has("all") && args.get("all").getAsBoolean()) {
            gitArgs.add(GIT_FLAG_ALL);
            stageAll = true;
        } else if (args.has(JSON_PATHS)) {
            for (var elem : args.getAsJsonArray(JSON_PATHS)) {
                String p = elem.getAsString();
                gitArgs.add(p);
                stagedPaths.add(p);
            }
        } else if (args.has("path")) {
            String p = args.get("path").getAsString();
            gitArgs.add(p);
            stagedPaths.add(p);
        } else {
            return "Error: 'path', 'paths', or 'all' parameter is required";
        }

        String result = runGit(gitArgs.toArray(new String[0]));

        // On success, git add produces no output — provide descriptive result
        if (result.isBlank()) {
            if (stageAll) {
                String cached = runGit("diff", "--cached", "--name-status");
                if (cached.isBlank()) return "✓ Nothing to stage";
                return "✓ Staged all changes:\n" + cached.trim();
            }
            var sb = new StringBuilder("✓ Staged ");
            sb.append(stagedPaths.size()).append(stagedPaths.size() == 1 ? " file" : " files").append(":\n");
            for (String p : stagedPaths) sb.append(p).append('\n');
            return sb.toString().trim();
        }
        return result;
    }

    String gitUnstage(JsonObject args) throws Exception {
        List<String> gitArgs = new ArrayList<>();
        gitArgs.add("restore");
        gitArgs.add("--staged");

        if (args.has(JSON_PATHS)) {
            for (var elem : args.getAsJsonArray(JSON_PATHS)) {
                gitArgs.add(elem.getAsString());
            }
        } else if (args.has("path")) {
            gitArgs.add(args.get("path").getAsString());
        } else {
            return "Error: 'path' or 'paths' parameter is required";
        }

        return runGit(gitArgs.toArray(new String[0]));
    }

    String gitBranch(JsonObject args) throws Exception {
        String action = args.has(JSON_ACTION) ? args.get(JSON_ACTION).getAsString() : "list";

        return switch (action) {
            case "list" -> {
                boolean all = args.has("all") && args.get("all").getAsBoolean();
                yield runGit(PARAM_BRANCH, all ? GIT_FLAG_ALL : "--list", "-v");
            }
            case "create" -> {
                if (!args.has("name")) yield "Error: 'name' required for create";
                String base = args.has("base") ? args.get("base").getAsString() : "HEAD";
                yield runGit(PARAM_BRANCH, args.get("name").getAsString(), base);
            }
            case "switch", "checkout" -> {
                if (!args.has("name")) yield "Error: 'name' required for switch";
                yield runGit("switch", args.get("name").getAsString());
            }
            case "delete" -> {
                if (!args.has("name")) yield "Error: 'name' required for delete";
                boolean force = args.has("force") && args.get("force").getAsBoolean();
                yield runGit(PARAM_BRANCH, force ? "-D" : "-d", args.get("name").getAsString());
            }
            default -> "Error: unknown action '" + action + "'. Use: list, create, switch, delete";
        };
    }

    String gitStash(JsonObject args) throws Exception {
        String action = args.has(JSON_ACTION) ? args.get(JSON_ACTION).getAsString() : "list";

        return switch (action) {
            case "list" -> runGit(JSON_STASH, "list");
            case "push", "save" -> {
                List<String> gitArgs = new ArrayList<>(List.of(JSON_STASH, "push"));
                if (args.has(PARAM_MESSAGE)) {
                    gitArgs.add("-m");
                    gitArgs.add(args.get(PARAM_MESSAGE).getAsString());
                }
                if (args.has("include_untracked") && args.get("include_untracked").getAsBoolean()) {
                    gitArgs.add("--include-untracked");
                }
                yield runGit(gitArgs.toArray(new String[0]));
            }
            case "pop" -> {
                String index = args.has(JSON_INDEX) ? args.get(JSON_INDEX).getAsString() : "";
                yield index.isEmpty() ? runGit(JSON_STASH, "pop") : runGit(JSON_STASH, "pop", JSON_STASH_PREFIX + index + "}");
            }
            case JSON_APPLY -> {
                String index = args.has(JSON_INDEX) ? args.get(JSON_INDEX).getAsString() : "";
                yield index.isEmpty() ? runGit(JSON_STASH, JSON_APPLY) : runGit(JSON_STASH, JSON_APPLY, JSON_STASH_PREFIX + index + "}");
            }
            case "drop" -> {
                String index = args.has(JSON_INDEX) ? args.get(JSON_INDEX).getAsString() : "";
                yield index.isEmpty() ? runGit(JSON_STASH, "drop") : runGit(JSON_STASH, "drop", JSON_STASH_PREFIX + index + "}");
            }
            default -> "Error: unknown stash action '" + action + "'. Use: list, push, pop, apply, drop";
        };
    }

    String gitPush(JsonObject args) throws Exception {
        List<String> gitArgs = new ArrayList<>();
        gitArgs.add("push");

        boolean setUpstream = args.has("set_upstream") && args.get("set_upstream").getAsBoolean();
        if (args.has("force") && args.get("force").getAsBoolean()) {
            gitArgs.add("--force");
        }
        if (setUpstream) {
            gitArgs.add("--set-upstream");
        }

        String remote = args.has("remote") ? args.get("remote").getAsString() : null;
        String branch = args.has(PARAM_BRANCH) ? args.get(PARAM_BRANCH).getAsString() : null;

        // When --set-upstream is used, git requires explicit remote and branch
        if (setUpstream) {
            if (remote == null) {
                remote = "origin";
            }
            if (branch == null) {
                branch = runGit("rev-parse", "--abbrev-ref", "HEAD").trim();
            }
        }

        if (remote != null) {
            gitArgs.add(remote);
        }
        if (branch != null) {
            gitArgs.add(branch);
        }
        if (args.has("tags") && args.get("tags").getAsBoolean()) {
            gitArgs.add("--tags");
        }

        return runGit(gitArgs.toArray(new String[0]));
    }

    String gitRemote(JsonObject args) throws Exception {
        String action = args.has(JSON_ACTION) ? args.get(JSON_ACTION).getAsString() : "list";

        return switch (action) {
            case "list" -> runGit("remote", "-v");
            case "add" -> {
                if (!args.has("name")) yield "Error: 'name' required for add";
                if (!args.has("url")) yield "Error: 'url' required for add";
                yield runGit("remote", "add", args.get("name").getAsString(), args.get("url").getAsString());
            }
            case "remove" -> {
                if (!args.has("name")) yield "Error: 'name' required for remove";
                yield runGit("remote", "remove", args.get("name").getAsString());
            }
            case "set_url", "set-url" -> {
                if (!args.has("name")) yield "Error: 'name' required for set_url";
                if (!args.has("url")) yield "Error: 'url' required for set_url";
                yield runGit("remote", "set-url", args.get("name").getAsString(), args.get("url").getAsString());
            }
            case "get_url", "get-url" -> {
                if (!args.has("name")) yield "Error: 'name' required for get_url";
                yield runGit("remote", "get-url", args.get("name").getAsString());
            }
            default -> "Error: unknown action '" + action + "'. Use: list, add, remove, set_url, get_url";
        };
    }

    String gitRevert(JsonObject args) throws Exception {
        if (!args.has(PARAM_COMMIT)) {
            return "Error: 'commit' parameter is required";
        }
        List<String> gitArgs = new ArrayList<>(List.of("revert"));
        if (args.has("no_commit") && args.get("no_commit").getAsBoolean()) {
            gitArgs.add("--no-commit");
        }
        if (args.has("no_edit") && args.get("no_edit").getAsBoolean()) {
            gitArgs.add("--no-edit");
        }
        gitArgs.add(args.get(PARAM_COMMIT).getAsString());
        return runGit(gitArgs.toArray(new String[0]));
    }

    String gitFetch(JsonObject args) throws Exception {
        List<String> gitArgs = new ArrayList<>();
        gitArgs.add("fetch");

        if (args.has("prune") && args.get("prune").getAsBoolean()) {
            gitArgs.add("--prune");
        }
        if (args.has("tags") && args.get("tags").getAsBoolean()) {
            gitArgs.add("--tags");
        }
        if (args.has("remote")) {
            gitArgs.add(args.get("remote").getAsString());
        }
        if (args.has(PARAM_BRANCH)) {
            gitArgs.add(args.get(PARAM_BRANCH).getAsString());
        }

        String result = runGit(gitArgs.toArray(new String[0]));
        return result.isEmpty() ? "Fetch completed successfully." : result;
    }

    String gitPull(JsonObject args) throws Exception {
        saveAllDocuments();
        List<String> gitArgs = new ArrayList<>();
        gitArgs.add("pull");

        if (args.has("rebase") && args.get("rebase").getAsBoolean()) {
            gitArgs.add("--rebase");
        }
        if (args.has("ff_only") && args.get("ff_only").getAsBoolean()) {
            gitArgs.add("--ff-only");
        }
        if (args.has("remote")) {
            gitArgs.add(args.get("remote").getAsString());
        }
        if (args.has(PARAM_BRANCH)) {
            gitArgs.add(args.get(PARAM_BRANCH).getAsString());
        }

        return runGit(gitArgs.toArray(new String[0]));
    }

    String gitMerge(JsonObject args) throws Exception {
        if (!args.has(PARAM_BRANCH) && !args.has("abort")) {
            return "Error: 'branch' parameter is required (or 'abort' to abort a merge)";
        }

        saveAllDocuments();
        List<String> gitArgs = new ArrayList<>();
        gitArgs.add("merge");

        if (args.has("abort") && args.get("abort").getAsBoolean()) {
            gitArgs.add("--abort");
            return runGit(gitArgs.toArray(new String[0]));
        }

        if (args.has("no_ff") && args.get("no_ff").getAsBoolean()) {
            gitArgs.add("--no-ff");
        }
        if (args.has("ff_only") && args.get("ff_only").getAsBoolean()) {
            gitArgs.add("--ff-only");
        }
        if (args.has("squash") && args.get("squash").getAsBoolean()) {
            gitArgs.add("--squash");
        }
        if (args.has(PARAM_MESSAGE)) {
            gitArgs.add("-m");
            gitArgs.add(args.get(PARAM_MESSAGE).getAsString());
        }
        gitArgs.add(args.get(PARAM_BRANCH).getAsString());

        return runGit(gitArgs.toArray(new String[0]));
    }

    String gitRebase(JsonObject args) throws Exception {
        saveAllDocuments();
        List<String> gitArgs = new ArrayList<>();
        gitArgs.add("rebase");

        // Handle abort/continue/skip first
        if (args.has("abort") && args.get("abort").getAsBoolean()) {
            gitArgs.add("--abort");
            return runGit(gitArgs.toArray(new String[0]));
        }
        if (args.has("continue_rebase") && args.get("continue_rebase").getAsBoolean()) {
            gitArgs.add("--continue");
            return runGit(gitArgs.toArray(new String[0]));
        }
        if (args.has("skip") && args.get("skip").getAsBoolean()) {
            gitArgs.add("--skip");
            return runGit(gitArgs.toArray(new String[0]));
        }

        if (args.has("interactive") && args.get("interactive").getAsBoolean()) {
            gitArgs.add("--interactive");
            // Set GIT_SEQUENCE_EDITOR to 'true' (no-op) for autosquash
            if (args.has("autosquash") && args.get("autosquash").getAsBoolean()) {
                gitArgs.add("--autosquash");
            }
        }

        if (args.has("onto")) {
            gitArgs.add("--onto");
            gitArgs.add(args.get("onto").getAsString());
        }

        if (args.has(PARAM_BRANCH)) {
            gitArgs.add(args.get(PARAM_BRANCH).getAsString());
        }

        return runGit(gitArgs.toArray(new String[0]));
    }

    String gitCherryPick(JsonObject args) throws Exception {
        saveAllDocuments();
        List<String> gitArgs = new ArrayList<>();
        gitArgs.add("cherry-pick");

        if (args.has("abort") && args.get("abort").getAsBoolean()) {
            gitArgs.add("--abort");
            return runGit(gitArgs.toArray(new String[0]));
        }
        if (args.has("continue_pick") && args.get("continue_pick").getAsBoolean()) {
            gitArgs.add("--continue");
            return runGit(gitArgs.toArray(new String[0]));
        }

        if (args.has("no_commit") && args.get("no_commit").getAsBoolean()) {
            gitArgs.add("--no-commit");
        }

        if (!args.has("commits")) {
            return "Error: 'commits' parameter is required (one or more commit SHAs)";
        }

        var commits = args.getAsJsonArray("commits");
        for (var c : commits) {
            gitArgs.add(c.getAsString());
        }

        return runGit(gitArgs.toArray(new String[0]));
    }

    String gitTag(JsonObject args) throws Exception {
        String action = args.has(JSON_ACTION) ? args.get(JSON_ACTION).getAsString() : "list";

        return switch (action) {
            case "list" -> {
                List<String> gitArgs = new ArrayList<>(List.of("tag", "-l"));
                if (args.has("pattern")) {
                    gitArgs.add(args.get("pattern").getAsString());
                }
                if (args.has("sort")) {
                    gitArgs.add("--sort=" + args.get("sort").getAsString());
                }
                yield runGit(gitArgs.toArray(new String[0]));
            }
            case "create" -> {
                if (!args.has("name")) yield "Error: 'name' required for create";
                List<String> gitArgs = new ArrayList<>(List.of("tag"));
                if (args.has("annotate") && args.get("annotate").getAsBoolean()) {
                    gitArgs.add("-a");
                }
                gitArgs.add(args.get("name").getAsString());
                if (args.has(PARAM_COMMIT)) {
                    gitArgs.add(args.get(PARAM_COMMIT).getAsString());
                }
                if (args.has(PARAM_MESSAGE)) {
                    gitArgs.add("-m");
                    gitArgs.add(args.get(PARAM_MESSAGE).getAsString());
                }
                yield runGit(gitArgs.toArray(new String[0]));
            }
            case "delete" -> {
                if (!args.has("name")) yield "Error: 'name' required for delete";
                yield runGit("tag", "-d", args.get("name").getAsString());
            }
            default -> "Error: unknown action '" + action + "'. Use: list, create, delete";
        };
    }

    String gitReset(JsonObject args) throws Exception {
        saveAllDocuments();
        List<String> gitArgs = new ArrayList<>();
        gitArgs.add("reset");

        String mode = args.has("mode") ? args.get("mode").getAsString() : "mixed";
        switch (mode) {
            case "soft" -> gitArgs.add("--soft");
            case "hard" -> gitArgs.add("--hard");
            case "mixed" -> gitArgs.add("--mixed");
            default -> {
                return "Error: unknown mode '" + mode + "'. Use: soft, mixed, hard";
            }
        }

        if (args.has(PARAM_COMMIT)) {
            gitArgs.add(args.get(PARAM_COMMIT).getAsString());
        }

        // If specific paths given, reset those files only (removes from staging)
        if (args.has("path")) {
            gitArgs.clear();
            gitArgs.add("reset");
            gitArgs.add("--");
            gitArgs.add(args.get("path").getAsString());
        }

        String result = runGit(gitArgs.toArray(new String[0]));
        return result.isEmpty() ? "Reset completed successfully." : result;
    }

    String gitShow(JsonObject args) throws Exception {
        List<String> gitArgs = new ArrayList<>();
        gitArgs.add("show");

        String ref = args.has("ref") ? args.get("ref").getAsString() : "HEAD";
        gitArgs.add(ref);

        if (args.has(PARAM_STAT_ONLY) && args.get(PARAM_STAT_ONLY).getAsBoolean()) {
            gitArgs.add("--stat");
        }
        if (args.has("path")) {
            gitArgs.add("--");
            gitArgs.add(args.get("path").getAsString());
        }
        String result = runGit(gitArgs.toArray(new String[0]));
        showFirstCommitInLog(result);
        return result;
    }

    // ---- IDE follow-along helpers ----

    private static final java.util.regex.Pattern FULL_HASH_PATTERN =
        java.util.regex.Pattern.compile("\\b[0-9a-f]{40}\\b");
    private static final java.util.regex.Pattern COMMIT_LINE_PATTERN =
        java.util.regex.Pattern.compile("^commit ([0-9a-f]{40})$", java.util.regex.Pattern.MULTILINE);

    /**
     * Opens the Git Log tool window and navigates to the newly created commit (HEAD).
     * <p>
     * Uses {@link PlatformApiCompat#showRevisionInLogAfterRefresh} to register a
     * {@code DataPackChangeListener} and trigger a VCS log refresh. Navigation happens
     * only after the log has indexed the new commit, avoiding the
     * "Commit or reference 'xxx' not found" notification.
     */
    private void showNewCommitInLog() {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                String fullHash = runGit("rev-parse", "HEAD").trim();
                if (fullHash.length() != 40) return;

                ApplicationManager.getApplication().invokeLater(() -> {
                    // Open the Git Log tool window
                    var twm = com.intellij.openapi.wm.ToolWindowManager.getInstance(project);
                    var tw = twm.getToolWindow("Version Control");
                    if (tw != null) tw.activate(null);

                    // Navigate after VCS log refresh (avoids "not found" notification)
                    PlatformApiCompat.showRevisionInLogAfterRefresh(project, fullHash);
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // best-effort — fall back silently
            }
        });
    }

    /**
     * Extracts the first full commit hash from a git-log result and navigates
     * to it in the VCS Log tab, so the user can follow what the agent is reading.
     */
    private void showFirstCommitInLog(String gitOutput) {
        if (gitOutput == null || gitOutput.isEmpty()) return;
        // Try "commit <hash>" line first (medium/full format), then any 40-char hex
        String hash = null;
        var m = COMMIT_LINE_PATTERN.matcher(gitOutput);
        if (m.find()) {
            hash = m.group(1);
        } else {
            var m2 = FULL_HASH_PATTERN.matcher(gitOutput);
            if (m2.find()) {
                hash = m2.group();
            }
        }
        if (hash == null) return;
        String finalHash = hash;
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                PlatformApiCompat.showRevisionInLog(project, finalHash);
            } catch (Exception ignored) {
                // best-effort UI follow-along
            }
        });
    }

    /**
     * Delegates to {@link PlatformApiCompat#runIdeGitCommand} which isolates all Git4Idea API
     * calls that produce false-positive errors in the IDE editor.
     * If Git4Idea is disabled, the outer class catches {@link NoClassDefFoundError} and falls
     * back to ProcessBuilder.
     */
    private static final class IdeGitSupport {
        static String run(Project project, String[] args) {
            return PlatformApiCompat.runIdeGitCommand(project, args);
        }
    }
}
