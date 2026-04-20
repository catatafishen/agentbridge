package com.github.catatafishen.agentbridge.ui.side;

import com.github.catatafishen.agentbridge.ui.EntryData;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure helpers in {@link PromptsPanel}'s companion object.
 * These helpers are exposed via Kotlin's {@code Companion} class.
 */
final class PromptsPanelLogicTest {

    private static final PromptsPanel.Companion C = PromptsPanel.Companion;

    // ── formatStats ──────────────────────────────────────────────────────────

    @Test
    void formatStats_nullReturnsEmpty() {
        assertEquals("", C.formatStats(null));
    }

    @Test
    void formatStats_zeroCountsReturnsEmpty() {
        EntryData.TurnStats stats = turnStats(0, 0L);
        assertEquals("", C.formatStats(stats));
    }

    @Test
    void formatStats_includesToolsAndDurationSeconds() {
        EntryData.TurnStats stats = turnStats(5, 12_500L);
        assertEquals("5 tools · 12.5s", C.formatStats(stats));
    }

    @Test
    void formatStats_formatsMinutesAndSeconds() {
        EntryData.TurnStats stats = turnStats(2, 125_000L);
        // 125s → 2m 5s
        assertEquals("2 tools · 2m 5s", C.formatStats(stats));
    }

    @Test
    void formatStats_toolsOnlyWhenDurationMissing() {
        EntryData.TurnStats stats = turnStats(3, 0L);
        assertEquals("3 tools", C.formatStats(stats));
    }

    // ── formatCommits ────────────────────────────────────────────────────────

    @Test
    void formatCommits_emptyReturnsEmpty() {
        assertEquals("", C.formatCommits(List.of()));
    }

    @Test
    void formatCommits_singleCommitUsesSingularLabel() {
        assertEquals("Commit: abcdef1", C.formatCommits(List.of("abcdef1234567890abcdef1234567890abcdef12")));
    }

    @Test
    void formatCommits_multipleCommitsShowsCountAndAbbrev() {
        String result = C.formatCommits(List.of(
            "1234567890abcdef1234567890abcdef12345678",
            "fedcba0987654321fedcba0987654321fedcba09"
        ));
        assertEquals("2 commits: 1234567, fedcba0", result);
    }

    // ── formatTimestamp ──────────────────────────────────────────────────────

    @Test
    void formatTimestamp_emptyReturnsEmpty() {
        assertEquals("", C.formatTimestamp(""));
    }

    @Test
    void formatTimestamp_invalidReturnsInput() {
        assertEquals("not-a-date", C.formatTimestamp("not-a-date"));
    }

    @Test
    void formatTimestamp_todayUsesTodayPrefix() {
        Instant now = LocalDate.now().atTime(LocalTime.NOON).atZone(ZoneId.systemDefault()).toInstant();
        String s = C.formatTimestamp(now.toString());
        assertTrue(s.startsWith("Today "), "Expected 'Today …' got: " + s);
    }

    @Test
    void formatTimestamp_yesterdayUsesYesterdayPrefix() {
        Instant y = LocalDate.now().minusDays(1).atTime(LocalTime.NOON).atZone(ZoneId.systemDefault()).toInstant();
        String s = C.formatTimestamp(y.toString());
        assertTrue(s.startsWith("Yesterday "), "Expected 'Yesterday …' got: " + s);
    }

    // ── truncatePrompt ───────────────────────────────────────────────────────

    @Test
    void truncatePrompt_shortTextUnchanged() {
        assertEquals("Hello", C.truncatePrompt("Hello"));
    }

    @Test
    void truncatePrompt_exactMaxCharsUnchanged() {
        String text = "a".repeat(200);
        assertEquals(text, C.truncatePrompt(text));
    }

    @Test
    void truncatePrompt_exceedsMaxCharsGetsEllipsis() {
        String text = "a".repeat(201);
        String result = C.truncatePrompt(text);
        assertEquals("a".repeat(200) + "…", result);
    }

    @Test
    void truncatePrompt_exactMaxRowsUnchanged() {
        String text = "line1\nline2\nline3\nline4\nline5";
        assertEquals(text, C.truncatePrompt(text));
    }

    @Test
    void truncatePrompt_exceedsMaxRowsGetsEllipsis() {
        String text = "line1\nline2\nline3\nline4\nline5\nline6";
        String result = C.truncatePrompt(text);
        assertEquals("line1\nline2\nline3\nline4\nline5…", result);
    }

    @Test
    void truncatePrompt_rowsLimitBeforeCharsLimit() {
        // 6 short lines (< 200 chars total) — rows should trigger first
        String text = "a\nb\nc\nd\ne\nf";
        String result = C.truncatePrompt(text);
        assertEquals("a\nb\nc\nd\ne…", result);
    }

