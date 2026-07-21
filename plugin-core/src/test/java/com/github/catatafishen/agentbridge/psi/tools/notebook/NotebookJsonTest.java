package com.github.catatafishen.agentbridge.psi.tools.notebook;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("NotebookJson — nbformat parse/serialize fidelity")
class NotebookJsonTest {

    @Test
    @DisplayName("parse rejects non-object JSON")
    void parseRejectsNonObject() {
        assertThrows(NotebookJson.NotebookParseException.class, () -> NotebookJson.parse("[1,2,3]"));
        assertThrows(NotebookJson.NotebookParseException.class, () -> NotebookJson.parse("not json"));
    }

    @Test
    @DisplayName("detectLineEnding distinguishes CRLF from LF")
    void detectsLineEnding() {
        assertEquals("\r\n", NotebookJson.detectLineEnding("{\r\n \"a\": 1\r\n}"));
        assertEquals("\n", NotebookJson.detectLineEnding("{\n \"a\": 1\n}"));
        assertEquals("\n", NotebookJson.detectLineEnding("{}"));
    }

    // ── source line encoding ──────────────────────────────────────────────────

    @Test
    @DisplayName("single-line source encodes as a JSON string; multi-line as a line array")
    void sourceShapeMatchesJupyter() {
        assertTrue(NotebookJson.stringToLines("import random").isJsonPrimitive(),
            "single line should be a plain string");
        assertTrue(NotebookJson.stringToLines("a\nb").isJsonArray(),
            "multi-line should be a line array");

        JsonArray arr = NotebookJson.stringToLines("N = 10\ngame_board = []").getAsJsonArray();
        assertEquals("N = 10\n", arr.get(0).getAsString());
        assertEquals("game_board = []", arr.get(1).getAsString());
    }

    @Test
    @DisplayName("trailing newline stays on the last line, no empty tail element")
    void trailingNewlineHasNoEmptyTail() {
        JsonArray arr = NotebookJson.stringToLines("a\nb\n").getAsJsonArray();
        assertEquals(2, arr.size());
        assertEquals("a\n", arr.get(0).getAsString());
        assertEquals("b\n", arr.get(1).getAsString());
    }

    @ParameterizedTest
    @DisplayName("linesToString ∘ stringToLines is the identity for any source text")
    @ValueSource(strings = {
        "", "x", "import random", "a\nb", "a\nb\n", "a\n\nb", "\n", "line1\nline2\nline3",
        "def f():\n    return 1\n", "text with \"quotes\" and \\backslash"
    })
    void sourceRoundTripsExactly(String source) {
        assertEquals(source, NotebookJson.linesToString(NotebookJson.stringToLines(source)));
    }

    @Test
    @DisplayName("linesToString accepts a plain string, an array, or null")
    void linesToStringHandlesAllShapes() {
        assertEquals("", NotebookJson.linesToString(null));
        assertEquals("", NotebookJson.linesToString(JsonNull.INSTANCE));
        assertEquals("hi", NotebookJson.linesToString(NotebookJson.stringToLines("hi")));
        JsonArray arr = new JsonArray();
        arr.add("a\n");
        arr.add("b");
        assertEquals("a\nb", NotebookJson.linesToString(arr));
    }

    // ── serialization conventions ─────────────────────────────────────────────

    @Test
    @DisplayName("serialize uses 1-space indent, keeps nulls, keeps non-ASCII, ends with a newline")
    void serializeMatchesJupyterConventions() {
        JsonObject root = new JsonObject();
        root.add("execution_count", JsonNull.INSTANCE);
        root.add("source", NotebookJson.stringToLines("N = 10\nx"));
        root.addProperty("text", "café — данные");

        String out = NotebookJson.serialize(root, "\n");

        assertTrue(out.startsWith("{\n \"execution_count\": null,"),
            "1-space indent + serialized null; was:\n" + out);
        assertTrue(out.contains("\"café — данные\""), "non-ASCII must be verbatim, not \\uXXXX");
        assertTrue(out.contains("\"N = 10\\n\""), "in-string newline stays an escaped \\n");
        assertTrue(out.endsWith("}\n"), "must end with exactly one trailing newline");
    }

    @Test
    @DisplayName("serialize preserves CRLF structurally without touching in-string \\n escapes")
    void serializePreservesCrlf() {
        JsonObject root = new JsonObject();
        root.add("source", NotebookJson.stringToLines("N = 10\nx"));

        String crlf = NotebookJson.serialize(root, "\r\n");

        assertTrue(crlf.endsWith("}\r\n"), "structural endings must be CRLF");
        assertFalse(crlf.replace("\r\n", "").contains("\n"),
            "no lone LF should remain after removing CRLF pairs");
        assertTrue(crlf.contains("\"N = 10\\n\""),
            "the escaped \\n inside the source string must not become \\r\\n");
    }

    @Test
    @DisplayName("re-serializing already-serialized output is a no-op (stable, minimal diffs)")
    void serializationIsIdempotent() {
        JsonObject root = new JsonObject();
        JsonArray cells = new JsonArray();
        JsonObject cell = new JsonObject();
        cell.addProperty("cell_type", "code");
        cell.add("execution_count", JsonNull.INSTANCE);
        cell.add("source", NotebookJson.stringToLines("print('hi')\nprint('bye')"));
        cell.add("outputs", new JsonArray());
        cells.add(cell);
        root.add("cells", cells);
        root.addProperty("nbformat", 4);
        root.addProperty("nbformat_minor", 5);

        String once = NotebookJson.serialize(root, "\r\n");
        String twice = NotebookJson.serialize(NotebookJson.parse(once), "\r\n");
        assertEquals(once, twice);
    }
}
