package com.github.catatafishen.agentbridge.ui.renderers;

import kotlin.text.MatchResult;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests regex patterns and extractDiff in {@link WriteFileRenderer}.
 */
class WriteFileRendererTest {

    private static final WriteFileRenderer R = WriteFileRenderer.INSTANCE;

    @Nested
    class WrittenPattern {

        @Test
        void matchesWrittenOutput() {
            MatchResult match = R.getWRITTEN().find("Written: src/Foo.java (1234 chars)", 0);

            assertNotNull(match);
            assertEquals("src/Foo.java", match.getGroupValues().get(1));
            assertEquals("1234", match.getGroupValues().get(2));
        }

        @Test
        void matchesPathWithSpaces() {
            MatchResult match = R.getWRITTEN().find("Written: src/My File.java (500 chars)", 0);

            assertNotNull(match);
            assertEquals("src/My File.java", match.getGroupValues().get(1));
            assertEquals("500", match.getGroupValues().get(2));
        }

        @Test
        void doesNotMatchCreated() {
            assertFalse(R.getWRITTEN().containsMatchIn("Created: src/Foo.java"));
        }
    }

    @Nested
    class CreatedPattern {

        @Test
        void matchesCreatedOutput() {
            MatchResult match = R.getCREATED().find("Created: src/Bar.java", 0);

            assertNotNull(match);
            assertEquals("src/Bar.java", match.getGroupValues().get(1));
        }

        @Test
        void matchesNestedPath() {
            MatchResult match = R.getCREATED().find("Created: src/main/kotlin/Foo.kt", 0);

            assertNotNull(match);
            assertEquals("src/main/kotlin/Foo.kt", match.getGroupValues().get(1));
        }

        @Test
        void doesNotMatchWritten() {
            assertFalse(R.getCREATED().containsMatchIn("Written: src/Foo.java (100 chars)"));
        }
    }

    @Nested
    class EditedCharsPattern {

        @Test
        void matchesEditedCharsOutput() {
            MatchResult match = R.getEDITED_CHARS().find(
                "Edited: src/X.java (replaced 50 chars with 75 chars)", 0);

            assertNotNull(match);
            assertEquals("src/X.java", match.getGroupValues().get(1));
            assertEquals("50", match.getGroupValues().get(2));
            assertEquals("75", match.getGroupValues().get(3));
        }

        @Test
        void matchesLargeCharCounts() {
            MatchResult match = R.getEDITED_CHARS().find(
                "Edited: build.gradle.kts (replaced 12345 chars with 67890 chars)", 0);

            assertNotNull(match);
            assertEquals("build.gradle.kts", match.getGroupValues().get(1));
            assertEquals("12345", match.getGroupValues().get(2));
            assertEquals("67890", match.getGroupValues().get(3));
        }

        @Test
        void doesNotMatchEditedLines() {
            assertFalse(R.getEDITED_CHARS().containsMatchIn(
                "Edited: src/X.java (replaced lines 10-20 (11 lines) with 500 chars)"));
        }
    }

    @Nested
    class EditedLinesPattern {

        @Test
        void matchesEditedLinesOutput() {
            MatchResult match = R.getEDITED_LINES().find(
                "Edited: src/X.java (replaced lines 10-20 (11 lines) with 500 chars)", 0);

            assertNotNull(match);
            assertEquals("src/X.java", match.getGroupValues().get(1));
            assertEquals("10", match.getGroupValues().get(2));
            assertEquals("20", match.getGroupValues().get(3));
            assertEquals("11", match.getGroupValues().get(4));
            assertEquals("500", match.getGroupValues().get(5));
        }

        @Test
        void matchesSingleLine() {
            MatchResult match = R.getEDITED_LINES().find(
                "Edited: Foo.kt (replaced lines 5-5 (1 line) with 100 chars)", 0);

            assertNotNull(match);
            assertEquals("Foo.kt", match.getGroupValues().get(1));
            assertEquals("5", match.getGroupValues().get(2));
            assertEquals("5", match.getGroupValues().get(3));
            assertEquals("1", match.getGroupValues().get(4));
            assertEquals("100", match.getGroupValues().get(5));
        }
    }

    @Nested
    class ContextLinePattern {

        @Test
        void matchesContextLine() {
            MatchResult match = R.getCONTEXT_LINE().find("42: some code", 0);

            assertNotNull(match);
            assertEquals("42", match.getGroupValues().get(1));
            assertEquals("some code", match.getGroupValues().get(2));
        }

        @Test
        void matchesLineWithLeadingSpaces() {
            MatchResult match = R.getCONTEXT_LINE().find("1:     indented code", 0);

            assertNotNull(match);
            assertEquals("1", match.getGroupValues().get(1));
            assertEquals("    indented code", match.getGroupValues().get(2));
        }

        @Test
        void doesNotMatchWithoutColon() {
            assertNull(R.getCONTEXT_LINE().find("no colon here", 0));
        }
    }

    @Nested
    class SyntaxWarningPattern {

        @Test
        void matchesWarning() {
            assertTrue(R.getSYNTAX_WARNING().containsMatchIn("WARNING: something wrong"));
        }

        @Test
        void matchesWarningWithDetails() {
            assertTrue(R.getSYNTAX_WARNING().containsMatchIn(
                "WARNING: Unresolved reference 'foo' at line 42"));
        }

        @Test
        void doesNotMatchWithoutPrefix() {
            assertFalse(R.getSYNTAX_WARNING().containsMatchIn("something wrong"));
        }
    }

    @Nested
    class ExtractDiff {

        @Test
        void parsesValidJson() {
            String json = "{\"old_str\": \"foo\", \"new_str\": \"bar\"}";
            WriteFileRenderer.DiffContent diff = R.extractDiff(json);

            assertNotNull(diff);
            assertEquals("foo", diff.getOldStr());
            assertEquals("bar", diff.getNewStr());
        }

        @Test
        void returnsNullForNull() {
            assertNull(R.extractDiff(null));
        }

        @Test
        void returnsNullForBlank() {
            assertNull(R.extractDiff("   "));
        }

        @Test
        void returnsNullForEmptyString() {
            assertNull(R.extractDiff(""));
        }

        @Test
        void returnsNullWhenMissingOldStr() {
            assertNull(R.extractDiff("{\"new_str\": \"bar\"}"));
        }

        @Test
        void returnsNullWhenMissingNewStr() {
            assertNull(R.extractDiff("{\"old_str\": \"foo\"}"));
        }

        @Test
        void returnsNullForInvalidJson() {
            assertNull(R.extractDiff("not json at all"));
        }

        @Test
        void returnsNullWhenBothEmpty() {
            assertNull(R.extractDiff("{\"old_str\": \"  \", \"new_str\": \"  \"}"));
        }

        @Test
        void parsesMultilineContent() {
            String json = "{\"old_str\": \"line1\\nline2\", \"new_str\": \"line1\\nline2\\nline3\"}";
            WriteFileRenderer.DiffContent diff = R.extractDiff(json);

            assertNotNull(diff);
            assertEquals("line1\nline2", diff.getOldStr());
            assertEquals("line1\nline2\nline3", diff.getNewStr());
        }
    }
}
