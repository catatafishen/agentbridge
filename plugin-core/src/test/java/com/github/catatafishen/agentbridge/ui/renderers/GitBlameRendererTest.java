package com.github.catatafishen.agentbridge.ui.renderers;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests regex patterns and parse methods in {@link GitBlameRenderer}.
 */
class GitBlameRendererTest {

    private static final GitBlameRenderer R = GitBlameRenderer.INSTANCE;

    @Nested
    class BlameLinePattern {

        @Test
        void matchesStandardBlameLine() {
            String line = "abc1234 (John Doe 2024-01-15 14:30:00 +0100 42) some code";
            assertTrue(R.getBLAME_LINE().containsMatchIn(line));
        }

        @Test
        void doesNotMatchPlainText() {
            assertFalse(R.getBLAME_LINE().containsMatchIn("just a normal line"));
        }

        @Test
        void doesNotMatchEmptyString() {
            assertFalse(R.getBLAME_LINE().containsMatchIn(""));
        }
    }

    @Nested
    class ParseBlameLine {

        @Test
        void parsesTypicalBlameOutput() {
            String line = "abc1234 (John Doe 2024-01-15 14:30:00 +0100 42) some code here";
            GitBlameRenderer.BlameEntry entry = R.parseBlameLine(line);

            assertNotNull(entry);
            assertEquals("abc1234", entry.getHash());
            assertEquals("John Doe", entry.getAuthor());
            assertEquals("2024-01-15", entry.getDate());
            assertEquals("42", entry.getLineNum());
            assertEquals("some code here", entry.getContent());
        }

        @Test
        void parsesMultiWordAuthorName() {
            String line = "f00bead (Mary Jane Watson 2025-03-10 09:15:30 -0500 7) import java.util.List;";
            GitBlameRenderer.BlameEntry entry = R.parseBlameLine(line);

            assertNotNull(entry);
            assertEquals("Mary Jane Watson", entry.getAuthor());
            assertEquals("f00bead", entry.getHash());
            assertEquals("2025-03-10", entry.getDate());
            assertEquals("7", entry.getLineNum());
            assertEquals("import java.util.List;", entry.getContent());
        }

        @Test
        void parsesLineWithEmptyContent() {
            String line = "deadbeef (Alice 2024-06-01 12:00:00 +0000 100) ";
            GitBlameRenderer.BlameEntry entry = R.parseBlameLine(line);

            assertNotNull(entry);
            assertEquals("deadbeef", entry.getHash());
            assertEquals("Alice", entry.getAuthor());
            assertEquals("100", entry.getLineNum());
            assertEquals("", entry.getContent());
        }

        @Test
        void parsesLineWithNoSpaceBeforeContent() {
            // Content directly follows ")" with no trailing space
            String line = "abcd123 (Bob 2024-02-20 08:00:00 +0000 5)";
            GitBlameRenderer.BlameEntry entry = R.parseBlameLine(line);

            assertNotNull(entry);
            assertEquals("abcd123", entry.getHash());
            assertEquals("Bob", entry.getAuthor());
            assertEquals("5", entry.getLineNum());
            assertEquals("", entry.getContent());
        }

        @Test
        void returnsNullForNonBlameLine() {
            assertNull(R.parseBlameLine("This is just a regular line"));
        }

        @Test
        void returnsNullForEmptyString() {
            assertNull(R.parseBlameLine(""));
        }

        @Test
        void returnsNullForPartialMatch() {
            assertNull(R.parseBlameLine("abc1234 (no date here 42) code"));
        }
    }

    @Nested
    class AbbreviateAuthor {

        @Test
        void shortNameUnchanged() {
            // "John Doe" is 8 chars, well under 12
            assertEquals("John Doe", R.abbreviateAuthor("John Doe"));
        }

        @Test
        void exactlyTwelveCharsUnchanged() {
            // Exactly 12 chars should not be truncated
            String name = "abcdefghijkl"; // 12 chars
            assertEquals(12, name.length());
            assertEquals(name, R.abbreviateAuthor(name));
        }

        @Test
        void thirteenCharsTruncated() {
            // 13 chars → first 10 + "…"
            String name = "abcdefghijklm"; // 13 chars
            assertEquals(13, name.length());
            assertEquals("abcdefghij…", R.abbreviateAuthor(name));
        }

        @Test
        void longNameTruncated() {
            String name = "Christopher Robinson";
            assertEquals("Christophe…", R.abbreviateAuthor(name));
        }

        @Test
        void singleCharUnchanged() {
            assertEquals("A", R.abbreviateAuthor("A"));
        }

        @Test
        void emptyNameUnchanged() {
            assertEquals("", R.abbreviateAuthor(""));
        }
    }
}
