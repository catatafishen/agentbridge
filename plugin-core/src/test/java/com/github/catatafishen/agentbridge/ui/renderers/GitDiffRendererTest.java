package com.github.catatafishen.agentbridge.ui.renderers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for pure logic in {@link GitDiffRenderer} — {@code isMetaLine()}
 * and regex pattern validation.
 */
class GitDiffRendererTest {

    @Nested
    @DisplayName("isMetaLine")
    class IsMetaLine {

        @ParameterizedTest
        @ValueSource(strings = {
            "--- a/file.txt",
            "+++ b/file.txt",
            "--- /dev/null",
            "+++ /dev/null",
            "index abc1234..def5678 100644",
            "new file mode 100644",
            "deleted file mode 100644",
            "similarity index 95%",
            "rename from old.txt",
            "rename to new.txt",
            "old mode 100644",
            "new mode 100755"
        })
        void metaLinesDetected(String line) {
            assertTrue(GitDiffRenderer.INSTANCE.isMetaLine(line));
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "+added line",
            "-removed line",
            " context line",
            "@@ -1,5 +1,7 @@",
            "diff --git a/f.txt b/f.txt",
            "Some random text",
            "",
            "   indented"
        })
        void nonMetaLinesRejected(String line) {
            assertFalse(GitDiffRenderer.INSTANCE.isMetaLine(line));
        }
    }

    @Nested
    @DisplayName("Regex patterns")
    class RegexPatterns {

        @Test
        void statSummaryMatchesSingleFile() {
            assertTrue(GitDiffRenderer.INSTANCE.getSTAT_SUMMARY()
                .matches(" 1 file changed, 3 insertions(+)"));
        }

        @Test
        void statSummaryMatchesMultipleFiles() {
            assertTrue(GitDiffRenderer.INSTANCE.getSTAT_SUMMARY()
                .matches(" 5 files changed, 10 insertions(+), 3 deletions(-)"));
        }

        @Test
        void statSummaryDoesNotMatchRandomText() {
            assertFalse(GitDiffRenderer.INSTANCE.getSTAT_SUMMARY()
                .matches("some other output"));
        }

        @Test
        void statFileMatchesTypicalLine() {
            var match = GitDiffRenderer.INSTANCE.getSTAT_FILE()
                .find(" src/Main.java | 42 +++---", 0);
            assertNotNull(match);
            assertTrue(match.getGroupValues().get(1).contains("src/Main.java"));
        }

        @Test
        void diffGitMatchesHeader() {
            var match = GitDiffRenderer.INSTANCE.getDIFF_GIT()
                .find("diff --git a/src/Main.java b/src/Main.java", 0);
            assertNotNull(match);
            assertTrue(match.getGroupValues().get(2).contains("src/Main.java"));
        }

        @Test
        void diffGitWithRename() {
            var match = GitDiffRenderer.INSTANCE.getDIFF_GIT()
                .find("diff --git a/old.txt b/new.txt", 0);
            assertNotNull(match);
            assertTrue(match.getGroupValues().get(1).contains("old.txt"));
            assertTrue(match.getGroupValues().get(2).contains("new.txt"));
        }
    }
}
