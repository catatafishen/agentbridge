package com.github.catatafishen.agentbridge.ui.renderers;

import kotlin.text.MatchResult;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests regex patterns and helper methods in {@link GitDiffRenderer}.
 */
class GitDiffRendererTest {

    private static final GitDiffRenderer R = GitDiffRenderer.INSTANCE;

    @Nested
    class StatSummaryPattern {

        @Test
        void matchesMultipleFilesWithInsertionsAndDeletions() {
            assertTrue(R.getSTAT_SUMMARY().matches(" 3 files changed, 10 insertions(+), 5 deletions(-)"));
        }

        @Test
        void matchesSingleFileWithInsertion() {
            assertTrue(R.getSTAT_SUMMARY().matches(" 1 file changed, 1 insertion(+)"));
        }

        @Test
        void matchesSingleFileWithDeletion() {
            assertTrue(R.getSTAT_SUMMARY().matches(" 1 file changed, 1 deletion(-)"));
        }

        @Test
        void matchesWithoutLeadingSpace() {
            assertTrue(R.getSTAT_SUMMARY().matches("3 files changed, 10 insertions(+), 5 deletions(-)"));
        }

        @Test
        void doesNotMatchRandomText() {
            assertFalse(R.getSTAT_SUMMARY().matches("just some text"));
        }
    }

    @Nested
    class StatFilePattern {

        @Test
        void matchesFileWithCountAndBar() {
            MatchResult match = R.getSTAT_FILE().find(" src/Main.java | 15 +++---", 0);

            assertNotNull(match);
            assertEquals("src/Main.java", match.getGroupValues().get(1).trim());
            assertEquals("15", match.getGroupValues().get(2));
            assertEquals("+++---", match.getGroupValues().get(3));
        }

        @Test
        void matchesFileWithOnlyAdditions() {
            MatchResult match = R.getSTAT_FILE().find(" README.md | 3 +++", 0);

            assertNotNull(match);
            assertEquals("README.md", match.getGroupValues().get(1).trim());
            assertEquals("3", match.getGroupValues().get(2));
            assertEquals("+++", match.getGroupValues().get(3));
        }

        @Test
        void matchesFileWithOnlyDeletions() {
            MatchResult match = R.getSTAT_FILE().find(" old-file.txt | 7 -------", 0);

            assertNotNull(match);
            assertEquals("old-file.txt", match.getGroupValues().get(1).trim());
            assertEquals("7", match.getGroupValues().get(2));
        }

        @Test
        void doesNotMatchDiffHeader() {
            assertNull(R.getSTAT_FILE().find("diff --git a/foo b/bar", 0));
        }
    }

    @Nested
    class DiffGitPattern {

        @Test
        void matchesDiffHeader() {
            MatchResult match = R.getDIFF_GIT().find("diff --git a/old/path.java b/new/path.java", 0);

            assertNotNull(match);
            assertEquals("old/path.java", match.getGroupValues().get(1));
            assertEquals("new/path.java", match.getGroupValues().get(2));
        }

        @Test
        void matchesSameFilePath() {
            MatchResult match = R.getDIFF_GIT().find("diff --git a/src/Foo.kt b/src/Foo.kt", 0);

            assertNotNull(match);
            assertEquals("src/Foo.kt", match.getGroupValues().get(1));
            assertEquals("src/Foo.kt", match.getGroupValues().get(2));
        }

        @Test
        void doesNotMatchStatLine() {
            assertNull(R.getDIFF_GIT().find(" src/Main.java | 15 +++---", 0));
        }
    }

    @Nested
    class IsMetaLine {

        @ParameterizedTest
        @ValueSource(strings = {
            "--- a/src/Main.java",
            "+++ b/src/Main.java",
            "index abc1234..def5678 100644",
            "new file mode 100644",
            "deleted file mode 100644",
            "similarity index 95%",
            "rename from old.java",
            "old mode 100644",
            "new mode 100755"
        })
        void recognizesMetaLines(String line) {
            assertTrue(R.isMetaLine(line));
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "+added line",
            "-removed line",
            " context line",
            "@@ -1,3 +1,4 @@",
            "some random text"
        })
        void rejectsNonMetaLines(String line) {
            assertFalse(R.isMetaLine(line));
        }
    }
}
