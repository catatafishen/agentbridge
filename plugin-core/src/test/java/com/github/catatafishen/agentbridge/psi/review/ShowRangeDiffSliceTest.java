package com.github.catatafishen.agentbridge.psi.review;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-logic tests for {@link ShowRangeDiffAction#extractSlice}. Verifies that a
 * line-range slice is padded with context lines and clamped to document bounds.
 */
class ShowRangeDiffSliceTest {

    @Test
    void extractSlice_middleRange_includesContext() {
        String text = "a\nb\nc\nd\ne\nf\ng\n";
        // request lines [3, 5): lines d, e; with 2 lines context → b, c, d, e, f, g
        assertEquals("b\nc\nd\ne\nf\ng", ShowRangeDiffAction.extractSlice(text, 3, 5));
    }

    @Test
    void extractSlice_clampsToStart() {
        String text = "a\nb\nc\nd\n";
        // request [0, 1): line a; 2 lines context above clamps → a, b, c
        assertEquals("a\nb\nc", ShowRangeDiffAction.extractSlice(text, 0, 1));
    }

    @Test
    void extractSlice_clampsToEnd() {
        String text = "a\nb\nc\nd\n";
        // split("\n", -1) yields {"a","b","c","d",""} (length 5)
        // request [3, 4): line d; +2 context below clamps → b, c, d, ""
        assertEquals("b\nc\nd\n", ShowRangeDiffAction.extractSlice(text, 3, 4));
    }

    @Test
    void extractSlice_emptyText_returnsEmpty() {
        assertEquals("", ShowRangeDiffAction.extractSlice("", 0, 5));
    }

    @Test
    void extractSlice_rangeOutOfBounds_returnsEmpty() {
        String text = "a\nb\n";
        assertEquals("", ShowRangeDiffAction.extractSlice(text, 50, 60));
    }
}
