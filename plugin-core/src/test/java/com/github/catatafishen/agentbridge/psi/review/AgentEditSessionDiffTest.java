package com.github.catatafishen.agentbridge.psi.review;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link AgentEditSession#computeRanges(String, String)} — the static
 * diff helper that produces {@link ChangeRange}s used by {@link AgentEditHighlighter}.
 */
class AgentEditSessionDiffTest {

    @Test
    void identicalContent_noRanges() {
        List<ChangeRange> ranges = AgentEditSession.computeRanges(
            "line1\nline2\nline3\n",
            "line1\nline2\nline3\n");

        assertTrue(ranges.isEmpty(), "Identical content should produce no ranges");
    }

    @Test
    void bothEmpty_noRanges() {
        List<ChangeRange> ranges = AgentEditSession.computeRanges("", "");
        assertTrue(ranges.isEmpty());
    }

    @Test
    void pureInsertion_returnsAddedRange() {
        List<ChangeRange> ranges = AgentEditSession.computeRanges(
            "a\nb\nc\n",
            "a\nNEW\nb\nc\n");

        assertEquals(1, ranges.size());
        ChangeRange r = ranges.get(0);
        assertEquals(ChangeType.ADDED, r.type());
        assertEquals(1, r.startLine());
        assertEquals(2, r.endLine());
        assertEquals(0, r.deletedCount());
        assertEquals(1, r.insertedCount());
    }

    @Test
    void pureDeletion_returnsDeletedRange() {
        List<ChangeRange> ranges = AgentEditSession.computeRanges(
            "a\nb\nc\n",
            "a\nc\n");

        assertEquals(1, ranges.size());
        ChangeRange r = ranges.get(0);
        assertEquals(ChangeType.DELETED, r.type());
        assertEquals(0, r.insertedCount(), "Deletion has zero insertedCount");
        assertEquals(1, r.deletedCount());
        assertEquals(1, r.deletedFromLine());
    }

    @Test
    void modification_returnsModifiedRange() {
        List<ChangeRange> ranges = AgentEditSession.computeRanges(
            "alpha\nbeta\ngamma\n",
            "alpha\nBETA_CHANGED\ngamma\n");

        assertEquals(1, ranges.size());
        ChangeRange r = ranges.get(0);
        assertEquals(ChangeType.MODIFIED, r.type());
        assertEquals(1, r.startLine());
        assertEquals(2, r.endLine());
        assertEquals(1, r.deletedCount());
        assertEquals(1, r.insertedCount());
    }

    @Test
    void multipleDisjointRanges_allDetected() {
        List<ChangeRange> ranges = AgentEditSession.computeRanges(
            "a\nb\nc\nd\ne\nf\n",
            "a\nX\nc\nd\ne\nY\n");

        assertEquals(2, ranges.size());
        assertEquals(ChangeType.MODIFIED, ranges.get(0).type());
        assertEquals(ChangeType.MODIFIED, ranges.get(1).type());
        assertTrue(ranges.get(0).startLine() < ranges.get(1).startLine(),
            "Ranges should be in document order");
    }

    @Test
    void addedRange_reportsOriginalSlot() {
        List<ChangeRange> ranges = AgentEditSession.computeRanges(
            "a\nb\n",
            "a\nNEW1\nNEW2\nb\n");

        assertEquals(1, ranges.size());
        ChangeRange r = ranges.get(0);
        assertEquals(ChangeType.ADDED, r.type());
        assertEquals(1, r.startLine());
        assertEquals(3, r.endLine());
        assertEquals(2, r.insertedCount());
        assertEquals(0, r.deletedCount());
    }

    @Test
    void completelyReplacedContent_returnsModifiedRange() {
        List<ChangeRange> ranges = AgentEditSession.computeRanges(
            "old1\nold2\nold3\n",
            "new1\nnew2\nnew3\n");

        assertEquals(1, ranges.size());
        assertEquals(ChangeType.MODIFIED, ranges.get(0).type());
    }
}
