package com.github.catatafishen.agentbridge.psi.tools.notebook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("NotebookMarkers — #%% cell-marker mapping and active-cell resolution")
class NotebookMarkersTest {

    // ── cellMarkerOffsets ─────────────────────────────────────────────────────

    @Test
    @DisplayName("finds the document offset of each cell's leading marker")
    void findsCellOffsets() {
        assertArrayEquals(new int[]{0, 18},
            NotebookMarkers.cellMarkerOffsets("#%%\nimport random\n#%%\nN = 10\n"));
    }

    @Test
    @DisplayName("counts code, markdown, and raw cell markers (all begin with #%%)")
    void countsAllCellTypes() {
        assertEquals(3, NotebookMarkers.cellMarkerOffsets("#%%\ncode\n#%% md\n# Title\n#%% raw\nraw").length);
    }

    @Test
    @DisplayName("only a line that starts with #%% is a marker")
    void ignoresInlineHashPercent() {
        assertEquals(1, NotebookMarkers.cellMarkerOffsets("#%%\nx = 1  #%% not a marker\n").length);
    }

    @Test
    @DisplayName("a trailing marker with no newline is still counted")
    void trailingMarkerWithoutNewline() {
        int[] offsets = NotebookMarkers.cellMarkerOffsets("#%%\nx = 1\n#%%");
        assertEquals(2, offsets.length);
        assertEquals(10, offsets[1]);
    }

    @Test
    @DisplayName("empty document has no markers")
    void emptyDocument() {
        assertEquals(0, NotebookMarkers.cellMarkerOffsets("").length);
    }

    // ── cellIndexForOffset ────────────────────────────────────────────────────

    @Test
    @DisplayName("caret inside the first cell → index 0")
    void caretInFirstCell() {
        int[] markers = {0, 18};
        assertEquals(0, NotebookMarkers.cellIndexForOffset(markers, 0));
        assertEquals(0, NotebookMarkers.cellIndexForOffset(markers, 5));
        assertEquals(0, NotebookMarkers.cellIndexForOffset(markers, 17));
    }

    @Test
    @DisplayName("caret at a cell's marker or inside it → that cell")
    void caretInLaterCell() {
        int[] markers = {0, 18, 40};
        assertEquals(1, NotebookMarkers.cellIndexForOffset(markers, 18));
        assertEquals(1, NotebookMarkers.cellIndexForOffset(markers, 30));
        assertEquals(2, NotebookMarkers.cellIndexForOffset(markers, 40));
        assertEquals(2, NotebookMarkers.cellIndexForOffset(markers, 999));
    }

    @Test
    @DisplayName("caret above the first cell → -1")
    void caretBeforeFirstCell() {
        assertEquals(-1, NotebookMarkers.cellIndexForOffset(new int[]{3, 10}, 0));
        assertEquals(-1, NotebookMarkers.cellIndexForOffset(new int[]{3, 10}, 2));
    }

    @Test
    @DisplayName("no markers → -1 for any offset")
    void noMarkers() {
        assertEquals(-1, NotebookMarkers.cellIndexForOffset(new int[]{}, 0));
        assertEquals(-1, NotebookMarkers.cellIndexForOffset(new int[]{}, 100));
    }

    // ── resolveActiveCell ─────────────────────────────────────────────────────

    @Test
    @DisplayName("markers match the cell count → the caret's cell index")
    void resolvesActiveCell() {
        int[] markers = {0, 18, 40};
        assertEquals(0, NotebookMarkers.resolveActiveCell(markers, 3, 5));
        assertEquals(1, NotebookMarkers.resolveActiveCell(markers, 3, 18));
        assertEquals(2, NotebookMarkers.resolveActiveCell(markers, 3, 100));
    }

    @Test
    @DisplayName("marker count != cell count → actionable error asking for index/cell_id")
    void markerCountMismatchThrows() {
        NotebookModel.NotebookException e = assertThrows(NotebookModel.NotebookException.class,
            () -> NotebookMarkers.resolveActiveCell(new int[]{0, 18}, 3, 5));
        assertTrue(e.getMessage().contains("index"));
    }

    @Test
    @DisplayName("caret above the first cell → actionable error")
    void caretAboveFirstCellThrows() {
        NotebookModel.NotebookException e = assertThrows(NotebookModel.NotebookException.class,
            () -> NotebookMarkers.resolveActiveCell(new int[]{3, 10}, 2, 0));
        assertTrue(e.getMessage().contains("caret"));
    }
}
