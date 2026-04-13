package com.github.catatafishen.agentbridge.ui.renderers;

import kotlin.text.MatchResult;
import kotlin.text.Regex;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests regex patterns in {@link GitCommitRenderer}.
 */
class GitCommitRendererTest {

    private static final GitCommitRenderer R = GitCommitRenderer.INSTANCE;

    @Nested
    class HeaderPattern {

        @Test
        void matchesBranchAndHash() {
            Regex pattern = R.getHEADER_PATTERN();
            MatchResult match = pattern.find("[main abc1234] Fix bug", 0);

            assertNotNull(match);
            assertEquals("main", match.getGroupValues().get(1));
            assertEquals("abc1234", match.getGroupValues().get(2));
            assertEquals("Fix bug", match.getGroupValues().get(3));
        }

        @Test
        void matchesBranchWithSlash() {
            MatchResult match = R.getHEADER_PATTERN().find("[feature/login def5678] Add auth", 0);

            assertNotNull(match);
            assertEquals("feature/login", match.getGroupValues().get(1));
            assertEquals("def5678", match.getGroupValues().get(2));
            assertEquals("Add auth", match.getGroupValues().get(3));
        }

        @Test
        void doesNotMatchWithoutBrackets() {
            assertNull(R.getHEADER_PATTERN().find("main abc1234 Fix bug", 0));
        }
    }

    @Nested
    class SummaryPattern {

        @Test
        void matchesSingleFile() {
            assertTrue(R.getSUMMARY_PATTERN().matches("1 file changed, 2 insertions(+)"));
        }

        @Test
        void matchesMultipleFiles() {
            assertTrue(R.getSUMMARY_PATTERN().matches("3 files changed, 10 insertions(+), 5 deletions(-)"));
        }

        @Test
        void matchesDeletionsOnly() {
            assertTrue(R.getSUMMARY_PATTERN().matches("1 file changed, 3 deletions(-)"));
        }

        @Test
        void doesNotMatchRandomText() {
            assertFalse(R.getSUMMARY_PATTERN().matches("just some text"));
        }
    }

    @Nested
    class CreatePattern {

        @Test
        void matchesCreateMode() {
            MatchResult match = R.getCREATE_PATTERN().find("create mode 100644 src/Foo.java", 0);

            assertNotNull(match);
            assertEquals("src/Foo.java", match.getGroupValues().get(1));
        }

        @Test
        void matchesCreateModeExecutable() {
            MatchResult match = R.getCREATE_PATTERN().find("create mode 100755 scripts/deploy.sh", 0);

            assertNotNull(match);
            assertEquals("scripts/deploy.sh", match.getGroupValues().get(1));
        }

        @Test
        void doesNotMatchDeleteMode() {
            assertNull(R.getCREATE_PATTERN().find("delete mode 100644 src/Old.java", 0));
        }
    }

    @Nested
    class DeletePattern {

        @Test
        void matchesDeleteMode() {
            MatchResult match = R.getDELETE_PATTERN().find("delete mode 100644 old/File.kt", 0);

            assertNotNull(match);
            assertEquals("old/File.kt", match.getGroupValues().get(1));
        }

        @Test
        void doesNotMatchCreateMode() {
            assertNull(R.getDELETE_PATTERN().find("create mode 100644 src/New.java", 0));
        }
    }

    @Nested
    class RenamePattern {

        @Test
        void matchesRenameWithPercentage() {
            MatchResult match = R.getRENAME_PATTERN().find("rename old.java => new.java (95%)", 0);

            assertNotNull(match);
            assertEquals("old.java", match.getGroupValues().get(1));
            assertEquals("new.java", match.getGroupValues().get(2));
            assertEquals("95", match.getGroupValues().get(3));
        }

        @Test
        void matchesRenameWithPaths() {
            MatchResult match = R.getRENAME_PATTERN().find("rename src/old/Foo.java => src/new/Foo.java (100%)", 0);

            assertNotNull(match);
            assertEquals("src/old/Foo.java", match.getGroupValues().get(1));
            assertEquals("src/new/Foo.java", match.getGroupValues().get(2));
            assertEquals("100", match.getGroupValues().get(3));
        }

        @Test
        void doesNotMatchNonRename() {
            assertNull(R.getRENAME_PATTERN().find("create mode 100644 src/Foo.java", 0));
        }
    }
}
