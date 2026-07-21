package com.github.catatafishen.agentbridge.psi.tools.notebook;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("NotebookModel — cell access and mutation")
class NotebookModelTest {

    /** Three cells: single-line code, markdown, multi-line code with an execute_result output. */
    private static final String FIXTURE = """
        {
         "cells": [
          {
           "cell_type": "code",
           "metadata": {"ExecuteTime": {"start_time": "2026-05-04T12:47:56Z"}},
           "source": "import random",
           "id": "aaa11111",
           "execution_count": 1,
           "outputs": []
          },
          {
           "cell_type": "markdown",
           "metadata": {},
           "source": "# Title",
           "id": "bbb22222"
          },
          {
           "cell_type": "code",
           "metadata": {},
           "source": ["x = 1\\n", "x + 1"],
           "id": "ccc33333",
           "execution_count": 3,
           "outputs": [
            {"output_type": "execute_result", "execution_count": 3, "data": {"text/plain": ["2"]}, "metadata": {}}
           ]
          }
         ],
         "metadata": {"kernelspec": {"name": "python3"}},
         "nbformat": 4,
         "nbformat_minor": 5
        }
        """;

    private static NotebookModel model() {
        return NotebookModel.parse(FIXTURE);
    }

    @Test
    @DisplayName("parse fails when there is no cells array")
    void parseRequiresCells() {
        assertThrows(NotebookJson.NotebookParseException.class,
            () -> NotebookModel.parse("{\"metadata\": {}}"));
    }

    @Test
    @DisplayName("cell accessors read type, id, execution_count, and both source shapes")
    void accessors() {
        NotebookModel nb = model();
        assertEquals(3, nb.cellCount());

        JsonObject c0 = nb.cellAt(0);
        assertEquals("code", NotebookModel.cellType(c0));
        assertEquals("aaa11111", NotebookModel.cellId(c0));
        assertEquals(1, NotebookModel.executionCount(c0));
        assertEquals("import random", NotebookModel.sourceOf(c0));

        JsonObject c1 = nb.cellAt(1);
        assertEquals("markdown", NotebookModel.cellType(c1));
        assertNull(NotebookModel.executionCount(c1), "markdown cell has no execution_count");

        // multi-line source stored as an array reconstructs verbatim
        assertEquals("x = 1\nx + 1", NotebookModel.sourceOf(nb.cellAt(2)));
    }

    @Test
    @DisplayName("cellAt validates the index")
    void cellAtBounds() {
        assertThrows(NotebookModel.NotebookException.class, () -> model().cellAt(3));
        assertThrows(NotebookModel.NotebookException.class, () -> model().cellAt(-1));
    }

    // ── reference resolution ──────────────────────────────────────────────────

    @Test
    @DisplayName("resolveIndex accepts index, id, or a consistent pair")
    void resolveIndex() {
        NotebookModel nb = model();
        assertEquals(1, nb.resolveIndex(1, null));
        assertEquals(1, nb.resolveIndex(null, "bbb22222"));
        assertEquals(1, nb.resolveIndex(1, "bbb22222"));
    }

    @Test
    @DisplayName("resolveIndex rejects missing, conflicting, out-of-range, and unknown references")
    void resolveIndexErrors() {
        NotebookModel nb = model();
        assertThrows(NotebookModel.NotebookException.class, () -> nb.resolveIndex(null, null));
        assertThrows(NotebookModel.NotebookException.class, () -> nb.resolveIndex(0, "bbb22222"));
        assertThrows(NotebookModel.NotebookException.class, () -> nb.resolveIndex(9, null));
        assertThrows(NotebookModel.NotebookException.class, () -> nb.resolveIndex(null, "nope"));
    }

    // ── mutations ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("setCellSource replaces source and preserves unrelated fields (id, metadata, outputs)")
    void setCellSourcePreservesFields() {
        NotebookModel nb = model();
        nb.setCellSource(0, "import numpy as np\nimport random");
        JsonObject c0 = nb.cellAt(0);
        assertEquals("import numpy as np\nimport random", NotebookModel.sourceOf(c0));
        assertEquals("aaa11111", NotebookModel.cellId(c0), "id preserved");
        assertTrue(c0.getAsJsonObject("metadata").has("ExecuteTime"), "unknown metadata preserved");
    }

