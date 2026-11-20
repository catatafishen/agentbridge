package com.github.catatafishen.agentbridge.ui;

import com.github.catatafishen.agentbridge.model.SessionUpdate;
import com.github.catatafishen.agentbridge.model.SessionUpdate.CompactionStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Summary accumulation and chat text for agent-reported compactions (ACP "Session compaction").
 */
class CompactionTrackerTest {

    private final CompactionTracker tracker = new CompactionTracker();

    private static SessionUpdate.Compaction update(String id, CompactionStatus status, String summary) {
        return new SessionUpdate.Compaction(id, status, false, null, summary);
    }

    private static SessionUpdate.CompactionSummaryChunk chunk(String id, String text) {
        return new SessionUpdate.CompactionSummaryChunk(id, text);
    }

    @Test
    void chunksAppendAndCompletionWithoutSummaryKeepsThem() {
        tracker.onUpdate(update("c1", CompactionStatus.STARTED, null));
        tracker.onChunk(chunk("c1", "Retained "));
        tracker.onChunk(chunk("c1", "context"));

        var notice = tracker.onUpdate(update("c1", CompactionStatus.COMPLETED, null));

        assertEquals("Retained context", notice.getSummary());
    }

    @Test
    void summaryOnUpdateReplacesStreamedChunks() {
        tracker.onChunk(chunk("c1", "draft"));

        var notice = tracker.onUpdate(update("c1", CompactionStatus.COMPLETED, "final"));

        assertEquals("final", notice.getSummary());
    }

    @Test
    void emptySummaryClearsAccumulatedText() {
        tracker.onChunk(chunk("c1", "partial"));

        var notice = tracker.onUpdate(update("c1", CompactionStatus.FAILED, ""));

        assertNull(notice.getSummary());
    }

    @Test
    void compactionsWithDifferentIdsDoNotShareSummaries() {
        tracker.onChunk(chunk("c1", "first"));
        tracker.onChunk(chunk("c2", "second"));

        assertEquals("first", tracker.onUpdate(update("c1", CompactionStatus.COMPLETED, null)).getSummary());
        assertEquals("second", tracker.onUpdate(update("c2", CompactionStatus.COMPLETED, null)).getSummary());
    }

    @Test
    void resetForgetsPreviousTurn() {
        tracker.onChunk(chunk("c1", "old"));
        tracker.reset();

        assertNull(tracker.onUpdate(update("c1", CompactionStatus.COMPLETED, null)).getSummary());
    }

    @Test
    void openCodeMarkerWithoutIdUsesOnlyItsOwnSummary() {
        var notice = tracker.onUpdate(update(null, CompactionStatus.COMPLETED, null));

        assertNull(notice.getSummary());
    }

    @Test
    void completedMessageIncludesTheSummary() {
        var notice = new CompactionTracker.Notice(CompactionStatus.COMPLETED, null, "## Retained context");

        String text = CompactionTracker.message("OpenCode", notice);

        assertTrue(text.startsWith("OpenCode compacted the conversation context."), text);
        assertTrue(text.endsWith("## Retained context"), text);
        assertFalse(CompactionTracker.isError(notice));
    }

    @Test
    void failedMessageShowsTheErrorAndIsAnError() {
        var notice = new CompactionTracker.Notice(CompactionStatus.FAILED, "timed out", null);

        assertEquals("OpenCode could not compact the conversation context: timed out",
            CompactionTracker.message("OpenCode", notice));
        assertTrue(CompactionTracker.isError(notice));
    }

    @Test
    void failedMessageWithoutErrorEndsWithAPeriod() {
        var notice = new CompactionTracker.Notice(CompactionStatus.FAILED, null, null);

        assertEquals("OpenCode could not compact the conversation context.", CompactionTracker.message("OpenCode", notice));
    }

    @Test
    void unknownStatusIsShownGenerically() {
        var notice = new CompactionTracker.Notice(CompactionStatus.UNKNOWN, null, null);

        assertTrue(CompactionTracker.message("Agent", notice).contains("does not know"));
        assertFalse(CompactionTracker.isError(notice));
    }
}
