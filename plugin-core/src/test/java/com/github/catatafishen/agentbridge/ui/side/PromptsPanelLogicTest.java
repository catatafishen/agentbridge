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
        assertEquals("p1", result.get(0).getId());
    }

    @Test
    void filterPrompts_whitespaceOnlyQueryReturnsAll() {
        List<EntryData.Prompt> all = List.of(prompt("p1", "Hello"));
        assertEquals(all, C.filterPrompts(all, "   "));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

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
