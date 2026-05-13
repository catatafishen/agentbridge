package com.github.catatafishen.agentbridge.psi.tools.git;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.github.catatafishen.agentbridge.psi.review.AgentEditSession;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import git4idea.rebase.GitInteractiveRebaseEditorHandler;
import git4idea.rebase.GitRebaseEntry;
import git4idea.rebase.GitRebaseOption;
import git4idea.rebase.GitRebaseUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Rebase current branch onto another, with optional non-interactive squash/drop/reword support.
 *
 * <p>For plain rebase (no operations), delegates to git4idea via GitLineHandler.
 * For interactive rebase with explicit operations (pick/drop/squash/fixup/reword), uses
 * a {@link GitInteractiveRebaseEditorHandler} subclass that applies the operations
 * programmatically without opening a UI dialog.
 */
@SuppressWarnings("java:S112")
public final class GitRebaseTool extends GitTool {

    private static final String CMD_REBASE = "rebase";
    private static final String PARAM_BRANCH = "branch";
    private static final String PARAM_INTERACTIVE = "interactive";
    private static final String PARAM_OPERATIONS = "operations";
    private static final String PARAM_AUTOSQUASH = "autosquash";
    private static final String PARAM_ABORT = "abort";
    private static final String PARAM_CONTINUE_REBASE = "continue_rebase";
    private static final String PARAM_EXEC = "exec";
    private static final String OP_COMMIT = "commit";
    private static final String OP_ACTION = "action";
    private static final String OP_MESSAGE = "message";
    private static final String ACTION_REWORD = "reword";
    private static final Set<String> VALID_REBASE_ACTIONS = Set.of("pick", ACTION_REWORD, "edit", "squash", "fixup", "drop");
    private static final Logger LOG = Logger.getInstance(GitRebaseTool.class);

    private record ParsedOps(Map<String, String> operations, Map<String, String> messages) {
    }