    @Test
    void truncatePrompt_charsLimitBeforeRowsLimit() {
        // single very long line (> 200 chars) — chars should trigger
        String text = "a".repeat(300);
        String result = C.truncatePrompt(text);
        assertEquals("a".repeat(200) + "…", result);
    }

    // ── filterPrompts ────────────────────────────────────────────────────────

    @Test
    void filterPrompts_emptyQueryReturnsAll() {
        List<EntryData.Prompt> all = List.of(prompt("p1", "Hello"), prompt("p2", "World"));
        assertEquals(all, C.filterPrompts(all, ""));
    }

    @Test
    void filterPrompts_caseInsensitiveMatch() {
        List<EntryData.Prompt> all = List.of(prompt("p1", "Hello World"), prompt("p2", "Goodbye"));
        List<EntryData.Prompt> result = C.filterPrompts(all, "WORLD");
        assertEquals(1, result.size());
        assertEquals("p1", result.iterator().next().getId());
    }

    @Test
    void filterPrompts_whitespaceOnlyQueryReturnsAll() {
        List<EntryData.Prompt> all = List.of(prompt("p1", "Hello"));
        assertEquals(all, C.filterPrompts(all, "   "));
    }

    @Test
    void filterPrompts_searchesOlderHistoryEntriesToo() {
        EntryData.Prompt older = prompt("h1", "Needle from history");
        EntryData.Prompt recent = prompt("l1", "Recent prompt");

        List<EntryData.Prompt> matches = C.filterPrompts(List.of(older, recent), "needle");
        assertEquals(1, matches.size());
        assertEquals(older, matches.iterator().next());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

// ── mergeEntries ─────────────────────────────────────────────────────────

    @Test
    void mergeEntries_liveEntriesTakePriorityOverHistory() {
        // Old history entry with id "t0" and new live entry with same id "t0"
        // (happens after session restart when turnCounter resets)
        EntryData.Prompt oldT0 = new EntryData.Prompt("Old prompt", "2024-04-18T21:00:00Z", null, "t0", "t0");
        EntryData.Prompt newT0 = new EntryData.Prompt("New prompt", "2024-04-20T10:00:00Z", null, "t0", "t0");

        List<EntryData> merged = C.mergeEntries(
            List.of(oldT0),
            List.of(newT0)
        );

        // Live version wins — the merged list should contain the new prompt
        assertEquals(1, merged.size());
        EntryData.Prompt result = (EntryData.Prompt) merged.get(0);
        assertEquals("New prompt", result.getText());
    }

    @Test
    void mergeEntries_supplementsOldEntriesFromHistory() {
        // History has an old entry "t0" that live doesn't have
        EntryData.Prompt oldT0 = new EntryData.Prompt("Old prompt", "2024-04-18T21:00:00Z", null, "t0", "t0");
        EntryData.Prompt newT1 = new EntryData.Prompt("New prompt", "2024-04-20T10:00:00Z", null, "t1", "t1");

        List<EntryData> merged = C.mergeEntries(
            List.of(oldT0),
            List.of(newT1)
        );

        // Both entries should be present: old supplemental + live
        assertEquals(2, merged.size());
        assertEquals("Old prompt", ((EntryData.Prompt) merged.get(0)).getText());
        assertEquals("New prompt", ((EntryData.Prompt) merged.get(1)).getText());
    }

    @Test
    void mergeEntries_emptyHistoryReturnsLive() {
        EntryData.Prompt live = new EntryData.Prompt("Live", "2024-04-20T10:00:00Z", null, "t0", "t0");
        List<EntryData> merged = C.mergeEntries(List.of(), List.of(live));
        assertEquals(1, merged.size());
        assertEquals("Live", ((EntryData.Prompt) merged.get(0)).getText());
    }

    @Test
    void mergeEntries_emptyLiveReturnsHistory() {
        EntryData.Prompt hist = new EntryData.Prompt("History", "2024-04-18T10:00:00Z", null, "t0", "t0");
        List<EntryData> merged = C.mergeEntries(List.of(hist), List.of());
        assertEquals(1, merged.size());
        assertEquals("History", ((EntryData.Prompt) merged.get(0)).getText());
    }

    private static EntryData.TurnStats turnStats(int toolCalls, long durationMs) {
        return new EntryData.TurnStats(
            "turn",            // turnId
            durationMs,
            0L, 0L, 0.0,
            toolCalls,
            0, 0,
            "", ""
        );
    }

    private static EntryData.Prompt prompt(String id, String text) {
        return new EntryData.Prompt(text, "", null, id);
    }
}
