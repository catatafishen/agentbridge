package com.github.catatafishen.agentbridge.ui.renderers;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests regex patterns and parse methods in {@link GitBranchRenderer}.
 */
class GitBranchRendererTest {

    private static final GitBranchRenderer R = GitBranchRenderer.INSTANCE;

    @Nested
    class BranchLinePattern {

        @Test
        void matchesCurrentBranch() {
            assertTrue(R.getBRANCH_LINE().containsMatchIn("* main abc1234 Latest commit"));
        }

        @Test
        void matchesNonCurrentBranch() {
            assertTrue(R.getBRANCH_LINE().containsMatchIn("  feature/x abc1234 WIP"));
        }

        @Test
        void matchesRemoteBranch() {
            assertTrue(R.getBRANCH_LINE().containsMatchIn("  remotes/origin/main abc1234 message"));
        }

        @Test
        void doesNotMatchLineWithoutHash() {
            assertFalse(R.getBRANCH_LINE().containsMatchIn("* main no-hash-here"));
        }

        @Test
        void doesNotMatchEmptyLine() {
            assertFalse(R.getBRANCH_LINE().containsMatchIn(""));
        }
    }

    @Nested
    class ParseBranch {

        @Test
        void parsesCurrentBranch() {
            GitBranchRenderer.Branch branch = R.parseBranch("* main abc1234 Latest commit message");

            assertNotNull(branch);
            assertEquals("main", branch.getName());
            assertEquals("abc1234", branch.getHash());
            assertEquals("Latest commit message", branch.getMessage());
            assertTrue(branch.isCurrent());
            assertFalse(branch.isRemote());
        }

        @Test
        void parsesNonCurrentBranch() {
            GitBranchRenderer.Branch branch = R.parseBranch("  feature/x def5678 WIP on feature");

            assertNotNull(branch);
            assertEquals("feature/x", branch.getName());
            assertEquals("def5678", branch.getHash());
            assertEquals("WIP on feature", branch.getMessage());
            assertFalse(branch.isCurrent());
            assertFalse(branch.isRemote());
        }

        @Test
        void parsesRemoteBranchAndStripsPrefix() {
            GitBranchRenderer.Branch branch = R.parseBranch("  remotes/origin/main abc1234 Remote message");

            assertNotNull(branch);
            // The REMOTE_PREFIX regex strips "remotes/" prefix
            assertEquals("origin/main", branch.getName());
            assertEquals("abc1234", branch.getHash());
            assertEquals("Remote message", branch.getMessage());
            assertFalse(branch.isCurrent());
            assertTrue(branch.isRemote());
        }

        @Test
        void returnsNullForInvalidLine() {
            assertNull(R.parseBranch("not a branch line at all"));
        }

        @Test
        void returnsNullForEmptyString() {
            assertNull(R.parseBranch(""));
        }

        @Test
        void parsesCurrentBranchWithSlashInName() {
            GitBranchRenderer.Branch branch = R.parseBranch("* feature/my-branch 1234abc Fix the thing");

            assertNotNull(branch);
            assertEquals("feature/my-branch", branch.getName());
            assertTrue(branch.isCurrent());
            assertFalse(branch.isRemote());
        }
    }
}
