package com.github.catatafishen.agentbridge.psi.tools.notebook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("NotebookExecutor — #%% cell-marker mapping")
class NotebookExecutorTest {

    @Test
    @DisplayName("finds the document offset of each cell's leading marker")
    void findsCellOffsets() {
        assertArrayEquals(new int[]{0, 18},
            NotebookExecutor.cellMarkerOffsets("#%%\nimport random\n#%%\nN = 10\n"));
    }

    @Test
    @DisplayName("counts code, markdown, and raw cell markers (all begin with #%%)")
    void countsAllCellTypes() {
        assertEquals(3, NotebookExecutor.cellMarkerOffsets("#%%\ncode\n#%% md\n# Title\n#%% raw\nraw").length);
    }

    @Test
    @DisplayName("only a line that starts with #%% is a marker")
    void ignoresInlineHashPercent() {
        assertEquals(1, NotebookExecutor.cellMarkerOffsets("#%%\nx = 1  #%% not a marker\n").length);
    }

    @Test
    @DisplayName("a trailing marker with no newline is still counted")
    void trailingMarkerWithoutNewline() {
        int[] offsets = NotebookExecutor.cellMarkerOffsets("#%%\nx = 1\n#%%");
        assertEquals(2, offsets.length);
        assertEquals(10, offsets[1]);
    }

    @Test
    @DisplayName("empty document has no markers")
    void emptyDocument() {
        assertEquals(0, NotebookExecutor.cellMarkerOffsets("").length);
    }
}
