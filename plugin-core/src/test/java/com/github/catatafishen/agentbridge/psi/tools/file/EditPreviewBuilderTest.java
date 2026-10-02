package com.github.catatafishen.agentbridge.psi.tools.file;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Preview computation for the Ask-permission bubble of {@code write_file} /
 * {@code edit_text}: argument precedence, edit semantics mirrored from
 * {@link WriteFileTool}, and the bounded diff/stats helpers.
 */
class EditPreviewBuilderTest {

    private static JsonObject args() {
        return new JsonObject();
    }

    // ── buildPreview: argument precedence ─────────────────────────────────────

    @Test
    @DisplayName("content wins over old_str/new_str and start_line")
    void contentPrecedence() {
        JsonObject a = args();
        a.addProperty("path", "p/A.java");
        a.addProperty("content", "new full");
        a.addProperty("old_str", "irrelevant");
        a.addProperty("new_str", "also irrelevant");
        a.addProperty("start_line", 1);
        EditPreviewBuilder.Preview p = EditPreviewBuilder.buildPreview(a, "old full");
        assertNotNull(p);
        assertEquals("new full", p.newText());
        assertEquals("old full", p.oldText());
    }

    @Test
    @DisplayName("old_str/new_str wins over start_line when content is absent")
    void partialEditPrecedence() {
        JsonObject a = args();
        a.addProperty("path", "p/A.java");
        a.addProperty("old_str", "one");
        a.addProperty("new_str", "two");
        a.addProperty("start_line", 1);
        EditPreviewBuilder.Preview p = EditPreviewBuilder.buildPreview(a, "one line here");
        assertNotNull(p);
        assertEquals("two line here", p.newText());
    }

    @Test
    @DisplayName("start_line/end_line range replace mirrors the tool semantics")
    void lineRangeReplace() {
        JsonObject a = args();
        a.addProperty("path", "p/A.java");
        a.addProperty("start_line", 2);
        a.addProperty("end_line", 3);
        a.addProperty("new_str", "replaced");
        String current = "line1\nline2\nline3\nline4";
        EditPreviewBuilder.Preview p = EditPreviewBuilder.buildPreview(a, current);
        assertNotNull(p);
        // The tool appends a trailing newline to new_str for clean replacement.
        assertEquals("line1\nreplaced\nline4", p.newText());
        assertEquals(current, p.oldText());
    }

    @Test
    @DisplayName("no path or file arg means no preview")
    void missingPathYieldsNull() {
        JsonObject a = args();
        a.addProperty("content", "text");
        assertNull(EditPreviewBuilder.buildPreview(a, "old"));
        assertNull(EditPreviewBuilder.buildPreview(a, null));
        assertNull(EditPreviewBuilder.buildPreview(null, "old"));
    }

    // ── buildPreview: partial-edit semantics ──────────────────────────────────

    @Test
    @DisplayName("file alias resolves the same as path")
    void fileAlias() {
        JsonObject a = args();
        a.addProperty("file", "p/A.java");
        a.addProperty("old_str", "x");
        a.addProperty("new_str", "y");
        EditPreviewBuilder.Preview p = EditPreviewBuilder.buildPreview(a, "x");
        assertNotNull(p);
        assertEquals("p/A.java", p.path());
        assertEquals("y", p.newText());
    }

    @Test
    @DisplayName("ambiguous old_str without replace_all yields no preview")
    void ambiguousMatchRejected() {
        JsonObject a = args();
        a.addProperty("path", "p/A.java");
        a.addProperty("old_str", "dup");
        a.addProperty("new_str", "one");
        assertNull(EditPreviewBuilder.buildPreview(a, "dup dup"));
    }

    @Test
    @DisplayName("replace_all replaces every occurrence")
    void replaceAll() {
        JsonObject a = args();
        a.addProperty("path", "p/A.java");
        a.addProperty("old_str", "dup");
        a.addProperty("new_str", "one");
        a.addProperty("replace_all", true);
        EditPreviewBuilder.Preview p = EditPreviewBuilder.buildPreview(a, "dup and dup");
        assertNotNull(p);
        assertEquals("one and one", p.newText());
    }

    @Test
    @DisplayName("case_sensitive=false matches case-insensitively")
    void caseInsensitiveMatch() {
        JsonObject a = args();
        a.addProperty("path", "p/A.java");
        a.addProperty("old_str", "Hello");
        a.addProperty("new_str", "Bye");
        a.addProperty("case_sensitive", false);
        EditPreviewBuilder.Preview p = EditPreviewBuilder.buildPreview(a, "say hello now");
        assertNotNull(p);
        assertEquals("say Bye now", p.newText());
    }

    @Test
    @DisplayName("case-insensitive replace_all preserves case outside the replacements")
    void caseInsensitiveReplaceAllPreservesCase() {
        JsonObject a = args();
        a.addProperty("path", "p/A.java");
        a.addProperty("old_str", "dup");
        a.addProperty("new_str", "one");
        a.addProperty("replace_all", true);
        a.addProperty("case_sensitive", false);
        EditPreviewBuilder.Preview p = EditPreviewBuilder.buildPreview(a, "Foo DUP bar and dup again");
        assertNotNull(p);
        // The lowered copy is only for finding positions; splicing happens in the
        // original text, exactly like the tool's document edit.
        assertEquals("Foo one bar and one again", p.newText());
    }

