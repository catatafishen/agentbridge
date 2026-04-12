package com.github.catatafishen.agentbridge.psi.tools.file;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for pure static methods in {@link WriteFileTool}.
 */
class WriteFileToolStaticMethodsTest {

    private static final Method CLOSEST_MATCH_HINT;
    private static final Method INDEX_OF;
    private static final Method RESOLVE_AUTO_FORMAT;

    static {
        try {
            CLOSEST_MATCH_HINT = WriteFileTool.class
                .getDeclaredMethod("closestMatchHint", String.class, String.class);
            CLOSEST_MATCH_HINT.setAccessible(true);

            INDEX_OF = WriteFileTool.class
                .getDeclaredMethod("indexOf", String.class, String.class, boolean.class);
            INDEX_OF.setAccessible(true);

            RESOLVE_AUTO_FORMAT = WriteFileTool.class
                .getDeclaredMethod("resolveAutoFormat", JsonObject.class);
            RESOLVE_AUTO_FORMAT.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("Method not found — signature changed?", e);
        }
    }

    // ── closestMatchHint ────────────────────────────────────

    @Test
    void closestMatchHint_findsLineContainingFirstNonBlankLine() throws Exception {
        String text = "line1\nfoo bar\nbaz qux\nend";
        String result = invokeClosestMatchHint(text, "foo bar");
        assertTrue(result.contains("Closest match found at line 2"), result);
        assertTrue(result.contains("foo bar"), result);
    }

    @Test
    void closestMatchHint_returnsEmptyWhenNoMatch() throws Exception {
        String text = "line1\nline2";
        assertEquals("", invokeClosestMatchHint(text, "nonexistent"));
    }

    @Test
    void closestMatchHint_returnsEmptyForAllBlankLines() throws Exception {
        assertEquals("", invokeClosestMatchHint("line1\nline2", "  \n  \n"));
    }

    @Test
    void closestMatchHint_skipsBlankLinesInSearchText() throws Exception {
        String text = "alpha\nbeta\ngamma";
        String result = invokeClosestMatchHint(text, "\n  \nbeta");
        assertTrue(result.contains("Closest match found at line 2"), result);
    }

    @Test
    void closestMatchHint_includesContext() throws Exception {
        String text = "a\nb\ntarget\nd\ne\nf";
        String result = invokeClosestMatchHint(text, "target");
        // Should include lines around the match (1 before, 3 after)
        assertTrue(result.contains("L2:"), "Should show line before match");
        assertTrue(result.contains("L3:"), "Should show match line");
        assertTrue(result.contains("L4:"), "Should show line after");
    }

    @Test
    void closestMatchHint_matchAtFirstLine() throws Exception {
        String text = "target\nsecond\nthird";
        String result = invokeClosestMatchHint(text, "target");
        assertTrue(result.contains("Closest match found at line 1"), result);
        assertTrue(result.contains("L1:"), "Should show first line");
    }

    // ── indexOf ─────────────────────────────────────────────

    @Test
    void indexOf_caseSensitive() throws Exception {
        assertEquals(0, invokeIndexOf("Hello World", "Hello", true));
        assertEquals(-1, invokeIndexOf("Hello World", "hello", true));
    }

    @Test
    void indexOf_caseInsensitive() throws Exception {
        assertEquals(0, invokeIndexOf("Hello World", "hello", false));
        assertEquals(6, invokeIndexOf("Hello World", "world", false));
    }

    @Test
    void indexOf_notFound() throws Exception {
        assertEquals(-1, invokeIndexOf("Hello", "xyz", true));
        assertEquals(-1, invokeIndexOf("Hello", "xyz", false));
    }

    // ── resolveAutoFormat ───────────────────────────────────

    @Test
    void resolveAutoFormat_defaultsToTrue() throws Exception {
        assertTrue(invokeResolveAutoFormat(new JsonObject()));
    }

    @Test
    void resolveAutoFormat_respectsPrimaryParam() throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty("auto_format_and_optimize_imports", false);
        assertFalse(invokeResolveAutoFormat(args));
    }

    @Test
    void resolveAutoFormat_fallsBackToLegacyParam() throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty("auto_format", false);
        assertFalse(invokeResolveAutoFormat(args));
    }

    @Test
    void resolveAutoFormat_primaryOverridesLegacy() throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty("auto_format_and_optimize_imports", true);
        args.addProperty("auto_format", false);
        assertTrue(invokeResolveAutoFormat(args));
    }

    // ── Reflection helpers ──────────────────────────────────

    private static String invokeClosestMatchHint(String text, String normalizedOld) throws Exception {
        return (String) CLOSEST_MATCH_HINT.invoke(null, text, normalizedOld);
    }

    private static int invokeIndexOf(String text, String target, boolean caseSensitive) throws Exception {
        return (int) INDEX_OF.invoke(null, text, target, caseSensitive);
    }

    private static boolean invokeResolveAutoFormat(JsonObject args) throws Exception {
        return (boolean) RESOLVE_AUTO_FORMAT.invoke(null, args);
    }
}
