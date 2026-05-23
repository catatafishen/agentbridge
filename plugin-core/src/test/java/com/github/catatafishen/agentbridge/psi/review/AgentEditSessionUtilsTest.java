package com.github.catatafishen.agentbridge.psi.review;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentEditSessionUtilsTest {

    @Nested
    class ToAbsolutePath {
        @Test
        void returnsAbsolutePathUnchanged() {
            assertEquals("/home/user/file.txt", AgentEditSession.toAbsolutePath("/home/user/file.txt", "/base"));
        }

        @Test
        void joinsRelativePathWithBase() {
            assertEquals("/base/src/Main.java", AgentEditSession.toAbsolutePath("src/Main.java", "/base"));
        }

        @Test
        void returnsRelativePathWhenBaseIsNull() {
            assertEquals("src/Main.java", AgentEditSession.toAbsolutePath("src/Main.java", null));
        }

        @Test
        void handlesEmptyRelativePath() {
            assertEquals("/base/", AgentEditSession.toAbsolutePath("", "/base"));
        }
    }

    @Nested
    class CountLines {
        @Test
        void returnsZeroForNull() {
            assertEquals(0, AgentEditSession.countLines(null));
        }

        @Test
        void returnsZeroForEmpty() {
            assertEquals(0, AgentEditSession.countLines(""));
        }

        @Test
        void countsSingleLine() {
            assertEquals(1, AgentEditSession.countLines("hello"));
        }

        @Test
        void countsMultipleLines() {
            assertEquals(3, AgentEditSession.countLines("line1\nline2\nline3"));
        }

        @Test
        void countsLineWithTrailingNewline() {
            assertEquals(2, AgentEditSession.countLines("line1\nline2\n"));
        }
    }

    @Nested
    class FormatReviewTimeoutError {
        @Test
        void formatsSingleFile() {
            String result = AgentEditSession.formatReviewTimeoutError("write_file", 1);
            assertTrue(result.contains("write_file"));
            assertTrue(result.contains("1"));
        }

        @Test
        void formatsMultipleFiles() {
            String result = AgentEditSession.formatReviewTimeoutError("edit_text", 5);
            assertTrue(result.contains("edit_text"));
            assertTrue(result.contains("5"));
        }

        @Test
        void startsWithErrorPrefix() {
            String result = AgentEditSession.formatReviewTimeoutError("op", 1);
            assertTrue(result.startsWith("Error:"));
        }
    }
}
