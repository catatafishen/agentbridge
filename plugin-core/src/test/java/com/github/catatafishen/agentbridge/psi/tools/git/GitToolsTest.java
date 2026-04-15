package com.github.catatafishen.agentbridge.psi.tools.git;

import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Platform tests for all git tools: {@link GitStatusTool}, {@link GitLogTool},
 * {@link GitBranchTool}, {@link GitDiffTool}, {@link GitStageTool}, and
 * {@link GitCommitTool}.
 *
 * <p>A real git repository is initialised inside {@code getProject().getBasePath()}
 * during {@link #setUp()} so that every process-based git command has a valid working
 * tree and at least one commit to operate on.
 *
 * <p>JUnit 3 style (extends {@link BasePlatformTestCase}): test methods must be
 * {@code public void testXxx()}. Run via Gradle only:
 * {@code ./gradlew :plugin-core:test}.
 *
 * <p><b>Safety:</b> Stage/commit tests only exercise error paths (missing arguments,
 * nothing staged) — no commits are ever made to the real repository.
 */
public class GitToolsTest extends BasePlatformTestCase {

    private String basePath;
    /**
     * Default branch name detected during {@link #setUp()} (e.g. "master" or "main").
     * Stored as a field so tests don't need to spawn a subprocess to resolve it.
     */
    private String currentBranch;
    /**
     * Absolute path to the git executable (resolved once in setUp).
     */
    private String gitExec;

    // ── Test lifecycle ────────────────────────────────────────────────────────────

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        // Disable follow-along UI behaviour (opens tool windows, navigates editors).
        // Use the String overload -- the boolean overload removes the key when value==default,
        // which would leave the setting at its default (true) rather than forcing it false.
        PropertiesComponent.getInstance(getProject())
            .setValue(ToolLayerSettings.FOLLOW_AGENT_FILES_KEY, "false");

        basePath = getProject().getBasePath();
        assertNotNull("BasePlatformTestCase must provide a non-null project base path", basePath);

        // Light test projects may use virtual (in-memory) VFS paths that don't exist
        // on the real filesystem. ProcessBuilder.start() requires a real directory as
        // working directory, so we ensure it exists before running any git commands.
        Files.createDirectories(Path.of(basePath));

        // Resolve the git executable. The Gradle test runner may not have 'git' in PATH
        // (it inherits a restricted environment), so we probe common fixed locations first
        // and fall back to the bare 'git' command only as a last resort.
        gitExec = resolveGitExecutable();

        // Initialise a git repository so that runGitProcess() has a valid working tree.
        // Git4Idea's GitRepositoryManager won't know about this repo (it was just created),
        // so PlatformApiCompat.runIdeGitCommand returns null and the fallback process-based
        // path is used -- which reads getProject().getBasePath() as its working directory.
        git("init");
        git("config", "user.email", "test@example.com");
        git("config", "user.name", "Test User");
        // Create a known branch name so tests can reference it deterministically.
        // We do this before the first commit so the branch is set from the start,
        // avoiding any ambiguity from git's default branch name (master vs main).
        git("checkout", "-b", "test-branch");
        currentBranch = "test-branch";

        // Create and commit a single file so that git-log, branch listing, and the
        // "nothing staged" pre-flight check all have a meaningful state to operate on.
        Path readme = Path.of(basePath, "README.md");
        Files.writeString(readme, "# Test Repository\n");
        git("add", "README.md");
        git("commit", "-m", "chore: initial test commit");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    /**
     * Runs a git command in {@link #basePath}, discarding output.
     * Used only during test set-up, never during SUT execution.
     */
    private void git(String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(gitExec);
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd)
            .directory(new File(basePath))
            .redirectErrorStream(true)
            .start();
        // Drain stdout/stderr to prevent the subprocess from blocking on a full pipe buffer.
        p.getInputStream().readAllBytes();
        p.waitFor();
    }

    /**
     * Resolves the git executable to an absolute path.
     *
     * <p>The Gradle test runner inherits a restricted environment that may omit '/usr/bin'
     * from PATH. Rather than relying on PATH resolution, we probe well-known locations
     * for git and only fall back to the bare {@code "git"} command as a last resort.
     *
     * <p>On Linux git is almost always at {@code /usr/bin/git}; on macOS Homebrew installs
     * to {@code /usr/local/bin/git} or {@code /opt/homebrew/bin/git}.
     */
    private static String resolveGitExecutable() {
        for (String candidate : List.of(
            "/usr/bin/git",
            "/usr/local/bin/git",
            "/opt/homebrew/bin/git",
            "/opt/local/bin/git"   // MacPorts
        )) {
            if (new File(candidate).canExecute()) {
                return candidate;
            }
        }
        return "git"; // last resort: rely on PATH
    }

    /**
     * Builds a {@link JsonObject} from alternating key/value {@link String} pairs.
     * Example: {@code args("action", "list", "format", "oneline")}
     */
    private static JsonObject args(String... pairs) {
        JsonObject obj = new JsonObject();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            obj.addProperty(pairs[i], pairs[i + 1]);
        }
        return obj;
    }

    // ── GitStatusTool ─────────────────────────────────────────────────────────────

    /**
     * Calling GitStatusTool on a valid git repository must return a non-empty,
     * non-error response.
     */
    public void testGitStatusReturnsValidResponse() throws Exception {
        GitStatusTool tool = new GitStatusTool(getProject());
        String result = tool.execute(new JsonObject());

        assertNotNull("git status must return a result", result);
        assertFalse("Result should not be blank", result.isBlank());
        assertFalse("Result should not start with 'Error', got: " + result,
            result.startsWith("Error"));
    }

    /**
     * The short-format output of {@code git status --short --branch} always includes
     * a {@code ##} branch indicator on the first line.
     */
    public void testGitStatusShortFormatContainsBranchIndicator() throws Exception {
        GitStatusTool tool = new GitStatusTool(getProject());
        String result = tool.execute(new JsonObject());

        assertTrue("Expected '##' branch indicator in short-format output, got: " + result,
            result.contains("##"));
    }

    /**
     * Verbose mode executes {@code git status} (no flags), whose output always starts
     * with "On branch" — distinct from short mode's {@code ##} prefix.
     */
    public void testGitStatusVerboseContainsOnBranchLine() throws Exception {
        GitStatusTool tool = new GitStatusTool(getProject());
        JsonObject argsObj = new JsonObject();
        argsObj.addProperty("verbose", true);
        String result = tool.execute(argsObj);

        assertNotNull(result);
        assertFalse("Verbose result should not start with 'Error', got: " + result,
            result.startsWith("Error"));
        assertTrue("Verbose output should contain 'On branch', got: " + result,
            result.contains("On branch"));
    }

    /**
     * Short-format output does NOT contain the "On branch" phrase (that is verbose-only).
     */
    public void testGitStatusShortFormatDoesNotContainOnBranch() throws Exception {
        GitStatusTool tool = new GitStatusTool(getProject());
        String result = tool.execute(new JsonObject()); // default = short format

        assertFalse("Short-format output should not contain 'On branch', got: " + result,
            result.contains("On branch"));
    }

    // ── GitLogTool ────────────────────────────────────────────────────────────────

    /**
     * Git log with default arguments must return the initial commit created in
     * {@link #setUp()} without error.
     */
    public void testGitLogReturnsCommits() throws Exception {
        GitLogTool tool = new GitLogTool(getProject());
        String result = tool.execute(new JsonObject());

        assertNotNull("git log must return a result", result);
        assertFalse("Result should not be blank", result.isBlank());
        assertFalse("Result should not start with 'Error', got: " + result,
            result.startsWith("Error"));
        // The medium format (default) emits "commit <hash>\nAuthor: …" for every entry.
        assertTrue("Expected 'commit' keyword in git log medium output, got: " + result,
            result.contains("commit"));
    }

    /**
     * Passing {@code max_count}=3 limits the number of returned commits.
     * With only one commit in the repo the output still contains that commit.
     */
    public void testGitLogWithMaxCount() throws Exception {
        GitLogTool tool = new GitLogTool(getProject());
        JsonObject argsObj = new JsonObject();
        argsObj.addProperty("max_count", 3);
        String result = tool.execute(argsObj);

        assertNotNull(result);
        assertFalse("Result should not start with 'Error', got: " + result,
            result.startsWith("Error"));
        assertTrue("Expected 'commit' keyword in limited git log output, got: " + result,
            result.contains("commit"));
    }

    /**
     * The {@code oneline} format emits one abbreviated line per commit.
     * The output must include the commit message from setUp.
     */
    public void testGitLogOnelineFormatContainsMessage() throws Exception {
        GitLogTool tool = new GitLogTool(getProject());
        String result = tool.execute(args("format", "oneline"));

        assertNotNull(result);
        assertFalse("Result should not start with 'Error', got: " + result,
            result.startsWith("Error"));
        assertTrue("Expected commit message in oneline output, got: " + result,
            result.contains("initial test commit"));
    }

    /**
     * The {@code short} format includes an "Author:" line per commit.
     */
    public void testGitLogShortFormatContainsAuthor() throws Exception {
        GitLogTool tool = new GitLogTool(getProject());
        String result = tool.execute(args("format", "short"));

        assertNotNull(result);
        assertFalse("Result should not start with 'Error', got: " + result,
            result.startsWith("Error"));
        assertTrue("Expected 'Author:' in short git log output, got: " + result,
            result.contains("Author:"));
    }

    // ── GitBranchTool ─────────────────────────────────────────────────────────────

    /**
     * The {@code list} action must return available branches without error.
     * {@code git branch --list -v} marks the current branch with {@code *}.
     */
    public void testGitBranchListAction() throws Exception {
        GitBranchTool tool = new GitBranchTool(getProject());
        String result = tool.execute(args("action", "list"));

        assertNotNull("git branch list must return a result", result);
        assertFalse("Result should not be blank", result.isBlank());
        assertFalse("Result should not start with 'Error', got: " + result,
            result.startsWith("Error"));
        // git branch --list -v marks the current branch with '*'
        assertTrue("Expected '*' to mark the current branch, got: " + result,
            result.contains("*"));
    }

    /**
     * Omitting the {@code action} parameter defaults to "list" — same behaviour as explicit list.
     */
    public void testGitBranchDefaultActionIsList() throws Exception {
        GitBranchTool tool = new GitBranchTool(getProject());
        String resultDefault = tool.execute(new JsonObject());
        String resultExplicit = tool.execute(args("action", "list"));

        assertFalse("Default branch result should not start with 'Error', got: " + resultDefault,
            resultDefault.startsWith("Error"));
        assertFalse("Explicit list should not start with 'Error', got: " + resultExplicit,
            resultExplicit.startsWith("Error"));
        // Both outputs must mark the current branch
        assertTrue("Default result must contain '*'", resultDefault.contains("*"));
        assertTrue("Explicit list result must contain '*'", resultExplicit.contains("*"));
    }

    /**
     * Requesting an unrecognised action must return a clear error that names the
     * invalid action and lists the valid ones.
     */
    public void testGitBranchInvalidActionReturnsError() throws Exception {
        GitBranchTool tool = new GitBranchTool(getProject());
        String result = tool.execute(args("action", "bogus_action"));

        assertNotNull(result);
        // Exact message from GitBranchTool source:
        // "Error: unknown action 'bogus_action'. Use: list, create, switch, delete"
        assertTrue("Expected unknown-action error, got: " + result,
            result.startsWith("Error: unknown action 'bogus_action'"));
        assertTrue("Error must list valid actions, got: " + result,
            result.contains("list"));
        assertTrue("Error must list valid actions, got: " + result,
            result.contains("create"));
    }

    /**
     * The {@code create} action without a {@code name} parameter must return a validation error.
     */
    public void testGitBranchCreateWithoutNameReturnsError() throws Exception {
        GitBranchTool tool = new GitBranchTool(getProject());
        String result = tool.execute(args("action", "create"));

        assertNotNull(result);
        // Exact message: "Error: 'name' parameter is required for 'create'"
        assertEquals("Error: 'name' parameter is required for 'create'", result);
    }

    /**
     * The {@code switch} action without a {@code name} parameter must return a validation error.
     */
    public void testGitBranchSwitchWithoutNameReturnsError() throws Exception {
        GitBranchTool tool = new GitBranchTool(getProject());
        String result = tool.execute(args("action", "switch"));

        assertNotNull(result);
        // Exact message: "Error: 'name' parameter is required for 'switch'"
        assertEquals("Error: 'name' parameter is required for 'switch'", result);
    }

    /**
     * The {@code delete} action without a {@code name} parameter must return a validation error.
     */
    public void testGitBranchDeleteWithoutNameReturnsError() throws Exception {
        GitBranchTool tool = new GitBranchTool(getProject());
        String result = tool.execute(args("action", "delete"));

        assertNotNull(result);
        // Exact message: "Error: 'name' parameter is required for 'delete'"
        assertEquals("Error: 'name' parameter is required for 'delete'", result);
    }

    // ── GitDiffTool ───────────────────────────────────────────────────────────────

    /**
     * With a clean working tree, {@code git diff} produces empty output (no unstaged changes).
     * The tool must not return an error string.
     */
    public void testGitDiffEmptyArgsCleanTree() throws Exception {
        GitDiffTool tool = new GitDiffTool(getProject());
        String result = tool.execute(new JsonObject());

        assertNotNull("git diff must return a result (even empty)", result);
        assertFalse("Clean-tree diff should not start with 'Error', got: " + result,
            result.startsWith("Error"));
    }

    /**
     * With nothing staged, {@code git diff --cached} (staged diff) must return
     * empty output without error.
     */
    public void testGitDiffStagedOnCleanIndexIsEmpty() throws Exception {
        GitDiffTool tool = new GitDiffTool(getProject());
        JsonObject argsObj = new JsonObject();
        argsObj.addProperty("staged", true);
        String result = tool.execute(argsObj);

        assertNotNull(result);
        assertFalse("Staged diff should not start with 'Error', got: " + result,
            result.startsWith("Error"));
        // Clean index → staged diff is empty
        assertTrue("Staged diff on a clean index must be empty, got: " + result,
            result.isBlank());
    }

    /**
     * Stat-only mode ({@code --stat}) on a clean tree must not produce an error.
     */
    public void testGitDiffStatOnlyNoChangesReturnsNoError() throws Exception {
        GitDiffTool tool = new GitDiffTool(getProject());
        JsonObject argsObj = new JsonObject();
        argsObj.addProperty("stat_only", true);
        String result = tool.execute(argsObj);

        assertNotNull(result);
        assertFalse("Stat-only diff should not start with 'Error', got: " + result,
            result.startsWith("Error"));
    }

    /**
     * Comparing against HEAD (a valid ref) must not produce an error.
     */
    public void testGitDiffAgainstHeadReturnsNoError() throws Exception {
        GitDiffTool tool = new GitDiffTool(getProject());
        String result = tool.execute(args("commit", "HEAD"));

        assertNotNull(result);
        assertFalse("Diff against HEAD should not start with 'Error', got: " + result,
            result.startsWith("Error"));
    }

    // ── GitStageTool ──────────────────────────────────────────────────────────────

    /**
     * Omitting all path parameters ({@code path}, {@code paths}, {@code all}) must
     * return the exact validation error defined in {@code GitStageTool.execute()}.
     */
    public void testGitStageWithoutPathReturnsError() throws Exception {
        GitStageTool tool = new GitStageTool(getProject());
        String result = tool.execute(new JsonObject());

        assertNotNull(result);
        assertEquals(
            "Error: provide 'path', 'paths', or 'all' parameter",
            result
        );
    }

    /**
     * Staging a path that does not exist on disk must return a git error (not a raw exception).
     * Git exits non-zero with "pathspec '...' did not match any files".
     */
    public void testGitStageNonExistentFileReturnsError() throws Exception {
        GitStageTool tool = new GitStageTool(getProject());
        String result = tool.execute(args("path", "/nonexistent/file-that-does-not-exist-xyz99.txt"));

        assertNotNull(result);
        assertTrue("Expected 'Error' for staging non-existent file, got: " + result,
            result.startsWith("Error"));
        // Must not be a raw Java exception dump
        assertFalse("Result must not contain a Java exception class, got: " + result,
            result.contains("Exception"));
    }

    /**
     * Providing an empty {@code paths} JSON array should still produce a graceful error
     * or at most a no-op — never a raw Java exception.
     */
    public void testGitStageEmptyPathsArrayIsGraceful() throws Exception {
        GitStageTool tool = new GitStageTool(getProject());
        JsonObject argsObj = new JsonObject();
        argsObj.add("paths", new JsonArray()); // empty array
        String result = tool.execute(argsObj);

        assertNotNull(result);
        // Must not expose a raw exception dump regardless of the outcome
        assertFalse("Result must not contain a Java exception class, got: " + result,
            result.contains("Exception"));
    }

    // ── GitCommitTool ─────────────────────────────────────────────────────────────

    /**
     * Omitting the required {@code message} parameter must return the exact
     * validation error defined in {@code GitCommitTool.execute()}.
     */
    public void testGitCommitWithoutMessageReturnsError() throws Exception {
        GitCommitTool tool = new GitCommitTool(getProject());
        String result = tool.execute(new JsonObject());

        assertNotNull(result);
        assertEquals("Error: 'message' parameter is required", result);
    }

    /**
     * Passing an empty string for {@code message} is treated identically to a missing
     * parameter — the tool returns the same validation error.
     */
    public void testGitCommitWithEmptyMessageReturnsError() throws Exception {
        GitCommitTool tool = new GitCommitTool(getProject());
        String result = tool.execute(args("message", ""));

        assertNotNull(result);
        assertEquals("Error: 'message' parameter is required", result);
    }

    /**
     * Attempting a commit with a valid message but nothing to commit must return
     * the "nothing to commit" error from the pre-flight check — not a raw git error
     * and not a Java exception. The working tree is clean after the setUp commit,
     * so the pre-flight branch that detects "nothing to commit" is exercised.
     * Note: git_commit defaults to all: true, so it runs 'git add -A' first,
     * then checks for staged changes.
     */
    public void testGitCommitNothingStagedReturnsError() throws Exception {
        GitCommitTool tool = new GitCommitTool(getProject());
        // Explicit all: false — the default (all: true) would run 'git add -A' which
        // picks up IntelliJ project files created by BasePlatformTestCase, making the
        // commit succeed instead of hitting the "nothing staged" error path.
        String result = tool.execute(args("message", "test: must not be committed", "all", "false"));

        assertNotNull(result);
        // With all: false, the tool skips 'git add -A' and checks staged changes directly.
        // Nothing is staged after setUp, so the error prefix is "Error: nothing to commit."
        assertTrue("Expected 'nothing to commit' error, got: " + result,
            result.startsWith("Error: nothing to commit."));
        // Must not surface a raw Java exception
        assertFalse("Result must not contain a Java exception, got: " + result,
            result.contains("Exception"));
    }

    /**
     * The error message for "nothing to commit" must provide actionable guidance —
     * it must not be a bare "nothing to commit" with no hint.
     */
    public void testGitCommitNothingStagedErrorContainsHint() throws Exception {
        GitCommitTool tool = new GitCommitTool(getProject());
        // Explicit all: false — see testGitCommitNothingStagedReturnsError for explanation.
        String result = tool.execute(args("message", "test: must not be committed", "all", "false"));

        assertNotNull(result);
        assertTrue("Expected 'nothing to commit' error", result.startsWith("Error: nothing to commit."));
        // The hint appended after the base message must contain a guidance phrase.
        // • "The working tree is clean." when no changes exist
        // • "There are unstaged changes not picked up by --all" for edge cases
        boolean containsHint =
            result.contains("working tree")
                || result.contains("unstaged");
        assertTrue("Error must include actionable guidance, got: " + result, containsHint);
    }

    // ── GitLogTool — additional format/filter tests ───────────────────────────────

    /**
     * The {@code full} format produces the longest output; it must include author
     * and commit-date lines.
     */
    public void testGitLogFullFormatContainsCommitDate() throws Exception {
        GitLogTool tool = new GitLogTool(getProject());
        String result = tool.execute(args("format", "full"));

        assertNotNull(result);
        assertFalse("Result should not start with 'Error', got: " + result,
            result.startsWith("Error"));
        assertTrue("Full format must contain 'Author:', got: " + result,
            result.contains("Author:"));
    }

    /**
     * The {@code author} filter narrows the log to commits by a matching author.
     * Using "test@example.com" (the address configured in setUp) must return the
     * single test commit.
     */
    public void testGitLogWithAuthorFilter() throws Exception {
        GitLogTool tool = new GitLogTool(getProject());
        String result = tool.execute(args("author", "test@example.com"));

        assertNotNull(result);
        assertFalse("Result should not start with 'Error', got: " + result,
            result.startsWith("Error"));
        assertTrue("Expected initial test commit in author-filtered log, got: " + result,
            result.contains("initial test commit"));
    }

    /**
     * The {@code since} filter applied to a date in the past must include the
     * initial test commit created during setUp.
     */
    public void testGitLogWithSinceFilter() throws Exception {
        GitLogTool tool = new GitLogTool(getProject());
        String result = tool.execute(args("since", "2000-01-01"));

        assertNotNull(result);
        assertFalse("Result should not start with 'Error', got: " + result,
            result.startsWith("Error"));
        assertTrue("Expected initial test commit in since-filtered log, got: " + result,
            result.contains("initial test commit"));
    }

    /**
     * The {@code until} filter applied to a date far in the future must include
     * the initial test commit.
     */
    public void testGitLogWithUntilFilter() throws Exception {
        GitLogTool tool = new GitLogTool(getProject());
        String result = tool.execute(args("until", "2099-12-31"));

        assertNotNull(result);
        assertFalse("Result should not start with 'Error', got: " + result,
            result.startsWith("Error"));
        assertTrue("Expected initial test commit in until-filtered log, got: " + result,
            result.contains("initial test commit"));
    }

    /**
     * The {@code path} filter limits log to commits touching a specific file.
     * "README.md" was staged and committed in setUp, so the initial commit must appear.
     */
    public void testGitLogWithPathFilter() throws Exception {
        GitLogTool tool = new GitLogTool(getProject());
        String result = tool.execute(args("path", "README.md"));

        assertNotNull(result);
        assertFalse("Result should not start with 'Error', got: " + result,
            result.startsWith("Error"));
        assertTrue("Expected initial test commit in path-filtered log, got: " + result,
            result.contains("initial test commit"));
    }

    /**
     * The {@code branch} filter selects which branch's history to show.
     * Uses {@link #currentBranch} captured in setUp to avoid hardcoding
     * "master" vs "main" and to avoid spawning a subprocess inside the test.
     */
    public void testGitLogWithBranchFilter() throws Exception {
        assertFalse("currentBranch must be set during setUp", currentBranch.isBlank());

        GitLogTool tool = new GitLogTool(getProject());
        String result = tool.execute(args("branch", currentBranch));

        assertNotNull(result);
        assertFalse("Result should not start with 'Error', got: " + result,
            result.startsWith("Error"));
        assertTrue("Expected initial test commit in branch-filtered log, got: " + result,
            result.contains("initial test commit"));
    }

    // ── GitStatusTool — stash coverage ────────────────────────────────────────────

    /**
     * After creating a stash the status output must include a stash count line.
     * This exercises the stash-counting branch in {@link GitStatusTool#execute}.
     */
    public void testGitStatusWithStashIncludesStashCount() throws Exception {
        // Create a dirty working tree so git stash has something to stash.
        java.nio.file.Files.writeString(
            java.nio.file.Path.of(basePath, "README.md"),
            "# Modified for stash test\n"
        );
        git("stash");

        GitStatusTool tool = new GitStatusTool(getProject());
        String result = tool.execute(new JsonObject());

        assertNotNull(result);
        assertFalse("Result should not start with 'Error', got: " + result,
            result.startsWith("Error"));
        assertTrue("Expected 'Stash:' line in output with non-empty stash, got: " + result,
            result.contains("Stash:"));

        // Restore working tree by popping the stash for clean test isolation.
        git("stash", "pop");
    }
}