    @Test
    @DisplayName("addCell inserts a code cell with empty outputs, null execution_count, and a fresh id")
    void addCodeCell() {
        NotebookModel nb = model();
        JsonObject added = nb.addCell(1, "code", "y = 2");
        assertEquals(4, nb.cellCount());
        assertEquals("y = 2", NotebookModel.sourceOf(nb.cellAt(1)));
        assertEquals("code", NotebookModel.cellType(added));
        assertTrue(added.has("outputs") && added.getAsJsonArray("outputs").isEmpty());
        assertTrue(added.has("execution_count") && added.get("execution_count").isJsonNull());
        String id = NotebookModel.cellId(added);
        assertTrue(id != null && !id.isBlank() && nb.indexOfId(id) == 1);
    }

    @Test
    @DisplayName("addCell of markdown omits outputs and execution_count")
    void addMarkdownCell() {
        NotebookModel nb = model();
        JsonObject added = nb.addCell(nb.cellCount(), "markdown", "## Section");
        assertEquals("markdown", NotebookModel.cellType(added));
        assertFalse(added.has("outputs"));
        assertFalse(added.has("execution_count"));
    }

    @Test
    @DisplayName("addCell clamps the position and rejects invalid types")
    void addCellClampAndValidate() {
        NotebookModel nb = model();
        nb.addCell(999, "code", "tail");
        assertEquals("tail", NotebookModel.sourceOf(nb.cellAt(nb.cellCount() - 1)));
        assertThrows(NotebookModel.NotebookException.class, () -> nb.addCell(0, "sql", "x"));
    }

    @Test
    @DisplayName("deleteCell removes and returns the cell")
    void deleteCell() {
        NotebookModel nb = model();
        JsonObject removed = nb.deleteCell(1);
        assertEquals("bbb22222", NotebookModel.cellId(removed));
        assertEquals(2, nb.cellCount());
        assertEquals("aaa11111", NotebookModel.cellId(nb.cellAt(0)));
        assertEquals("ccc33333", NotebookModel.cellId(nb.cellAt(1)));
    }

    @Test
    @DisplayName("moveCell repositions to the given final index, forward and backward")
    void moveCell() {
        NotebookModel forward = model();
        forward.moveCell(0, 2);
        assertEquals("bbb22222", NotebookModel.cellId(forward.cellAt(0)));
        assertEquals("ccc33333", NotebookModel.cellId(forward.cellAt(1)));
        assertEquals("aaa11111", NotebookModel.cellId(forward.cellAt(2)));

        NotebookModel backward = model();
        backward.moveCell(2, 0);
        assertEquals("ccc33333", NotebookModel.cellId(backward.cellAt(0)));

        assertThrows(NotebookModel.NotebookException.class, () -> model().moveCell(0, 9));
    }

    @Test
    @DisplayName("changeCellType code→markdown strips outputs/execution_count; markdown→code adds them")
    void changeCellType() {
        NotebookModel nb = model();
        nb.changeCellType(2, "markdown");
        JsonObject c2 = nb.cellAt(2);
        assertEquals("markdown", NotebookModel.cellType(c2));
        assertFalse(c2.has("outputs"), "markdown must not carry outputs");
        assertFalse(c2.has("execution_count"));
        assertEquals("x = 1\nx + 1", NotebookModel.sourceOf(c2), "source preserved across type change");

        nb.changeCellType(1, "code");
        JsonObject c1 = nb.cellAt(1);
        assertEquals("code", NotebookModel.cellType(c1));
        assertTrue(c1.has("outputs") && c1.getAsJsonArray("outputs").isEmpty());
        assertTrue(c1.has("execution_count") && c1.get("execution_count").isJsonNull());
    }

    // ── round-trip fidelity ───────────────────────────────────────────────────

    @Test
    @DisplayName("toJson round-trips all cells, outputs, and top-level metadata")
    void roundTripPreservesEverything() {
        NotebookModel original = model();
        NotebookModel reparsed = NotebookModel.parse(original.toJson());

        assertEquals(3, reparsed.cellCount());
        assertEquals("import random", NotebookModel.sourceOf(reparsed.cellAt(0)));
        assertEquals("x = 1\nx + 1", NotebookModel.sourceOf(reparsed.cellAt(2)));
        assertEquals(1, NotebookModel.outputsOf(reparsed.cellAt(2)).size());

        JsonObject reRoot = NotebookJson.parse(original.toJson());
        assertEquals(4, reRoot.get("nbformat").getAsInt());
        assertEquals("python3",
            reRoot.getAsJsonObject("metadata").getAsJsonObject("kernelspec").get("name").getAsString());
    }

    @Test
    @DisplayName("an unmodified parse→serialize is byte-stable (idempotent) for minimal diffs")
    void serializeIsIdempotent() {
        String once = model().toJson();
        String twice = NotebookModel.parse(once).toJson();
        assertEquals(once, twice);
    }
}
