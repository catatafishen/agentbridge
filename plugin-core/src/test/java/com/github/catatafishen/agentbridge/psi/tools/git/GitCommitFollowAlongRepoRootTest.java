package com.github.catatafishen.agentbridge.psi.tools.git;

import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Regression test for {@code docs/bugs/COMMIT-NOT-FOUND-IN-LOG-BUG.md}.
 *
 * <p>{@link GitTool#showNewCommitInLog(String)} must read HEAD from the repo root the
 * caller actually committed in — never the project base path. In multi-repo projects
 * those differ, and the previous bug used {@code runGit(...)} (project-base resolution)
 * causing the VCS Log to navigate to an unrelated commit and emit a "commit could not
 * be found" bubble.
 *
 * <p>This test installs a {@link GitTool} subclass that captures the working directory
 * passed to {@code runGitIn} and asserts it matches the root supplied to
 * {@link GitTool#showNewCommitInLog(String)}.
 */
public class GitCommitFollowAlongRepoRootTest extends BasePlatformTestCase {

    public void testShowNewCommitInLogReadsHeadFromSuppliedRoot() throws Exception {
        // Follow-along must be enabled for showNewCommitInLog to do anything.
        // String overload is required: the boolean overload removes the key when value
        // equals the default, which would leave the (true) default in place — but here
        // we want to be explicit even though true is the default.
        PropertiesComponent.getInstance(getProject())
            .setValue(ToolLayerSettings.FOLLOW_AGENT_FILES_KEY, "true");

        AtomicReference<String> capturedWorkingDir = new AtomicReference<>();
        CountDownLatch ranGit = new CountDownLatch(1);

        GitTool tool = new GitTool(getProject()) {
            @Override
            public @org.jetbrains.annotations.NotNull String id() {
                return "test-git";
            }

            @Override
            public @org.jetbrains.annotations.NotNull Kind kind() {
                return Kind.READ;
            }

            @Override
            public @org.jetbrains.annotations.NotNull String displayName() {
                return "test-git";
            }

            @Override
            public @org.jetbrains.annotations.NotNull String description() {
                return "test fixture";
            }

            @Override
            public @org.jetbrains.annotations.NotNull com.google.gson.JsonObject inputSchema() {
                return new com.google.gson.JsonObject();
            }

            @Override
            public String execute(@org.jetbrains.annotations.NotNull com.google.gson.JsonObject args) {
                return "";
            }

            @Override
            protected String runGitIn(@org.jetbrains.annotations.NotNull String workingDir, String... args) {
                if (args.length >= 2 && "rev-parse".equals(args[0]) && "HEAD".equals(args[1])) {
                    capturedWorkingDir.set(workingDir);
                    ranGit.countDown();
                    // Return a fake 40-char hash so the EDT navigation block runs.
                    // VCS log is not initialised in the lightweight test fixture so the
                    // navigation itself is a safe no-op — we only care that runGitIn was
                    // invoked with the supplied working dir.
                    return "0123456789abcdef0123456789abcdef01234567\n";
                }
                return "";
            }
        };

        String suppliedRoot = "/some/multirepo/sub/repo";
        tool.showNewCommitInLog(suppliedRoot);

        assertTrue("showNewCommitInLog must invoke runGitIn(\"rev-parse\",\"HEAD\") within 5 s",
            ranGit.await(5, TimeUnit.SECONDS));
        assertEquals(
            "showNewCommitInLog must read HEAD from the supplied repo root, not the project base — "
                + "see docs/bugs/COMMIT-NOT-FOUND-IN-LOG-BUG.md",
            suppliedRoot, capturedWorkingDir.get());
    }
}
