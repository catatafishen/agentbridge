package com.github.catatafishen.agentbridge.psi.tools.file;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for pure static methods in {@link WriteFileTool}:
 * {@code closestMatchHint}, {@code resolveAutoFormat}, and {@code indexOf}.
 */
class WriteFileToolStaticMethodsTest {

    // ── closestMatchHint ────────────────────────────────────

    @Nested
    class ClosestMatchHint {

        @Test
        void findsMatchAtMiddleOfFile() {
            String text = "line1\nline2\nline3\nline4\ntarget line\nline6\nline7\nline8\nline9\nline10";
            String result = WriteFileTool.closestMatchHint(text, "target line");
            assertTrue(result.contains("Closest match found at line 5"), result);
            // Context should include lines around the match (L4–L8)
            assertTrue(result.contains("L4:"), "Should show context line before match");
            assertTrue(result.contains("L5:"), "Should show the matched line");
            assertTrue(result.contains("L8:"), "Should show context line after match");
        }

        @Test
        void returnsEmptyForAllBlankNormalizedOld() {
            assertEquals("", WriteFileTool.closestMatchHint("line1\nline2", "  \n  \n"));
        }

        @Test
        void returnsEmptyWhenNotFound() {
            assertEquals("", WriteFileTool.closestMatchHint("line1\nline2", "nonexistent"));
        }

        @Test
        void matchAtFirstLine() {
            String text = "function foo()\nrest of code\nmore code";
            String normalizedOld = "\n  \nfunction foo()";
            String result = WriteFileTool.closestMatchHint(text, normalizedOld);
            assertTrue(result.contains("Closest match found at line 1"), result);
            assertTrue(result.contains("L1:"), "Should show first line in context");
        }

        @Test
        void skipsLeadingBlankLinesInSearchText() {
            String text = "alpha\nbeta\ngamma";
            String result = WriteFileTool.closestMatchHint(text, "\n  \nbeta");
            assertTrue(result.contains("Closest match found at line 2"), result);
        }

        @Test
        void includesContextLines() {
            String text = "a\nb\ntarget\nd\ne\nf";
            String result = WriteFileTool.closestMatchHint(text, "target");
            // 1 line before, the match itself, and up to 3 lines after
            assertTrue(result.contains("L2:"), "Should include line before match");
            assertTrue(result.contains("L3:"), "Should include the match line");
            assertTrue(result.contains("L4:"), "Should include line after match");
        }

        @Test
        void matchAtLastLine() {
            String text = "first\nsecond\nlast target";
            String result = WriteFileTool.closestMatchHint(text, "last target");
            assertTrue(result.contains("Closest match found at line 3"), result);
            // Context should include lines 2–3 (can't go beyond end)
            assertTrue(result.contains("L2:"), "Should include line before match");
            assertTrue(result.contains("L3:"), "Should include the matched line");
        }

        @Test
        void multipleMatchesReturnsFirst() {
            String text = "target\nother\ntarget";
            String result = WriteFileTool.closestMatchHint(text, "target");
            // Should find line 1, not line 3
            assertTrue(result.contains("Closest match found at line 1"), result);
        }

        @Test
        void singleLineBothTextAndSearch() {
            String text = "only line here";
            String result = WriteFileTool.closestMatchHint(text, "only line here");
            assertTrue(result.contains("Closest match found at line 1"), result);
            assertTrue(result.contains("L1:"), result);
        }

        @Test
        void emptyText() {
            assertEquals("", WriteFileTool.closestMatchHint("", "something"));
        }

        @Test
        void partialSubstringMatch() {
            // closestMatchHint uses contains(), so a substring should match
            String text = "public void doSomething() {\n  int x = 1;\n}";
            String result = WriteFileTool.closestMatchHint(text, "doSomething");
            assertTrue(result.contains("Closest match found at line 1"), result);
        }
    }

    // ── resolveAutoFormat ───────────────────────────────────

    @Nested
    class ResolveAutoFormat {

        @Test
        void primaryTrue() {
            JsonObject args = new JsonObject();
            args.addProperty("auto_format_and_optimize_imports", true);
            assertTrue(WriteFileTool.resolveAutoFormat(args));
        }

        @Test
        void primaryFalse() {
            JsonObject args = new JsonObject();
            args.addProperty("auto_format_and_optimize_imports", false);
            assertFalse(WriteFileTool.resolveAutoFormat(args));
        }

        @Test
        void legacyTrue() {
            JsonObject args = new JsonObject();
            args.addProperty("auto_format", true);
            assertTrue(WriteFileTool.resolveAutoFormat(args));
        }

        @Test
        void legacyFalse() {
            JsonObject args = new JsonObject();
            args.addProperty("auto_format", false);
            assertFalse(WriteFileTool.resolveAutoFormat(args));
        }

        @Test
        void defaultsToTrue() {
            assertTrue(WriteFileTool.resolveAutoFormat(new JsonObject()));
        }

        @Test
        void primaryOverridesLegacy() {
            JsonObject args = new JsonObject();
            args.addProperty("auto_format_and_optimize_imports", true);
            args.addProperty("auto_format", false);
            assertTrue(WriteFileTool.resolveAutoFormat(args));
        }

        @Test
        void primaryFalseOverridesLegacyTrue() {
            JsonObject args = new JsonObject();
            args.addProperty("auto_format_and_optimize_imports", false);
            args.addProperty("auto_format", true);
            assertFalse(WriteFileTool.resolveAutoFormat(args));
        }
    }

    // ── indexOf ─────────────────────────────────────────────

    @Nested
    class IndexOf {

        @Test
        void caseSensitiveExactMatch() {
            assertEquals(0, WriteFileTool.indexOf("Hello World", "Hello", true));
        }

        @Test
        void caseSensitiveNoMatch() {
            assertEquals(-1, WriteFileTool.indexOf("Hello World", "hello", true));
        }

        @Test
        void caseInsensitiveMatch() {
            assertEquals(0, WriteFileTool.indexOf("Hello World", "hello", false));
        }

        @Test
        void caseInsensitiveMidString() {
            assertEquals(6, WriteFileTool.indexOf("Hello WORLD", "world", false));
        }

        @Test
        void caseSensitiveEmptyTarget() {
            assertEquals(0, WriteFileTool.indexOf("anything", "", true));
        }

        @Test
        void caseInsensitiveEmptyTarget() {
            assertEquals(0, WriteFileTool.indexOf("anything", "", false));
        }

        @Test
        void caseSensitiveNotPresent() {
            assertEquals(-1, WriteFileTool.indexOf("abc", "xyz", true));
        }

        @Test
        void caseInsensitiveNotPresent() {
            assertEquals(-1, WriteFileTool.indexOf("abc", "xyz", false));
        }

        @Test
        void caseSensitiveMultipleOccurrencesReturnsFirst() {
            assertEquals(0, WriteFileTool.indexOf("abcabc", "abc", true));
        }

        @Test
        void caseInsensitiveMixedCase() {
            assertEquals(0, WriteFileTool.indexOf("AbCdEf", "abcdef", false));
        }

        @Test
        void caseSensitiveTargetLongerThanText() {
            assertEquals(-1, WriteFileTool.indexOf("ab", "abcdef", true));
        }

        @Test
        void multiLineTarget() {
            String text = "line1\nline2\nline3\n";
            assertEquals(6, WriteFileTool.indexOf(text, "line2\nline3", true));
        }
    }
}
