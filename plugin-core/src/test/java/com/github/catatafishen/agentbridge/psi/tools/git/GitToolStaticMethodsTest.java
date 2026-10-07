package com.github.catatafishen.agentbridge.psi.tools.git;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitToolStaticMethodsTest {

    private static final String HASH_40 = "a1b2c3d4e5f6789012345678901234567890abcd";

    @Nested
    class NormalizeRepoParam {

        @Test
        void dotResolvesToProjectBasePath() {
            assertEquals("/work/project", GitTool.normalizeRepoParam(".", "/work/project"));
        }

        @Test
        void relativeSubmoduleResolvesUnderBasePath() {
            assertEquals("/work/project/.agents", GitTool.normalizeRepoParam(".agents", "/work/project"));
        }

        @Test
        void absolutePathIsKept() {
            assertEquals("/other/repo", GitTool.normalizeRepoParam("/other/repo", "/work/project"));
        }
    }

    @Nested
    class CreateBranchArgs {

        @Test
        void remoteBaseIsNotTracked() {
            assertArrayEquals(new String[]{"checkout", "--no-track", "-b", "feat/x", "origin/master"},
                GitBranchTool.createBranchArgs("feat/x", "origin/master"));
        }

        @Test
        void noBaseBranchesFromHead() {
            assertArrayEquals(new String[]{"checkout", "-b", "feat/x"},
                GitBranchTool.createBranchArgs("feat/x", null));
        }
    }

    @Nested
    class FormatStaleBaseWarning {

        @Test
        void warnsWhenBaseIsBehindUpstreamAndSaysNoFetchWasRun() {
            String warning = GitBranchTool.formatStaleBaseWarning("master", "origin/master", "3\n");
            assertTrue(warning.contains("'master' is 3 commit(s) behind its upstream 'origin/master'"), warning);
            assertTrue(warning.contains("no fetch was run"), warning);
            assertTrue(warning.contains("base: 'origin/master'"), warning);
        }

        @Test
        void silentWhenUpToDate() {
            assertEquals("", GitBranchTool.formatStaleBaseWarning("master", "origin/master", "0"));
        }

        @Test
        void silentWhenBaseHasNoUpstreamOrGitFailed() {
            assertEquals("", GitBranchTool.formatStaleBaseWarning("origin/master", null, null));
            assertEquals("", GitBranchTool.formatStaleBaseWarning("master", "origin/master", null));
            assertEquals("", GitBranchTool.formatStaleBaseWarning("master", null, "2"));
        }

        @Test
        void silentWhenCountIsNotANumber() {
            assertEquals("", GitBranchTool.formatStaleBaseWarning("master", "origin/master", "fatal: bad"));
        }
    }

    // ── formatPorcelainStatus ───────────────────────────────

    @Nested
    class FormatPorcelainStatus {

        @Test
        void singleStagedFile() {
            assertEquals("1 staged", GitTool.formatPorcelainStatus("M  foo.java\n"));
        }

        @Test
        void singleModifiedFile() {
            assertEquals("1 modified", GitTool.formatPorcelainStatus(" M foo.java\n"));
        }

        @Test
        void singleUntrackedFile() {
            assertEquals("1 untracked", GitTool.formatPorcelainStatus("?? untracked.txt\n"));
        }

        @Test
        void stagedAndModifiedSameFile() {
            // index='M', worktree='M' — counts as both staged and modified
            assertEquals("1 staged, 1 modified", GitTool.formatPorcelainStatus("MM both.java\n"));
        }

        @Test
        void mixedStatus() {
            String porcelain = "A  new.java\n M old.java\n?? unk.txt\n";
            assertEquals("1 staged, 1 modified, 1 untracked", GitTool.formatPorcelainStatus(porcelain));
        }

        @Test
        void addedFile() {
            assertEquals("1 staged", GitTool.formatPorcelainStatus("A  added.java\n"));
        }

        @Test
        void deletedInIndex() {
            assertEquals("1 staged", GitTool.formatPorcelainStatus("D  removed.java\n"));
        }

        @Test
        void deletedInWorktree() {
            assertEquals("1 modified", GitTool.formatPorcelainStatus(" D removed.java\n"));
        }

        @Test
        void singleCharLineSkipped() {
            // A line with fewer than 2 characters should be skipped
            assertEquals("", GitTool.formatPorcelainStatus("X\n"));
        }

        @Test
        void emptyStringProducesEmptyResult() {
            // Empty string splits to [""], which has length < 2, so skipped
            assertEquals("", GitTool.formatPorcelainStatus(""));
        }

        @Test
        void multipleUntrackedFiles() {
            String porcelain = "?? a.txt\n?? b.txt\n?? c.txt\n";
            assertEquals("3 untracked", GitTool.formatPorcelainStatus(porcelain));
        }
    }

    // ── countStashEntries ───────────────────────────────────

    @Nested
    class CountStashEntries {

        @Test
        void singleEntryWithTrailingNewline() {
            assertEquals(1, GitTool.countStashEntries("stash@{0}: WIP on main\n"));
        }

        @Test
        void threeEntriesWithTrailingNewline() {
            assertEquals(3, GitTool.countStashEntries("a\nb\nc\n"));
        }

        @Test
        void threeEntriesWithoutTrailingNewline() {
            assertEquals(3, GitTool.countStashEntries("a\nb\nc"));
        }

        @Test
        void singleLineNoNewline() {
            assertEquals(1, GitTool.countStashEntries("single"));
        }

        @Test
        void singleLineWithNewline() {
            assertEquals(1, GitTool.countStashEntries("a\n"));
        }

        @Test
        void emptyStringReturnsZero() {
            assertEquals(0, GitTool.countStashEntries(""));
        }
    }

    // ── extractFirstCommitHash ──────────────────────────────

    @Nested
    class ExtractFirstCommitHash {

        @Test
        void nullInput() {
            assertNull(GitTool.extractFirstCommitHash(null));
        }

        @Test
        void emptyInput() {
            assertNull(GitTool.extractFirstCommitHash(""));
        }

        @Test
        void commitLineFormat() {
            String input = "commit " + HASH_40 + "\nAuthor: Test\nDate: today";
            assertEquals(HASH_40, GitTool.extractFirstCommitHash(input));
        }

        @Test
        void standaloneHashInText() {
            String input = "some text " + HASH_40 + " more text";
            assertEquals(HASH_40, GitTool.extractFirstCommitHash(input));
        }

        @Test
        void noHashPresent() {
            assertNull(GitTool.extractFirstCommitHash("no hash here"));
        }

        @Test
        void prefersCommitLineOverStandaloneHash() {
            String commitHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
            String standaloneHash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
            String input = "found " + standaloneHash + " here\ncommit " + commitHash + "\nmore";
            assertEquals(commitHash, GitTool.extractFirstCommitHash(input));
        }

        @Test
        void hashTooShortIgnored() {
            assertNull(GitTool.extractFirstCommitHash("abcdef1234567890"));
        }
    }

    // ── Pattern fields ──────────────────────────────────────

// ── toRelativePath ──────────────────────────────────────

    @Nested
    class ToRelativePath {

        @Test
        void samePathReturnsDot() {
            assertEquals(".", GitTool.toRelativePath("/project/root", "/project/root"));
        }

        @Test
        void childPathReturnsRelative() {
            assertEquals("backend", GitTool.toRelativePath("/project/root/backend", "/project/root"));
        }

        @Test
        void deepChildReturnsFullRelative() {
            assertEquals("a/b/c", GitTool.toRelativePath("/project/root/a/b/c", "/project/root"));
        }

        @Test
        void nullBasePathReturnsAbsolute() {
            assertEquals("/some/absolute/path", GitTool.toRelativePath("/some/absolute/path", null));
        }

        @Test
        void pathOutsideBaseReturnsAbsolute() {
            assertEquals("/other/repo", GitTool.toRelativePath("/other/repo", "/project/root"));
        }

        @Test
        void pathWithCommonPrefixButNotChildReturnsAbsolute() {
            // "/project/rootother" must NOT match base "/project/root"
            assertEquals("/project/rootother",
                GitTool.toRelativePath("/project/rootother", "/project/root"));
        }
    }

    @Nested
    class Patterns {

        @Test
        void fullHashPatternMatches40HexChars() {
            Matcher m = GitTool.FULL_HASH_PATTERN.matcher(HASH_40);
            assertTrue(m.find());
            assertEquals(HASH_40, m.group());
        }

        @Test
        void fullHashPatternRejectsShortHex() {
            Matcher m = GitTool.FULL_HASH_PATTERN.matcher("abcdef1234567890");
            assertFalse(m.find());
        }

        @Test
        void commitLinePatternMatchesCommitLine() {
            Matcher m = GitTool.COMMIT_LINE_PATTERN.matcher("commit " + HASH_40);
            assertTrue(m.find());
            assertEquals(HASH_40, m.group(1));
        }

        @Test
        void commitLinePatternRejectsNonCommitLine() {
            Matcher m = GitTool.COMMIT_LINE_PATTERN.matcher("author " + HASH_40);
            assertFalse(m.find());
        }
    }
}
