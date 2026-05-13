package com.github.catatafishen.agentbridge.ui;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConversationSummaryBuilderTest {

    // ── groupIntoTurns ────────────────────────────────────────────────

    @Test
    void groupIntoTurns_emptyList_returnsEmptyResult() {
        List<ConversationSummaryBuilder.TurnData> turns =
                ConversationSummaryBuilder.INSTANCE.groupIntoTurns(Collections.emptyList());
        assertTrue(turns.isEmpty());
    }

    @Test
    void groupIntoTurns_singlePromptOnly_returnsOneTurnWithEmptyAgent() {
        List<EntryData> entries = List.of(new EntryData.Prompt("Hello"));
        List<ConversationSummaryBuilder.TurnData> turns =
                ConversationSummaryBuilder.INSTANCE.groupIntoTurns(entries);

        assertEquals(1, turns.size());
        assertEquals("Hello", turns.get(0).getUserText());
        assertEquals("", turns.get(0).getAgentText());
        assertEquals(0, turns.get(0).getToolCallCount());
        assertEquals(0, turns.get(0).getThinkingCount());
        assertEquals(0, turns.get(0).getSubAgentCount());
    }

    @Test
    void groupIntoTurns_singlePromptAndResponse_returnsOneTurn() {
        EntryData.Text text = new EntryData.Text();
        text.setRaw("Agent reply");
        List<EntryData> entries = List.of(
                new EntryData.Prompt("User question"),
                text
        );
        List<ConversationSummaryBuilder.TurnData> turns =
                ConversationSummaryBuilder.INSTANCE.groupIntoTurns(entries);

        assertEquals(1, turns.size());
        assertEquals("User question", turns.get(0).getUserText());
        assertEquals("Agent reply", turns.get(0).getAgentText());
    }

    @Test
    void groupIntoTurns_multipleTurns_incrementsTurnIds() {
        EntryData.Text text1 = new EntryData.Text();
        text1.setRaw("Reply 1");
        EntryData.Text text2 = new EntryData.Text();
        text2.setRaw("Reply 2");

        List<EntryData> entries = List.of(
                new EntryData.Prompt("Q1"),
                text1,
                new EntryData.Prompt("Q2"),
                text2
        );
        List<ConversationSummaryBuilder.TurnData> turns =
                ConversationSummaryBuilder.INSTANCE.groupIntoTurns(entries);

        assertEquals(2, turns.size());
        assertEquals("Q1", turns.get(0).getUserText());
        assertEquals("Reply 1", turns.get(0).getAgentText());
        assertEquals("Q2", turns.get(1).getUserText());
        assertEquals("Reply 2", turns.get(1).getAgentText());
    }

    @Test
    void groupIntoTurns_multiplePromptsNoResponses_createsOneTurnEach() {
        List<EntryData> entries = List.of(
                new EntryData.Prompt("A"),
                new EntryData.Prompt("B"),
                new EntryData.Prompt("C")
        );
        List<ConversationSummaryBuilder.TurnData> turns =
                ConversationSummaryBuilder.INSTANCE.groupIntoTurns(entries);

        assertEquals(3, turns.size());
        assertEquals("A", turns.get(0).getUserText());
        assertEquals("", turns.get(0).getAgentText());
        assertEquals("B", turns.get(1).getUserText());
        assertEquals("C", turns.get(2).getUserText());
    }

    @Test
    void groupIntoTurns_entriesWithoutPrompt_ignored() {
        // Text/ToolCall entries before any Prompt should be silently dropped
        EntryData.Text text = new EntryData.Text();
        text.setRaw("orphan text");
        List<EntryData> entries = List.of(
                text,
                new EntryData.ToolCall("some-tool"),
                new EntryData.Thinking()
        );
        List<ConversationSummaryBuilder.TurnData> turns =
                ConversationSummaryBuilder.INSTANCE.groupIntoTurns(entries);

        assertTrue(turns.isEmpty());
    }

    @Test
    void groupIntoTurns_countsToolCallsAndThinkingAndSubAgents() {
        List<EntryData> entries = List.of(
                new EntryData.Prompt("Do stuff"),
                new EntryData.ToolCall("read_file"),
                new EntryData.ToolCall("write_file"),
                new EntryData.ToolCall("edit_text"),
                new EntryData.Thinking(),
                new EntryData.Thinking(),
                new EntryData.SubAgent("explore", "check files")
        );
        List<ConversationSummaryBuilder.TurnData> turns =
                ConversationSummaryBuilder.INSTANCE.groupIntoTurns(entries);

        assertEquals(1, turns.size());
        assertEquals(3, turns.get(0).getToolCallCount());
        assertEquals(2, turns.get(0).getThinkingCount());
        assertEquals(1, turns.get(0).getSubAgentCount());
    }

    @Test
    void groupIntoTurns_multipleTextEntries_concatenatesAgentText() {
        EntryData.Text t1 = new EntryData.Text();
        t1.setRaw("Hello ");
        EntryData.Text t2 = new EntryData.Text();
        t2.setRaw("World");
        List<EntryData> entries = List.of(new EntryData.Prompt("hi"), t1, t2);

        List<ConversationSummaryBuilder.TurnData> turns =
                ConversationSummaryBuilder.INSTANCE.groupIntoTurns(entries);

        assertEquals("Hello World", turns.get(0).getAgentText());
    }

    @Test
    void groupIntoTurns_agentTextIsTrimmed() {
        EntryData.Text t = new EntryData.Text();
        t.setRaw("  padded text  ");
        List<EntryData> entries = List.of(new EntryData.Prompt("q"), t);

        List<ConversationSummaryBuilder.TurnData> turns =
                ConversationSummaryBuilder.INSTANCE.groupIntoTurns(entries);

        assertEquals("padded text", turns.get(0).getAgentText());
    }

    @Test
    void groupIntoTurns_countsResetBetweenTurns() {
        List<EntryData> entries = List.of(
                new EntryData.Prompt("turn1"),
                new EntryData.ToolCall("t1"),
                new EntryData.Thinking(),
                new EntryData.SubAgent("explore", "desc"),
                new EntryData.Prompt("turn2"),
                new EntryData.ToolCall("t2")
        );
        List<ConversationSummaryBuilder.TurnData> turns =
                ConversationSummaryBuilder.INSTANCE.groupIntoTurns(entries);

        assertEquals(2, turns.size());
        // turn 1
        assertEquals(1, turns.get(0).getToolCallCount());
        assertEquals(1, turns.get(0).getThinkingCount());
        assertEquals(1, turns.get(0).getSubAgentCount());
        // turn 2 — counts should have reset
        assertEquals(1, turns.get(1).getToolCallCount());
        assertEquals(0, turns.get(1).getThinkingCount());
        assertEquals(0, turns.get(1).getSubAgentCount());
    }

    @Test
    void groupIntoTurns_irrelevantEntryTypes_areIgnored() {
        // ContextFiles, SessionSeparator, Status, Nudge, TurnStats are skipped
        List<EntryData> entries = List.of(
                new EntryData.Prompt("question"),
                new EntryData.ContextFiles(List.of()),
                new EntryData.SessionSeparator("2025-01-01T00:00:00Z"),
                new EntryData.Status("✓", "done"),
                new EntryData.Nudge("hurry", "n1"),
                new EntryData.TurnStats("t1")
        );
        List<ConversationSummaryBuilder.TurnData> turns =
                ConversationSummaryBuilder.INSTANCE.groupIntoTurns(entries);

        assertEquals(1, turns.size());
        assertEquals("question", turns.get(0).getUserText());
        assertEquals("", turns.get(0).getAgentText());
        assertEquals(0, turns.get(0).getToolCallCount());
        assertEquals(0, turns.get(0).getThinkingCount());
        assertEquals(0, turns.get(0).getSubAgentCount());
    }

    @Test
    void groupIntoTurns_orphanEntriesThenPrompt_onlyPromptTurnKept() {
        EntryData.Text orphan = new EntryData.Text();
        orphan.setRaw("orphan");
        EntryData.Text reply = new EntryData.Text();
        reply.setRaw("actual reply");

        List<EntryData> entries = List.of(
                orphan,
                new EntryData.ToolCall("stray-tool"),
                new EntryData.Prompt("real question"),
                reply
        );
        List<ConversationSummaryBuilder.TurnData> turns =
                ConversationSummaryBuilder.INSTANCE.groupIntoTurns(entries);

        assertEquals(1, turns.size());
        assertEquals("real question", turns.get(0).getUserText());
        assertEquals("actual reply", turns.get(0).getAgentText());
    }

    // ── truncateField ─────────────────────────────────────────────────

    @Test
    void truncateField_shortText_returnsAsIs() {
        String text = "short text";
        assertEquals(text, ConversationSummaryBuilder.INSTANCE.truncateField(text, false, "hint"));
    }

    @Test
    void truncateField_exactlyAtLimit_returnsAsIs() {
        String text = "a".repeat(500);
        assertEquals(text, ConversationSummaryBuilder.INSTANCE.truncateField(text, false, "hint"));
    }

    @Test
    void truncateField_overLimit_truncatesWithHint() {
        String text = "a".repeat(600);
        String result = ConversationSummaryBuilder.INSTANCE.truncateField(text, false, "truncated");
        assertEquals("a".repeat(500) + "…[truncated]", result);
    }

    @Test
    void truncateField_overLimitFull_returnsUntruncated() {
        String text = "a".repeat(600);
        assertEquals(text, ConversationSummaryBuilder.INSTANCE.truncateField(text, true, "hint"));
    }

    @Test
    void truncateField_emptyText_returnsEmpty() {
        assertEquals("", ConversationSummaryBuilder.INSTANCE.truncateField("", false, "hint"));
    }

    @Test
    void truncateField_fullModeShortText_returnsAsIs() {
        assertEquals("hello", ConversationSummaryBuilder.INSTANCE.truncateField("hello", true, "hint"));
    }

    @Test
    void truncateField_hintIsIncludedInSuffix() {
        String text = "b".repeat(501);
        String result = ConversationSummaryBuilder.INSTANCE.truncateField(text, false, "truncated");
        assertTrue(result.endsWith("…[truncated]"));
    }

    // ── buildMarkerLine ───────────────────────────────────────────────

    @Test
    void buildMarkerLine_noMarkers_returnsNull() {
        ConversationSummaryBuilder.TurnData turn = new ConversationSummaryBuilder.TurnData(
                "user", "agent", 0, 0, 0);
        assertNull(ConversationSummaryBuilder.INSTANCE.buildMarkerLine(turn));
    }

    @Test
    void buildMarkerLine_oneToolCall_singular() {
        ConversationSummaryBuilder.TurnData turn = new ConversationSummaryBuilder.TurnData(
                "user", "agent", 1, 0, 0);
        assertEquals("[1 tool call]", ConversationSummaryBuilder.INSTANCE.buildMarkerLine(turn));
    }

    @Test
    void buildMarkerLine_multipleToolCalls_plural() {
        ConversationSummaryBuilder.TurnData turn = new ConversationSummaryBuilder.TurnData(
                "user", "agent", 5, 0, 0);
        assertEquals("[5 tool calls]", ConversationSummaryBuilder.INSTANCE.buildMarkerLine(turn));
    }

    @Test
    void buildMarkerLine_oneThinkingBlock_singular() {
        ConversationSummaryBuilder.TurnData turn = new ConversationSummaryBuilder.TurnData(
                "user", "agent", 0, 1, 0);
        assertEquals("[1 thinking block]", ConversationSummaryBuilder.INSTANCE.buildMarkerLine(turn));
    }

    @Test
    void buildMarkerLine_multipleThinkingBlocks_plural() {
        ConversationSummaryBuilder.TurnData turn = new ConversationSummaryBuilder.TurnData(
                "user", "agent", 0, 3, 0);
        assertEquals("[3 thinking blocks]", ConversationSummaryBuilder.INSTANCE.buildMarkerLine(turn));
    }

    @Test
    void buildMarkerLine_oneSubAgent_singular() {
        ConversationSummaryBuilder.TurnData turn = new ConversationSummaryBuilder.TurnData(
                "user", "agent", 0, 0, 1);
        assertEquals("[1 sub-agent]", ConversationSummaryBuilder.INSTANCE.buildMarkerLine(turn));
    }

    @Test
    void buildMarkerLine_multipleSubAgents_plural() {
        ConversationSummaryBuilder.TurnData turn = new ConversationSummaryBuilder.TurnData(
                "user", "agent", 0, 0, 4);
        assertEquals("[4 sub-agents]", ConversationSummaryBuilder.INSTANCE.buildMarkerLine(turn));
    }

    @Test
    void buildMarkerLine_toolCallsAndThinking_combinedMarker() {
        ConversationSummaryBuilder.TurnData turn = new ConversationSummaryBuilder.TurnData(
                "user", "agent", 5, 2, 0);
        assertEquals("[5 tool calls, 2 thinking blocks]",
                ConversationSummaryBuilder.INSTANCE.buildMarkerLine(turn));
    }

    @Test
    void buildMarkerLine_allThreeCounters_combinedMarker() {
        ConversationSummaryBuilder.TurnData turn = new ConversationSummaryBuilder.TurnData(
                "user", "agent", 3, 1, 2);
        assertEquals("[3 tool calls, 1 thinking block, 2 sub-agents]",
                ConversationSummaryBuilder.INSTANCE.buildMarkerLine(turn));
    }

    @Test
    void buildMarkerLine_toolCallsAndSubAgents_noThinking() {
        ConversationSummaryBuilder.TurnData turn = new ConversationSummaryBuilder.TurnData(
                "user", "agent", 2, 0, 1);
        assertEquals("[2 tool calls, 1 sub-agent]",
                ConversationSummaryBuilder.INSTANCE.buildMarkerLine(turn));
    }

    // ── formatTurnForSummary ──────────────────────────────────────────

    @Test
    void formatTurnForSummary_compactMinimalTurn_outputsUserLineOnly() {
        ConversationSummaryBuilder.TurnData turn = new ConversationSummaryBuilder.TurnData(
                "Hello", "", 0, 0, 0);
        String result = ConversationSummaryBuilder.INSTANCE.formatTurnForSummary(turn, false);

        assertTrue(result.contains("[t1] User: Hello"));
        assertFalse(result.contains("Agent:"));
    }

    @Test
    void formatTurnForSummary_compactWithAgentText_includesAgentLine() {
        ConversationSummaryBuilder.TurnData turn = new ConversationSummaryBuilder.TurnData(
                "Question", "Answer", 0, 0, 0);
        String result = ConversationSummaryBuilder.INSTANCE.formatTurnForSummary(turn, false);

        assertTrue(result.contains("[t2] User: Question"));
        assertTrue(result.contains("Agent: Answer"));
    }

    @Test
    void formatTurnForSummary_compactWithMarkers_includesMarkerLine() {
        ConversationSummaryBuilder.TurnData turn = new ConversationSummaryBuilder.TurnData(
                "Do it", "Done", 5, 2, 0);
        String result = ConversationSummaryBuilder.INSTANCE.formatTurnForSummary(turn, false);

        assertTrue(result.contains("[t3] User: Do it"));
        assertTrue(result.contains("Agent: Done"));
        assertTrue(result.contains("[5 tool calls, 2 thinking blocks]"));
    }

    @Test
    void formatTurnForSummary_compactLongUserText_isTruncated() {
        String longUser = "x".repeat(600);
        ConversationSummaryBuilder.TurnData turn = new ConversationSummaryBuilder.TurnData(
                longUser, "", 0, 0, 0);
        String result = ConversationSummaryBuilder.INSTANCE.formatTurnForSummary(turn, false);

        assertTrue(result.contains("…[truncated]"));
    }

    @Test
    void formatTurnForSummary_fullModeLongUserText_notTruncated() {
        String longUser = "x".repeat(600);
        ConversationSummaryBuilder.TurnData turn = new ConversationSummaryBuilder.TurnData(
                longUser, "", 0, 0, 0);
        String result = ConversationSummaryBuilder.INSTANCE.formatTurnForSummary(turn, true);

        assertTrue(result.contains(longUser));
        assertFalse(result.contains("…["));
    }

    @Test
    void formatTurnForSummary_compactLongAgentText_isTruncated() {
        String longAgent = "y".repeat(600);
        ConversationSummaryBuilder.TurnData turn = new ConversationSummaryBuilder.TurnData(
                "q", longAgent, 0, 0, 0);
        String result = ConversationSummaryBuilder.INSTANCE.formatTurnForSummary(turn, false);

        assertTrue(result.contains("…[truncated]"));
    }

    @Test
    void formatTurnForSummary_fullModeLongAgentText_notTruncated() {
        String longAgent = "y".repeat(600);
        ConversationSummaryBuilder.TurnData turn = new ConversationSummaryBuilder.TurnData(
                "q", longAgent, 0, 0, 0);
        String result = ConversationSummaryBuilder.INSTANCE.formatTurnForSummary(turn, true);

        assertTrue(result.contains(longAgent));
        assertFalse(result.contains("…["));
    }

    @Test
    void formatTurnForSummary_noMarkers_noMarkerLine() {
        ConversationSummaryBuilder.TurnData turn = new ConversationSummaryBuilder.TurnData(
                "q", "a", 0, 0, 0);
        String result = ConversationSummaryBuilder.INSTANCE.formatTurnForSummary(turn, false);

        // Should only have User and Agent lines, no bracket marker
        String[] lines = result.strip().split("\n");
        assertEquals(2, lines.length);
        assertTrue(lines[0].startsWith("[t1] User:"));
        assertTrue(lines[1].startsWith("Agent:"));
    }

    @Test
    void formatTurnForSummary_emptyAgentText_noAgentLine() {
        ConversationSummaryBuilder.TurnData turn = new ConversationSummaryBuilder.TurnData(
                "q", "", 2, 0, 0);
        String result = ConversationSummaryBuilder.INSTANCE.formatTurnForSummary(turn, false);

        assertFalse(result.contains("Agent:"));
        assertTrue(result.contains("[2 tool calls]"));
    }

    @Test
    void formatTurnForSummary_turnIdAppearsInUserHint() {
        String longUser = "z".repeat(600);
        ConversationSummaryBuilder.TurnData turn = new ConversationSummaryBuilder.TurnData(
                longUser, "", 0, 0, 0);
        String result = ConversationSummaryBuilder.INSTANCE.formatTurnForSummary(turn, false);

        assertTrue(result.contains("…[truncated]"));
    }

    // ── TurnData data class basics ────────────────────────────────────

    @Test
    void turnData_equality() {
        ConversationSummaryBuilder.TurnData a = new ConversationSummaryBuilder.TurnData(
                "user", "agent", 1, 2, 3);
        ConversationSummaryBuilder.TurnData b = new ConversationSummaryBuilder.TurnData(
                "user", "agent", 1, 2, 3);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void turnData_inequality() {
        ConversationSummaryBuilder.TurnData a = new ConversationSummaryBuilder.TurnData(
                "user", "agent", 1, 2, 3);
        ConversationSummaryBuilder.TurnData b = new ConversationSummaryBuilder.TurnData(
                "user", "agent", 1, 2, 3);
        assertNotEquals(a, b);
    }

    // ── integration: groupIntoTurns → formatTurnForSummary ────────────

    @Test
    void integration_groupAndFormat_roundTrip() {
        EntryData.Text text = new EntryData.Text();
        text.setRaw("I fixed the bug.");
        List<EntryData> entries = List.of(
                new EntryData.Prompt("Fix the bug"),
                new EntryData.Thinking(),
                new EntryData.ToolCall("read_file"),
                new EntryData.ToolCall("edit_text"),
                text
        );

        List<ConversationSummaryBuilder.TurnData> turns =
                ConversationSummaryBuilder.INSTANCE.groupIntoTurns(entries);
        assertEquals(1, turns.size());

        String formatted = ConversationSummaryBuilder.INSTANCE.formatTurnForSummary(turns.get(0), false);
        assertTrue(formatted.contains("[t1] User: Fix the bug"));
        assertTrue(formatted.contains("Agent: I fixed the bug."));
        assertTrue(formatted.contains("[2 tool calls, 1 thinking block]"));
    }

    @Test
    void integration_multiTurnConversation() {
        EntryData.Text r1 = new EntryData.Text();
        r1.setRaw("Sure, I'll look into it.");
        EntryData.Text r2 = new EntryData.Text();
        r2.setRaw("Done, all tests pass.");

        List<EntryData> entries = List.of(
                new EntryData.Prompt("Refactor the service"),
                new EntryData.Thinking(),
                new EntryData.ToolCall("read_file"),
                r1,
                new EntryData.Prompt("Now run the tests"),
                new EntryData.ToolCall("run_tests"),
                r2
        );

        List<ConversationSummaryBuilder.TurnData> turns =
                ConversationSummaryBuilder.INSTANCE.groupIntoTurns(entries);
        assertEquals(2, turns.size());

        String f1 = ConversationSummaryBuilder.INSTANCE.formatTurnForSummary(turns.get(0), false);
        assertTrue(f1.contains("[t1] User: Refactor the service"));
        assertTrue(f1.contains("[1 tool call, 1 thinking block]"));

        String f2 = ConversationSummaryBuilder.INSTANCE.formatTurnForSummary(turns.get(1), false);
        assertTrue(f2.contains("[t2] User: Now run the tests"));
        assertTrue(f2.contains("[1 tool call]"));
        assertTrue(f2.contains("Agent: Done, all tests pass."));
    }
}
