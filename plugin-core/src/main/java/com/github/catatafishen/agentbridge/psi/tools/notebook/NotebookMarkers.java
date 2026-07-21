package com.github.catatafishen.agentbridge.psi.tools.notebook;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure helpers for locating notebook cells in the IDE's {@code #%%} script view of an {@code .ipynb}.
 *
 * <p>Deliberately free of any IntelliJ/EDT dependency so the cell-boundary and caret-to-cell logic can
 * be unit-tested directly. {@link NotebookExecutor} supplies the editor document and caret offset and
 * delegates the decisions here — keeping the IDE-coupled class thin and this one fully covered.
 */
final class NotebookMarkers {

    private NotebookMarkers() {
    }

    /**
     * Returns the document offsets where each notebook cell begins in the IDE's {@code #%%} script
     * view — every cell (code / markdown / raw) is introduced by a line starting with {@code #%%}.
     * The Nth offset is the start of the Nth cell, matching the nbformat cell order.
     */
    static int[] cellMarkerOffsets(CharSequence text) {
        List<Integer> offsets = new ArrayList<>();
        int length = text.length();
        int lineStart = 0;
        for (int i = 0; i <= length; i++) {
            if (i == length || text.charAt(i) == '\n') {
                if (isCellMarker(text, lineStart, i)) {
                    offsets.add(lineStart);
                }
                lineStart = i + 1;
            }
        }
        int[] result = new int[offsets.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = offsets.get(i);
        }
        return result;
    }

    /**
     * Maps a caret {@code offset} to the 0-based index of the cell it falls in, given the ascending
     * cell-start {@code offsets} from {@link #cellMarkerOffsets}. Returns the index of the last cell
     * whose start is at or before {@code offset}, or {@code -1} if the offset precedes the first cell.
     */
    static int cellIndexForOffset(int[] offsets, int offset) {
        int cell = -1;
        for (int start : offsets) {
            if (start <= offset) {
                cell++;
            } else {
                break;
            }
        }
        return cell;
    }

    /**
     * Resolves the active cell from an editor's cell {@code markers}, the notebook's {@code cellCount},
     * and the {@code caretOffset}. Split from the EDT/editor lookup in
     * {@link NotebookExecutor#activeCellIndex} so the decision is unit-tested.
     *
     * @throws NotebookModel.NotebookException if the markers do not map cleanly to {@code cellCount}
     *                                         cells, or the caret is above the first cell
     */
    static int resolveActiveCell(int[] markers, int cellCount, int caretOffset) {
        if (markers.length != cellCount) {
            throw new NotebookModel.NotebookException("could not map the caret to a cell (the editor shows "
                + markers.length + " cell markers for " + cellCount + " cells; a cell's source may start"
                + " with '#%%') — pass 'index' or 'cell_id'");
        }
        int idx = cellIndexForOffset(markers, caretOffset);
        if (idx < 0) {
            throw new NotebookModel.NotebookException(
                "the caret is above the first cell — place it inside a cell, or pass 'index'/'cell_id'");
        }
        return idx;
    }

    private static boolean isCellMarker(CharSequence text, int start, int end) {
        return end - start >= 3
            && text.charAt(start) == '#'
            && text.charAt(start + 1) == '%'
            && text.charAt(start + 2) == '%';
    }
}
