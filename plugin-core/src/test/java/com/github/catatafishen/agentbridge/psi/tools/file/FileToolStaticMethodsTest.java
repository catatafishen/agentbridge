package com.github.catatafishen.agentbridge.psi.tools.file;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for pure static methods in {@link FileTool}.
 */
class FileToolStaticMethodsTest {

    @Nested
    class ParseGitPorcelainLine {

        @Test
        void untracked() {
            assertEquals(" [git: untracked]", FileTool.parseGitPorcelainLine("?? file.txt"));
        }

        @Test
        void newFileStaged() {
            assertEquals(" [git: new file, staged]", FileTool.parseGitPorcelainLine("A  file.txt"));
        }

        @Test
        void modifiedStaged() {
            // M in index, space in work-tree → generic "staged"
            assertEquals(" [git: staged]", FileTool.parseGitPorcelainLine("M  file.txt"));
        }

        @Test
        void modifiedNotStaged() {
            assertEquals(" [git: modified, not staged]", FileTool.parseGitPorcelainLine(" M file.txt"));
        }

        @Test
        void partiallyStaged() {
            assertEquals(" [git: partially staged]", FileTool.parseGitPorcelainLine("MM file.txt"));
        }

        @Test
        void deletedStaged() {
            // D in index, space in work-tree → generic "staged"
            assertEquals(" [git: staged]", FileTool.parseGitPorcelainLine("D  file.txt"));
        }

        @Test
        void deletedNotStaged() {
            assertEquals(" [git: deleted]", FileTool.parseGitPorcelainLine(" D file.txt"));
        }

        @Test
        void renamedStaged() {
            // R in index, space in work-tree → generic "staged"
            assertEquals(" [git: staged]", FileTool.parseGitPorcelainLine("R  file.txt -> new.txt"));
        }

        @Test
        void ignoredFallsThrough() {
            // !! is not matched by any specific branch — falls to the default
            assertEquals(" [git: !!]", FileTool.parseGitPorcelainLine("!! file.txt"));
        }

        @Test
        void emptyStringMeansClean() {
            assertEquals(" [git: clean]", FileTool.parseGitPorcelainLine(""));
        }

        @Test
        void shortStringEdgeCase() {
            // Single character — too short for two-char status code
            assertEquals(" [git: X]", FileTool.parseGitPorcelainLine("X"));
        }

        @Test
        void twoSpacesFallsThrough() {
            // Both index and work-tree are space — no condition matches; falls to default
            assertEquals(" [git: ]", FileTool.parseGitPorcelainLine("   file.txt"));
        }

        @Test
        void newFileModifiedSinceStaging() {
            // A in index is matched first, regardless of work-tree state
            assertEquals(" [git: new file, staged]", FileTool.parseGitPorcelainLine("AM file.txt"));
        }
    }
}
