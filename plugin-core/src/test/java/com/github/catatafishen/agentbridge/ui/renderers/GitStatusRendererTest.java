package com.github.catatafishen.agentbridge.ui.renderers;

import kotlin.Pair;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the pure categorization logic in {@link GitStatusRenderer}.
 */
class GitStatusRendererTest {

    private static final GitStatusRenderer R = GitStatusRenderer.INSTANCE;

    @Nested
    class CategorizeFiles {

        @Test
        void stagedModifiedFile() {
            var result = R.categorizeFiles(List.of("M  src/Main.java"));
            assertEquals(1, result.getStaged().size());
            assertEquals(new Pair<>('M', "src/Main.java"), result.getStaged().get(0));
            assertTrue(result.getUnstaged().isEmpty());
        }

        @Test
        void unstagedModifiedFile() {
            var result = R.categorizeFiles(List.of(" M src/Main.java"));
            assertTrue(result.getStaged().isEmpty());
            assertEquals(1, result.getUnstaged().size());
            assertEquals(new Pair<>('M', "src/Main.java"), result.getUnstaged().get(0));
        }

        @Test
        void untrackedFile() {
            var result = R.categorizeFiles(List.of("?? new-file.txt"));
            assertTrue(result.getStaged().isEmpty());
            assertTrue(result.getUnstaged().isEmpty());
            assertEquals(List.of("new-file.txt"), result.getUntracked());
        }

        @Test
        void conflictedFileUU() {
            var result = R.categorizeFiles(List.of("UU conflict.java"));
            assertEquals(List.of("conflict.java"), result.getConflicted());
            assertTrue(result.getStaged().isEmpty());
        }

        @Test
        void conflictedFileAA() {
            var result = R.categorizeFiles(List.of("AA both-added.java"));
            assertEquals(List.of("both-added.java"), result.getConflicted());
        }

        @Test
        void conflictedFileDD() {
            var result = R.categorizeFiles(List.of("DD both-deleted.java"));
            assertEquals(List.of("both-deleted.java"), result.getConflicted());
        }

        @Test
        void stagedAddedFile() {
            var result = R.categorizeFiles(List.of("A  new.java"));
            assertEquals(1, result.getStaged().size());
            assertEquals(new Pair<>('A', "new.java"), result.getStaged().get(0));
        }

        @Test
        void stagedDeletedFile() {
            var result = R.categorizeFiles(List.of("D  old.java"));
            assertEquals(1, result.getStaged().size());
            assertEquals(new Pair<>('D', "old.java"), result.getStaged().get(0));
        }

        @Test
        void bothStagedAndUnstaged() {
            var result = R.categorizeFiles(List.of("MM both.java"));
            assertEquals(1, result.getStaged().size());
            assertEquals(new Pair<>('M', "both.java"), result.getStaged().get(0));
            assertEquals(1, result.getUnstaged().size());
            assertEquals(new Pair<>('M', "both.java"), result.getUnstaged().get(0));
        }

        @Test
        void skipsBranchLine() {
            var result = R.categorizeFiles(List.of("## main...origin/main", "M  file.java"));
            assertEquals(1, result.getStaged().size());
        }

        @Test
        void skipsBlankLines() {
            var result = R.categorizeFiles(List.of("", "  ", "M  file.java"));
            assertEquals(1, result.getStaged().size());
        }

        @Test
        void multipleFiles() {
            var result = R.categorizeFiles(List.of(
                "## main",
                "M  staged.java",
                " M unstaged.java",
                "?? untracked.java",
                "UU conflict.java"
            ));
            assertEquals(1, result.getStaged().size());
            assertEquals(1, result.getUnstaged().size());
            assertEquals(1, result.getUntracked().size());
            assertEquals(1, result.getConflicted().size());
        }

        @Test
        void emptyInput() {
            var result = R.categorizeFiles(List.of());
            assertTrue(result.getStaged().isEmpty());
            assertTrue(result.getUnstaged().isEmpty());
            assertTrue(result.getUntracked().isEmpty());
            assertTrue(result.getConflicted().isEmpty());
        }

        @Test
        void renamedFile() {
            var result = R.categorizeFiles(List.of("R  old.java -> new.java"));
            assertEquals(1, result.getStaged().size());
            assertEquals('R', result.getStaged().get(0).getFirst().charValue());
        }
    }
}
