package com.github.catatafishen.agentbridge.psi.tools.file;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for pure static methods in {@link WriteFileTool}:
 * {@code closestMatchHint} and {@code resolveAutoFormat}.
 */
class WriteFileToolStaticMethodsTest {

    // ── closestMatchHint ────────────────────────────────────

    @Test
    void closestMatchHint_findsMatchAtMiddleOfFile() {
        String text = "line1\nline2\nline3\nline4\ntarget line\nline6\nline7\nline8\nline9\nline10";
        String result = WriteFileTool.closestMatchHint(text, "target line");
        assertTrue(result.contains("Closest match found at line 5"), result);
        // Context should include lines around the match (L4–L8)
        assertTrue(result.contains("L4:"), "Should show context line before match");
        assertTrue(result.contains("L5:"), "Should show the matched line");
        assertTrue(result.contains("L8:"), "Should show context line after match");
    }

    @Test
    void closestMatchHint_returnsEmptyForAllBlankNormalizedOld() {
        assertEquals("", WriteFileTool.closestMatchHint("line1\nline2", "  \n  \n"));
    }

    @Test
    void closestMatchHint_returnsEmptyWhenNotFound() {
        assertEquals("", WriteFileTool.closestMatchHint("line1\nline2", "nonexistent"));
    }

    @Test
    void closestMatchHint_matchAtFirstLine() {
        String text = "function foo()\nrest of code\nmore code";
        String normalizedOld = "\n  \nfunction foo()";
        String result = WriteFileTool.closestMatchHint(text, normalizedOld);
        assertTrue(result.contains("Closest match found at line 1"), result);
        assertTrue(result.contains("L1:"), "Should show first line in context");
    }

    @Test
    void closestMatchHint_skipsLeadingBlankLinesInSearchText() {
        String text = "alpha\nbeta\ngamma";
        String result = WriteFileTool.closestMatchHint(text, "\n  \nbeta");
        assertTrue(result.contains("Closest match found at line 2"), result);
    }

    @Test
    void closestMatchHint_includesContextLines() {
        String text = "a\nb\ntarget\nd\ne\nf";
        String result = WriteFileTool.closestMatchHint(text, "target");
        // 1 line before, the match itself, and up to 3 lines after
        assertTrue(result.contains("L2:"), "Should include line before match");
        assertTrue(result.contains("L3:"), "Should include the match line");
        assertTrue(result.contains("L4:"), "Should include line after match");
    }

    // ── resolveAutoFormat ───────────────────────────────────

    @Test
    void resolveAutoFormat_primaryTrue() {
        JsonObject args = new JsonObject();
        args.addProperty("auto_format_and_optimize_imports", true);
        assertTrue(WriteFileTool.resolveAutoFormat(args));
    }

    @Test
    void resolveAutoFormat_primaryFalse() {
        JsonObject args = new JsonObject();
        args.addProperty("auto_format_and_optimize_imports", false);
        assertFalse(WriteFileTool.resolveAutoFormat(args));
    }

    @Test
    void resolveAutoFormat_legacyTrue() {
        JsonObject args = new JsonObject();
        args.addProperty("auto_format", true);
        assertTrue(WriteFileTool.resolveAutoFormat(args));
    }

    @Test
    void resolveAutoFormat_legacyFalse() {
        JsonObject args = new JsonObject();
        args.addProperty("auto_format", false);
        assertFalse(WriteFileTool.resolveAutoFormat(args));
    }

    @Test
    void resolveAutoFormat_defaultsToTrue() {
        assertTrue(WriteFileTool.resolveAutoFormat(new JsonObject()));
    }

    @Test
    void resolveAutoFormat_primaryOverridesLegacy() {
        JsonObject args = new JsonObject();
        args.addProperty("auto_format_and_optimize_imports", true);
        args.addProperty("auto_format", false);
        assertTrue(WriteFileTool.resolveAutoFormat(args));
    }

    @Test
    void resolveAutoFormat_primaryFalseOverridesLegacyTrue() {
        JsonObject args = new JsonObject();
        args.addProperty("auto_format_and_optimize_imports", false);
        args.addProperty("auto_format", true);
        assertFalse(WriteFileTool.resolveAutoFormat(args));
    }
}