    @Test
    @DisplayName("CRLF in old_str/new_str is normalized like the tool does")
    void newlineNormalization() {
        JsonObject a = args();
        a.addProperty("path", "p/A.java");
        a.addProperty("old_str", "a\r\nb");
        a.addProperty("new_str", "c\r\nd");
        EditPreviewBuilder.Preview p = EditPreviewBuilder.buildPreview(a, "a\nb\nz");
        assertNotNull(p);
        assertEquals("c\nd\nz", p.newText());
    }

    @Test
    @DisplayName("new file (null current text) with content renders as pure additions")
    void newFileFullWrite() {
        JsonObject a = args();
        a.addProperty("path", "p/New.java");
        a.addProperty("content", "brand\nnew");
        EditPreviewBuilder.Preview p = EditPreviewBuilder.buildPreview(a, null);
        assertNotNull(p);
        assertNull(p.oldText());
        assertEquals("brand\nnew", p.newText());
    }

    @Test
    @DisplayName("edit that produces identical text yields no preview")
    void identicalTextYieldsNull() {
        JsonObject a = args();
        a.addProperty("path", "p/A.java");
        a.addProperty("content", "same");
        assertNull(EditPreviewBuilder.buildPreview(a, "same"));

        JsonObject b = args();
        b.addProperty("path", "p/A.java");
        b.addProperty("old_str", "x");
        b.addProperty("new_str", "x");
        assertNull(EditPreviewBuilder.buildPreview(b, "x here"));
    }

    @Test
    @DisplayName("old_str not found yields no preview (never fabricates a result)")
    void oldStrNotFound() {
        JsonObject a = args();
        a.addProperty("path", "p/A.java");
        a.addProperty("old_str", "missing");
        a.addProperty("new_str", "whatever");
        assertNull(EditPreviewBuilder.buildPreview(a, "other content"));
        assertNull(EditPreviewBuilder.buildPreview(a, null));
    }

    @Test
    @DisplayName("line range out of bounds yields no preview")
    void lineRangeOutOfBounds() {
        JsonObject a = args();
        a.addProperty("path", "p/A.java");
        a.addProperty("start_line", 10);
        a.addProperty("new_str", "x");
        assertNull(EditPreviewBuilder.buildPreview(a, "one\ntwo"));
    }

    @Test
    @DisplayName("trailing newline does not create a phantom extra line")
    void trailingNewlinePhantomLine() {
        JsonObject a = args();
        a.addProperty("path", "p/A.java");
        a.addProperty("start_line", 3);
        a.addProperty("new_str", "x");
        // "a\nb\n" has 2 real lines; split() yields 3 elements with the empty tail.
        assertNull(EditPreviewBuilder.buildPreview(a, "a\nb\n"));
        // start_line=2 is the last real line and must preview fine.
        JsonObject b = args();
        b.addProperty("path", "p/A.java");
        b.addProperty("start_line", 2);
        b.addProperty("new_str", "B");
        EditPreviewBuilder.Preview p = EditPreviewBuilder.buildPreview(b, "a\nb\n");
        assertNotNull(p);
        assertEquals("a\nB\n", p.newText());
    }

    // ── changeStats / boundedDiff ─────────────────────────────────────────────

    @Test
    @DisplayName("stats count the full change, beyond the diff cap")
    void statsCountFullChange() {
        StringBuilder oldText = new StringBuilder();
        StringBuilder newText = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            oldText.append("old").append(i).append('\n');
            newText.append("new").append(i).append('\n');
        }
        int[] stats = EditPreviewBuilder.changeStats(oldText.toString(), newText.toString());
        assertEquals(600, stats[0]); // every line replaced = all additions…
        assertEquals(600, stats[1]); // …and all removals (trailing empty line is shared)
    }

    @Test
    @DisplayName("bounded diff collapses unchanged regions and marks the cut")
    void boundedDiffCollapsesAndCaps() {
        StringBuilder oldText = new StringBuilder();
        StringBuilder newText = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            oldText.append("line").append(i).append('\n');
            newText.append(i == 5 ? "CHANGED" : "line" + i).append('\n');
        }
        String diff = EditPreviewBuilder.boundedDiff(oldText.toString(), newText.toString());
        assertNotNull(diff);
        assertTrue(diff.contains("+CHANGED"));
        assertTrue(diff.contains("\u2026"));
        assertTrue(diff.lines().count() <= 410, "diff must stay near the 400-line cap");
    }

    @Test
    @DisplayName("new file renders as pure additions; equal texts yield null")
    void diffEdgeCases() {
        String diff = EditPreviewBuilder.boundedDiff(null, "new line");
        assertEquals("+new line\n", diff);
        assertNull(EditPreviewBuilder.boundedDiff("same", "same"));
    }
}