    public GitRebaseTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "git_rebase";
    }

    @Override
    public @NotNull String displayName() {
        return "Git Rebase";
    }

    @Override
    public @NotNull String description() {
        return """
            Rebase current branch onto another. Auto-fetches from origin when rebasing \
            onto a remote branch (origin/*). Returns rebase result with branch context.

            Interactive rebase (without a terminal editor): pass interactive: true and an \
            'operations' list of {commit, action} objects, where 'commit' is a non-blank \
            short SHA prefix and 'action' is one of: pick (default), drop, squash, fixup, \
            reword, edit. Commits not listed in operations keep their default 'pick' action. \
            'branch' is required when interactive is true. \
            The 'reword' action requires a 'message' field with the new commit message. \
            Example: operations: [{commit: 'abc1234', action: 'squash'}, {commit: 'def5678', action: 'drop'}, \
            {commit: 'ghi9012', action: 'reword', message: 'Better commit message'}]""";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EXECUTE;
    }

    @Override
    public boolean isDestructive() {
        return true;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Rebase onto {branch}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        JsonObject s = schema(
            Param.optional(PARAM_BRANCH, TYPE_STRING, "Branch to rebase onto"),
            Param.optional("onto", TYPE_STRING, "Rebase onto a specific commit (used with --onto)"),
            Param.optional(PARAM_INTERACTIVE, TYPE_BOOLEAN,
                "Start an interactive rebase. Requires 'operations' to apply changes programmatically."),
            Param.optional(PARAM_OPERATIONS, TYPE_ARRAY,
                "List of {commit, action} objects for interactive rebase. "
                    + "Each 'commit' is a short SHA prefix; 'action' is pick/drop/squash/fixup/reword/edit. "
                    + "Commits not listed keep their default 'pick' action. "
                    + "The 'reword' action requires a 'message' field with the new commit message."),
            Param.optional(PARAM_AUTOSQUASH, TYPE_BOOLEAN,
                "Automatically squash fixup! and squash! commits (requires interactive)"),
            Param.optional(PARAM_EXEC, TYPE_STRING,
                "Shell command to run after each rebase step (e.g. 'make test')"),
            Param.optional(PARAM_ABORT, TYPE_BOOLEAN, "Abort an in-progress rebase"),
            Param.optional(PARAM_CONTINUE_REBASE, TYPE_BOOLEAN,
                "Continue a paused rebase after resolving conflicts"),
            Param.optional("skip", TYPE_BOOLEAN, "Skip the current patch and continue rebase"),
            Param.optional(PARAM_REPO, TYPE_STRING, REPO_PARAM_DESCRIPTION)
        );
        addObjectArrayItems(s, PARAM_OPERATIONS,
            Param.required(OP_COMMIT, TYPE_STRING, "Non-blank short SHA prefix of the commit to rebase"),
            Param.required(OP_ACTION, TYPE_STRING, "Action: pick, drop, squash, fixup, reword, or edit"),
            Param.optional(OP_MESSAGE, TYPE_STRING, "New commit message (required when action is 'reword')"));
        return s;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        flushAndSave();

        String repoParam = args.has(PARAM_REPO) ? args.get(PARAM_REPO).getAsString() : null;
        String ambiError = requireUnambiguousRepo(repoParam, "git_rebase");
        if (ambiError != null) return ambiError;
        String root = resolveRepoRootOrError(repoParam);
        if (root.startsWith("Error")) return root;

        String controlResult = handleControlArgs(args, root);
        if (controlResult != null) return controlResult;

        boolean interactive = args.has(PARAM_INTERACTIVE) && args.get(PARAM_INTERACTIVE).getAsBoolean();
        if (interactive) {
            return doInteractiveRebase(args, root);
        }

        return doPlainRebase(args, root);
    }

    // ── Plain (non-interactive) rebase ───────────────────────

    private @NotNull String doPlainRebase(@NotNull JsonObject args, @NotNull String root) throws Exception {
        String branchArg = args.has(PARAM_BRANCH) ? args.get(PARAM_BRANCH).getAsString() : null;
        String ontoArg = args.has("onto") ? args.get("onto").getAsString() : null;
        String fetchNote = autoFetchForRemoteRefIn(branchArg, root);
        if (fetchNote.isEmpty()) fetchNote = autoFetchForRemoteRefIn(ontoArg, root);

        String reviewError = AgentEditSession.getInstance(project)
            .awaitReviewCompletion("git rebase");
        if (reviewError != null) return reviewError;

        String result = runGitIn(root, buildPlainRebaseArgs(args).toArray(String[]::new));
        if (result.startsWith("Error")) return fetchNote + result;

        AgentEditSession.getInstance(project).invalidateOnWorktreeChange("git rebase");
        return fetchNote + result + getBranchContextIn(root);
    }

    private @NotNull List<String> buildPlainRebaseArgs(@NotNull JsonObject args) {
        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add(CMD_REBASE);
        if (args.has(PARAM_AUTOSQUASH) && args.get(PARAM_AUTOSQUASH).getAsBoolean()) {
            cmdArgs.add("--autosquash");
        }
        if (args.has("onto") && !args.get("onto").getAsString().isEmpty()) {
            cmdArgs.add("--onto");
            cmdArgs.add(args.get("onto").getAsString());
        }
        if (args.has(PARAM_EXEC) && !args.get(PARAM_EXEC).getAsString().isEmpty()) {
            cmdArgs.add("--exec");
            cmdArgs.add(args.get(PARAM_EXEC).getAsString());
        }
        if (args.has(PARAM_BRANCH) && !args.get(PARAM_BRANCH).getAsString().isEmpty()) {
            cmdArgs.add(args.get(PARAM_BRANCH).getAsString());
        }
        return cmdArgs;
    }

    // ── Interactive rebase (programmatic, no UI dialog) ──────

    private @NotNull String doInteractiveRebase(@NotNull JsonObject args, @NotNull String root) {
        String argError = validateInteractiveArgs(args);
        if (argError != null) return argError;

        String upstream = args.get(PARAM_BRANCH).getAsString();
        String fetchNote = autoFetchForRemoteRefIn(upstream, root);

        ParsedOps parsed = parseOperations(args);
        Map<String, String> operations = parsed.operations();
        Map<String, String> messages = parsed.messages();
        String opError = validateOperations(operations, messages);
        if (opError != null) return opError;

        Future<?> future = null;
        try {
            git4idea.repo.GitRepository repo = PlatformApiCompat.getRepositoryForRoot(project, root);
            if (repo == null) {
                return "Error: repository '" + root + "' is not registered in IntelliJ's VCS roots. "
                    + "Check Settings → Version Control and confirm the directory is tracked, "
                    + "or verify that the git4idea plugin is installed.";
            }

            String reviewError = AgentEditSession.getInstance(project)
                .awaitReviewCompletion("git rebase -i");
            if (reviewError != null) return reviewError;

            AtomicReference<String> errorRef = new AtomicReference<>();
            future = ApplicationManager.getApplication().executeOnPooledThread(() ->
                executeRebaseOnPooledThread(repo, upstream, operations, messages, errorRef));

            future.get(60, TimeUnit.SECONDS);

            if (errorRef.get() != null) return "Error: " + errorRef.get();

            AgentEditSession.getInstance(project).invalidateOnWorktreeChange("git rebase -i");
            refreshVcsState();
            return fetchNote + "Interactive rebase completed" + getBranchContextIn(root);

        } catch (NoClassDefFoundError e) {
            return "Error: git4idea plugin required for interactive rebase (not available in this IDE)";
        } catch (TimeoutException e) {
            future.cancel(true);
            return "Error: interactive rebase timed out after 60 seconds. "
                + "A modal dialog may be blocking — call interact_with_modal to inspect or dismiss it, "
                + "or the rebase may still be running in background.";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: interactive rebase was interrupted";
        } catch (Exception e) {
            return "Error: " + (e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    private void executeRebaseOnPooledThread(
        @NotNull git4idea.repo.GitRepository repo,
        @NotNull String upstream,
        @NotNull Map<String, String> operations,
        @NotNull Map<String, String> messages,
        @NotNull AtomicReference<String> errorRef) {
        try {
            VirtualFile repoRoot = repo.getRoot();
            var handler = new ProgrammaticRebaseEditorHandler(project, repoRoot, operations, messages);

            git4idea.branch.GitRebaseParams params = new git4idea.branch.GitRebaseParams(
                repo.getVcs().getVersion(),
                null,
                null,
                upstream,
                Set.of(GitRebaseOption.INTERACTIVE),
                handler
            );

            boolean ok = GitRebaseUtils.rebaseWithResult(
                project, List.of(repo), params, new EmptyProgressIndicator());
            if (!ok) {
                errorRef.set("Rebase failed or conflicts remain. Resolve conflicts and use continue_rebase: true");
            }
        } catch (Exception e) {
            errorRef.set(e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    private @Nullable String validateInteractiveArgs(@NotNull JsonObject args) {
        if (args.has(PARAM_AUTOSQUASH) && args.get(PARAM_AUTOSQUASH).getAsBoolean()) {
            return "Error: 'autosquash' is not supported in programmatic interactive rebase. "
                + "Operations are applied explicitly — mark fixup!/squash! commits manually in the operations list.";
        }
        if (args.has("onto") && !args.get("onto").getAsString().isBlank()) {
            return "Error: 'onto' is not supported in interactive rebase mode. "
                + "Use plain rebase (without interactive: true) for --onto.";
        }
        if (args.has(PARAM_EXEC) && !args.get(PARAM_EXEC).getAsString().isBlank()) {
            return "Error: 'exec' is not supported in interactive rebase mode.";
        }
        String upstream = args.has(PARAM_BRANCH) ? args.get(PARAM_BRANCH).getAsString() : null;
        if (upstream == null || upstream.isBlank()) {
            return "Error: 'branch' (upstream) parameter is required for interactive rebase";
        }
        return null;
    }

    private @Nullable String validateOperations(@NotNull Map<String, String> operations, @NotNull Map<String, String> messages) {
        for (Map.Entry<String, String> entry : operations.entrySet()) {
            String action = entry.getValue();
            if (!VALID_REBASE_ACTIONS.contains(action)) {
                return "Error: invalid rebase action '" + action + "' for commit '"
                    + entry.getKey() + "'. Allowed: " + String.join(", ", VALID_REBASE_ACTIONS);
            }
            if (ACTION_REWORD.equals(action) && !messages.containsKey(entry.getKey())) {
                return "Error: 'reword' action for commit '" + entry.getKey()
                    + "' requires a 'message' field with the new commit message.";
            }
        }
        return null;
    }

    private @NotNull ParsedOps parseOperations(@NotNull JsonObject args) {
        Map<String, String> operations = new LinkedHashMap<>();
        Map<String, String> messages = new LinkedHashMap<>();
        if (!args.has(PARAM_OPERATIONS) || !args.get(PARAM_OPERATIONS).isJsonArray()) {
            return new ParsedOps(operations, messages);
        }
        for (var el : args.getAsJsonArray(PARAM_OPERATIONS)) {
            String commitKey = extractCommitKey(el);
            if (commitKey == null) continue;
            JsonObject op = el.getAsJsonObject();
            if (op.has(OP_ACTION)) {
                operations.put(commitKey, op.get(OP_ACTION).getAsString().trim().toLowerCase());
            }
            if (op.has(OP_MESSAGE)) {
                messages.put(commitKey, op.get(OP_MESSAGE).getAsString());
            }
        }
        return new ParsedOps(operations, messages);
    }

    @Nullable
    private static String extractCommitKey(@NotNull JsonElement el) {
        if (!el.isJsonObject()) return null;
        JsonObject op = el.getAsJsonObject();
        if (!op.has(OP_COMMIT)) return null;
        String key = op.get(OP_COMMIT).getAsString().trim();
        return key.isBlank() ? null : key;
    }

    // ── Control args (abort / continue / skip) ───────────────

    private @Nullable String handleControlArgs(@NotNull JsonObject args, @NotNull String root) throws Exception {
        if (args.has(PARAM_ABORT) && args.get(PARAM_ABORT).getAsBoolean()) {
            return runGitIn(root, CMD_REBASE, "--abort");
        }
        if (args.has(PARAM_CONTINUE_REBASE) && args.get(PARAM_CONTINUE_REBASE).getAsBoolean()) {
            return runGitIn(root, CMD_REBASE, "--continue");
        }
        if (args.has("skip") && args.get("skip").getAsBoolean()) {
            return runGitIn(root, CMD_REBASE, "--skip");
        }
        return null;
    }

    // ── Programmatic rebase editor ────────────────────────────

    /**
     * Applies agent-provided operations to the rebase instruction list without opening a dialog.
     *
     * <p>Extends {@link GitInteractiveRebaseEditorHandler} so git4idea owns all file I/O,
     * state management, and editor protocol. We override {@code collectNewEntries()} to apply
     * the requested actions and {@code handleUnstructuredEditor(File)} to inject commit messages
     * for {@code reword} entries without showing a dialog.
     * Commits not mentioned in {@code operationsByCommit} keep their original action ({@code pick}).
     */
    private static final class ProgrammaticRebaseEditorHandler extends GitInteractiveRebaseEditorHandler {

        /**
         * Map from short SHA prefix → action string (pick/drop/squash/fixup/reword/edit).
         */
        private final Map<String, String> operationsByCommit;

        /**
         * Map from short SHA prefix → new commit message (for reword entries).
         */
        private final Map<String, String> messagesByCommit;

        /**
         * Messages for reword entries in order, consumed one-by-one by
         * {@link #handleUnstructuredEditor(File)} as git processes each reword commit.
         */
        private final List<String> rewordMessages = new ArrayList<>();
        private final List<String> rewordShas = new ArrayList<>();
        private int rewordIndex = 0;

        ProgrammaticRebaseEditorHandler(
            @NotNull Project project,
            @NotNull VirtualFile root,
            @NotNull Map<String, String> operationsByCommit,
            @NotNull Map<String, String> messagesByCommit) {
            super(project, root);
            this.operationsByCommit = operationsByCommit;
            this.messagesByCommit = messagesByCommit;
        }

        @Override
        protected @NotNull List<? extends GitRebaseEntry> collectNewEntries(
            @NotNull List<GitRebaseEntry> entries) {

            List<GitRebaseEntry> result = new ArrayList<>(entries.size());
            for (GitRebaseEntry entry : entries) {
                String sha = entry.getCommit();
                String actionString = findOperation(sha);
                if (actionString == null) {
                    result.add(entry);
                    continue;
                }
                GitRebaseEntry.Action newAction = GitRebaseEntry.parseAction(actionString);
                result.add(new GitRebaseEntry(newAction, sha, entry.getSubject()));
                if (ACTION_REWORD.equals(actionString)) {
                    String msg = findMessage(sha);
                    if (msg != null) {
                        rewordMessages.add(msg);
                        rewordShas.add(sha);
                    }
                }
            }
            return result;
        }

        /**
         * Called by git4idea when git invokes the editor on COMMIT_EDITMSG for a {@code reword}
         * entry. Writes the next pre-supplied message to the file instead of showing a dialog.
         * Returns {@code true} (handled) to suppress the dialog.
         *
         * <p>Any existing template content in {@code file} (comment lines, git hooks-injected
         * content) is intentionally discarded — only the new message and a trailing newline are
         * written, matching the simplest valid commit message format.
         */
        @Override
        protected boolean handleUnstructuredEditor(@NotNull File file) throws IOException {
            if (rewordIndex >= rewordMessages.size()) {
                // Fallback: should not happen if validation passed, but defer to dialog rather than fail silently
                return false;
            }
            LOG.debug("Applying reword message for commit " + rewordShas.get(rewordIndex));
            Files.writeString(file.toPath(), rewordMessages.get(rewordIndex++) + "\n", StandardCharsets.UTF_8);
            return true;
        }

        @Nullable
        private String findOperation(@NotNull String sha) {
            if (operationsByCommit.containsKey(sha)) return operationsByCommit.get(sha);
            // sha is git's full commit hash; op.getKey() is the agent-provided short prefix
            for (Map.Entry<String, String> op : operationsByCommit.entrySet()) {
                if (sha.startsWith(op.getKey())) return op.getValue();
            }
            return null;
        }

        @Nullable
        private String findMessage(@NotNull String sha) {
            if (messagesByCommit.containsKey(sha)) return messagesByCommit.get(sha);
            for (Map.Entry<String, String> op : messagesByCommit.entrySet()) {
                if (sha.startsWith(op.getKey())) return op.getValue();
            }
            return null;
        }
    }
}
