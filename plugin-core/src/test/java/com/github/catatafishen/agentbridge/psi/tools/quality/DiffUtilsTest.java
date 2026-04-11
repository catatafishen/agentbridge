package com.github.catatafishen.agentbridge.psi.tools.quality;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DiffUtils#unifiedDiff(String, String, String)}.
 *
 * <p>{@code DiffUtils} is a package-private utility class that generates unified diffs
 * using IntelliJ's {@code com.intellij.util.diff.Diff} algorithm. These tests run as
 * plain JUnit 5 tests (no {@code BasePlatformTestCase} required) because
 * {@code Diff.buildChanges} is a pure algorithm with no dependency on the IntelliJ
 * Application lifecycle.
 *
 * <p>Run via Gradle: {@code ./gradlew :plugin-core:test}.
 */
class DiffUtilsTest {

    // ── Identical content ─────────────────────────────────────────────────────

    /**
     * When both strings are the same, the diff must be empty (no changes to report).
     */
    @Test
    void testIdenticalStringsReturnEmptyDiff() {
        String result = DiffUtils.unifiedDiff("hello\n", "hello\n", "test.txt");
        assertEquals("", result, "Diff of identical strings must be empty");
    }

    /**
     * When both strings are completely empty, the diff must also be empty.
     */
    @Test
    void testBothEmptyStringsReturnEmptyDiff() {
        String result = DiffUtils.unifiedDiff("", "", "test.txt");
        assertEquals("", result, "Diff of two empty strings must be empty");
    }

    // ── Diff header format ────────────────────────────────────────────────────

    /**
     * For any non-trivial change the output must start with the standard
     * {@code diff --git a/<path> b/<path>} header so that {@code GitDiffRenderer}
     * can render it correctly.
     */
    @Test
    void testDiffStartsWithGitHeader() {
        String result = DiffUtils.unifiedDiff("foo\n", "bar\n", "src/Foo.java");
        assertTrue(result.startsWith("diff --git a/src/Foo.java b/src/Foo.java"),
            "Diff output must start with 'diff --git' header, got:\n" + result);
    }

    /**
     * The output must include {@code --- a/<filePath>} and {@code +++ b/<filePath>}
     * lines so that standard patch tools and renderers can identify the file.
     */
    @Test
    void testDiffContainsFromAndToFileHeaders() {
        String result = DiffUtils.unifiedDiff("line1\n", "changed\n", "com/example/MyClass.java");
        assertTrue(result.contains("--- a/com/example/MyClass.java"),
            "Diff must contain '--- a/<filePath>' header, got:\n" + result);
        assertTrue(result.contains("+++ b/com/example/MyClass.java"),
            "Diff must contain '+++ b/<filePath>' header, got:\n" + result);
    }

    /**
     * The hunk header must follow the {@code @@ -<from>,<count> +<to>,<count> @@} format.
     */
    @Test
    void testDiffContainsHunkHeader() {
        String result = DiffUtils.unifiedDiff("a\n", "b\n", "test.txt");
        assertTrue(result.contains("@@"),
            "Diff output must contain a hunk header starting with '@@', got:\n" + result);
    }

    // ── Changed lines ─────────────────────────────────────────────────────────

    /**
     * A single substituted line must produce a deletion line (starting with '-')
     * for the old content and an insertion line (starting with '+') for the new.
     */
    @Test
    void testSimpleSingleLineChange() {
        String result = DiffUtils.unifiedDiff("hello\n", "world\n", "greet.txt");
        assertFalse(result.isEmpty(), "Diff must be non-empty for a changed line");
        assertTrue(result.contains("-hello"),
            "Diff must mark removed line with '-', got:\n" + result);
        assertTrue(result.contains("+world"),
            "Diff must mark added line with '+', got:\n" + result);
    }

    /**
     * Adding a new line at the end of the file must produce an insertion marker
     * for that line and not mark the original lines as deleted.
     */
    @Test
    void testAddedLine() {
        String before = "line1\n";
        String after = "line1\nline2\n";
        String result = DiffUtils.unifiedDiff(before, after, "add.txt");
        assertFalse(result.isEmpty(), "Diff must be non-empty when a line is added");
        assertTrue(result.contains("+line2"),
            "Diff must contain '+line2' for the added line, got:\n" + result);
        assertFalse(result.contains("-line1"),
            "Diff must NOT mark 'line1' as deleted when it is unchanged, got:\n" + result);
    }

