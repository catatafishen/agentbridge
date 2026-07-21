package com.github.catatafishen.agentbridge.psi.tools.notebook;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("NotebookOutputFormatter — rendering")
class NotebookOutputFormatterTest {

    private static final String ESC = String.valueOf((char) 27);

    private static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static JsonArray arr(String json) {
        return JsonParser.parseString(json).getAsJsonArray();
    }

    // ── list view ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("listLine shows index, type, id, exec count and output tag for a code cell")
    void listLineCode() {
        JsonObject cell = obj("""
            {"cell_type":"code","id":"71137a7b","execution_count":3,"source":"x = 1",
             "outputs":[{"output_type":"execute_result","data":{"text/plain":["1"]}}]}""");
        String line = NotebookOutputFormatter.listLine(cell, 2);
        assertTrue(line.startsWith("[2] code"), line);
        assertTrue(line.contains("#71137a7b"), line);
        assertTrue(line.contains("exec=3"), line);
        assertTrue(line.contains("out:text"), line);
        assertTrue(line.contains("x = 1"), line);
    }

    @Test
    @DisplayName("listLine marks a never-run cell and previews only the first line")
    void listLineMultilineNeverRun() {
        JsonObject cell = obj("""
            {"cell_type":"code","id":"abc","execution_count":null,"source":["line one\\n","line two"],"outputs":[]}""");
        String line = NotebookOutputFormatter.listLine(cell, 0);
        assertTrue(line.contains("exec=·"), line);
        assertTrue(line.contains("line one"), line);
        assertFalse(line.contains("line two"), "only the first source line is previewed");
    }

    // ── short output summary ──────────────────────────────────────────────────

    @Test
    @DisplayName("shortOutputSummary flags errors, images, text, and empties")
    void shortOutputSummary() {
        assertEquals("", NotebookOutputFormatter.shortOutputSummary(new JsonArray()));
        assertEquals("ERROR:NameError", NotebookOutputFormatter.shortOutputSummary(
            arr("[{\"output_type\":\"error\",\"ename\":\"NameError\",\"evalue\":\"x\"}]")));
        assertEquals("out:image", NotebookOutputFormatter.shortOutputSummary(
            arr("[{\"output_type\":\"display_data\",\"data\":{\"image/png\":\"iVBOR\"}}]")));
        assertEquals("out:text", NotebookOutputFormatter.shortOutputSummary(
            arr("[{\"output_type\":\"stream\",\"name\":\"stdout\",\"text\":[\"hi\"]}]")));
        assertEquals("out:text+image", NotebookOutputFormatter.shortOutputSummary(
            arr("[{\"output_type\":\"execute_result\",\"data\":{\"text/plain\":[\"x\"],\"image/png\":\"iVBOR\"}}]")));
    }

    // ── detail rendering ──────────────────────────────────────────────────────

    @Test
    @DisplayName("cellDetail renders header, source, and each output kind")
    void cellDetailRendersOutputs() {
        JsonObject cell = obj("""
            {"cell_type":"code","id":"c1","execution_count":5,"source":"do_it()",
             "outputs":[
               {"output_type":"stream","name":"stdout","text":["hello\\n","world"]},
               {"output_type":"execute_result","execution_count":5,"data":{"text/plain":["42"]}},
               {"output_type":"display_data","data":{"text/plain":["<Figure>"],"image/png":"aaaa"}}
             ]}""");
        String detail = NotebookOutputFormatter.cellDetail(cell, 0, 3);
        assertTrue(detail.contains("type=code"), detail);
        assertTrue(detail.contains("execution_count=5"), detail);
        assertTrue(detail.contains("do_it()"), detail);
        assertTrue(detail.contains("[stream:stdout]"), detail);
        assertTrue(detail.contains("hello\nworld"), detail);
        assertTrue(detail.contains("[execute_result]"), detail);
        assertTrue(detail.contains("42"), detail);
        assertTrue(detail.contains("<image/png,"), detail);
    }

    @Test
    @DisplayName("cellDetail reports when a code cell has no outputs")
    void cellDetailNoOutputs() {
        JsonObject cell = obj("{\"cell_type\":\"code\",\"id\":\"c\",\"execution_count\":null,\"source\":\"pass\",\"outputs\":[]}");
        String detail = NotebookOutputFormatter.cellDetail(cell, 0, 1);
        assertTrue(detail.contains("execution_count=null"), detail);
        assertTrue(detail.contains("has not produced output"), detail);
    }

    @Test
    @DisplayName("error outputs render ename, evalue, and an ANSI-stripped traceback")
    void renderError() {
        JsonArray outputs = arr("[{\"output_type\":\"error\",\"ename\":\"ValueError\",\"evalue\":\"bad\","
            + "\"traceback\":[\"" + ESC + "[0;31mValueError" + ESC + "[0m: bad\"]}]");
        String rendered = NotebookOutputFormatter.renderOutputs(outputs, 4000);
        assertTrue(rendered.startsWith("[error] ValueError: bad"), rendered);
        assertTrue(rendered.contains("ValueError: bad"), rendered);
        assertFalse(rendered.contains(ESC), "ANSI escape bytes must be stripped");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("stripAnsi removes CSI sequences but never touches bracketed text")
    void stripAnsi() {
        assertEquals("Error", NotebookOutputFormatter.stripAnsi(ESC + "[0;31mError" + ESC + "[0m"));
        assertEquals("data[index] = arr[0]",
            NotebookOutputFormatter.stripAnsi("data[index] = arr[0]"));
    }

    @Test
    @DisplayName("approxSize reports chars for small payloads and KB for large ones")
    void approxSize() {
        assertEquals("3 chars", NotebookOutputFormatter.approxSize(new JsonPrimitive("abc")));
        assertEquals("1 KB", NotebookOutputFormatter.approxSize(new JsonPrimitive("x".repeat(2048))));
    }

    @Test
    @DisplayName("truncate cuts overlong text and notes how much was removed")
    void truncate() {
        String out = NotebookOutputFormatter.truncate("abcdef", 3);
        assertTrue(out.startsWith("abc"), out);
        assertTrue(out.contains("truncated 3 chars"), out);
        assertEquals("short", NotebookOutputFormatter.truncate("short", 10));
    }
}
