package com.github.catatafishen.agentbridge.ui.renderers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for pure logic in {@link GitStatusRenderer} — specifically
 * the {@code categorizeFiles()} method which parses git status output.
 */
class GitStatusRendererTest {

    @Nested
    @DisplayName("categorizeFiles")
    class CategorizeFiles {

        @Test
        void emptyInputReturnsEmptyCategories() {
            var result = GitStatusRenderer.INSTANCE.categorizeFiles(List.of());
            assertTrue(result.getStaged().isEmpty());
            assertTrue(result.getUnstaged().isEmpty());
            assertTrue(result.getUntracked().isEmpty());
            assertTrue(result.getConflicted().isEmpty());
        }

        @Test
        void untrackedFilesDetected() {
            var result = GitStatusRenderer.INSTANCE.categorizeFiles(List.of(
                "?? new-file.txt",
                "?? another.kt"
            ));
            assertEquals(2, result.getUntracked().size());
            assertEquals("new-file.txt", result.getUntracked().get(0));
            assertEquals("another.kt", result.getUntracked().get(1));
        }

        @Test
        void stagedFileDetected() {
            var result = GitStatusRenderer.INSTANCE.categorizeFiles(List.of(
                "M  src/Main.java"
            ));
            assertEquals(1, result.getStaged().size());
            assertEquals('M', (char) result.getStaged().get(0).getFirst());
            assertEquals("src/Main.java", result.getStaged().get(0).getSecond());
        }

        @Test
        void unstagedModification() {
            var result = GitStatusRenderer.INSTANCE.categorizeFiles(List.of(
                " M src/Main.java"
            ));
            assertEquals(1, result.getUnstaged().size());
            assertEquals('M', (char) result.getUnstaged().get(0).getFirst());
            assertEquals("src/Main.java", result.getUnstaged().get(0).getSecond());
        }

        @Test
        void partiallyStaged() {
            var result = GitStatusRenderer.INSTANCE.categorizeFiles(List.of(
                "MM src/Both.java"
            ));
            assertEquals(1, result.getStaged().size());
            assertEquals(1, result.getUnstaged().size());
            assertEquals("src/Both.java", result.getStaged().get(0).getSecond());
            assertEquals("src/Both.java", result.getUnstaged().get(0).getSecond());
        }

        @ParameterizedTest(name = "staged {1} from \"{0}\"")
        @CsvSource({
            "A  new-module/File.kt, A",
            "D  removed.txt, D",
            "R  old.txt -> new.txt, R"
        })
        void stagedFileTypes(String statusLine, char expectedType) {
            var result = GitStatusRenderer.INSTANCE.categorizeFiles(List.of(statusLine));
            assertEquals(1, result.getStaged().size());
            assertEquals(expectedType, (char) result.getStaged().get(0).getFirst());
        }

        @Test
        void deletedUnstaged() {
            var result = GitStatusRenderer.INSTANCE.categorizeFiles(List.of(
                " D removed.txt"
            ));
            assertEquals(1, result.getUnstaged().size());
            assertEquals('D', (char) result.getUnstaged().get(0).getFirst());
        }

        @Test
        void conflictBothModified() {
            var result = GitStatusRenderer.INSTANCE.categorizeFiles(List.of(
                "UU conflicted.java"
            ));
            assertEquals(1, result.getConflicted().size());
            assertEquals("conflicted.java", result.getConflicted().get(0));
        }

        @ParameterizedTest(name = "conflict pattern \"{0}\"")
        @CsvSource({
            "AA both-added.txt",
            "DD both-deleted.txt",
            "DU deleted-by-us.txt"
        })
        void conflictPatterns(String statusLine) {
            var result = GitStatusRenderer.INSTANCE.categorizeFiles(List.of(statusLine));
            assertEquals(1, result.getConflicted().size());
        }

        @Test
        void branchHeaderIgnored() {
            var result = GitStatusRenderer.INSTANCE.categorizeFiles(List.of(
                "## main...origin/main [ahead 1]",
                "M  file.txt"
            ));
            assertEquals(1, result.getStaged().size());
            assertTrue(result.getConflicted().isEmpty());
        }

        @Test
        void blankLinesIgnored() {
            var result = GitStatusRenderer.INSTANCE.categorizeFiles(List.of(
                "",
                "   ",
                "M  file.txt"
            ));
            assertEquals(1, result.getStaged().size());
        }

        @Test
        void shortLinesIgnored() {
            var result = GitStatusRenderer.INSTANCE.categorizeFiles(List.of(
                "X",
                "XY"
            ));
            assertTrue(result.getStaged().isEmpty());
            assertTrue(result.getUnstaged().isEmpty());
        }

        @Test
        void mixedStatusOutput() {
            var result = GitStatusRenderer.INSTANCE.categorizeFiles(List.of(
                "## feature...origin/feature",
                "M  staged.txt",
                " M unstaged.txt",
                "?? untracked.txt",
                "UU conflict.txt",
                "A  added.txt"
            ));
            assertEquals(2, result.getStaged().size());
            assertEquals(1, result.getUnstaged().size());
            assertEquals(1, result.getUntracked().size());
            assertEquals(1, result.getConflicted().size());
        }
    }
}