    /**
     * Removing a line must produce a deletion marker for that line
     * and not mark the remaining lines as added.
     */
    @Test
    void testDeletedLine() {
        String before = "line1\nline2\n";
        String after = "line1\n";
        String result = DiffUtils.unifiedDiff(before, after, "del.txt");
        assertFalse(result.isEmpty(), "Diff must be non-empty when a line is deleted");
        assertTrue(result.contains("-line2"),
            "Diff must contain '-line2' for the deleted line, got:\n" + result);
        assertFalse(result.contains("+line1"),
            "Diff must NOT mark 'line1' as added when it is unchanged, got:\n" + result);
    }

    /**
     * Changes to non-adjacent lines separated by more than {@code 2 * CONTEXT_LINES}
     * (= 6) unchanged lines must produce separate hunks, each with its own
     * {@code @@} header.
     */
    @Test
    void testDistantChangesProduceMultipleHunks() {
        StringBuilder before = new StringBuilder();
        StringBuilder after = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            before.append("line").append(i).append("\n");
            if (i == 1 || i == 20) {
                after.append("CHANGED").append(i).append("\n");
            } else {
                after.append("line").append(i).append("\n");
            }
        }
        String result = DiffUtils.unifiedDiff(before.toString(), after.toString(), "multi.txt");
        assertFalse(result.isEmpty(), "Diff must be non-empty for distant changes");
        long hunkCount = result.lines().filter(l -> l.startsWith("@@")).count();
        assertTrue(hunkCount >= 2,
            "Expected at least 2 hunk headers for distant changes, got " + hunkCount
                + " in:\n" + result);
    }

    /**
     * Adjacent changes (within the context window) must be grouped into a single hunk
     * rather than split across multiple {@code @@} sections.
     */
    @Test
    void testAdjacentChangesProduceSingleHunk() {
        String before = "aaa\nbbb\nccc\n";
        String after = "AAA\nBBB\nccc\n";
        String result = DiffUtils.unifiedDiff(before, after, "adjacent.txt");
        assertFalse(result.isEmpty(), "Diff must be non-empty for adjacent changes");
        long hunkCount = result.lines().filter(l -> l.startsWith("@@")).count();
        assertEquals(1, hunkCount,
            "Adjacent changes within context window must produce exactly 1 hunk, got "
                + hunkCount + " in:\n" + result);
    }

    // ── Context lines ─────────────────────────────────────────────────────────

    /**
     * Lines immediately surrounding a change (context lines) must appear in the diff
     * prefixed only with a space (neither '+' nor '-').
     */
    @Test
    void testContextLinesArePrefixedWithSpace() {
        String before = "ctx-before\nchanged\nctx-after\n";
        String after = "ctx-before\nNEW\nctx-after\n";
        String result = DiffUtils.unifiedDiff(before, after, "ctx.txt");
        assertTrue(result.contains(" ctx-before"),
            "Context line before change must be prefixed with space, got:\n" + result);
        assertTrue(result.contains(" ctx-after"),
            "Context line after change must be prefixed with space, got:\n" + result);
    }

    // ── File path in output ───────────────────────────────────────────────────

    /**
     * The supplied {@code filePath} must appear verbatim in all three header lines
     * so that callers can identify the source file without extra parsing.
     */
    @Test
    void testFilePathAppearsInAllHeaders() {
        String filePath = "src/main/java/com/example/Foo.java";
        String result = DiffUtils.unifiedDiff("old\n", "new\n", filePath);
        assertTrue(result.contains("diff --git a/" + filePath + " b/" + filePath),
            "Git header must contain the full file path, got:\n" + result);
        assertTrue(result.contains("--- a/" + filePath),
            "'---' header must contain the full file path, got:\n" + result);
        assertTrue(result.contains("+++ b/" + filePath),
            "'+++ ' header must contain the full file path, got:\n" + result);
    }

    // ── New-file / deleted-file edge cases ────────────────────────────────────

    /**
     * Adding content to a previously empty string must produce a diff with only
     * insertion lines and no deletion lines in the hunk body.
     */
    @Test
    void testNewFileFromEmpty() {
        String result = DiffUtils.unifiedDiff("", "brand new content\n", "new.txt");
        assertFalse(result.isEmpty(), "Diff must be non-empty when adding content to empty string");
        assertTrue(result.contains("+brand new content"),
            "Diff must contain the new content as an insertion, got:\n" + result);
    }

    /**
     * Deleting all content (reducing to an empty string) must produce a diff with only
     * deletion lines and no insertion lines in the hunk body.
     */
    @Test
    void testDeleteAllContent() {
        String result = DiffUtils.unifiedDiff("all gone\n", "", "gone.txt");
        assertFalse(result.isEmpty(), "Diff must be non-empty when all content is removed");
        assertTrue(result.contains("-all gone"),
            "Diff must mark deleted content with '-', got:\n" + result);
    }
}
