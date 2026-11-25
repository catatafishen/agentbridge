package com.github.catatafishen.agentbridge.session;

import com.github.catatafishen.agentbridge.bridge.EntryData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A sub-agent's streamed conversation is recorded in the shape its section shows it: reasoning, tool calls in
 * the order they started, then the text, which tool calls do not split.
 */
class SubAgentTranscriptsTest {

    private static final String TS = "2026-11-25T10:00:00Z";
    private SubAgentTranscripts transcripts;

    @BeforeEach
    void setUp() {
        transcripts = new SubAgentTranscripts();
    }

    @Test
    void nothingStreamedGivesAnEmptyTranscript() {
        assertTrue(transcripts.snapshot("sa1").isEmpty());
    }

    @Test
    void reasoningThenToolsThenTextWhateverOrderTheyStreamedIn() {
        transcripts.appendText("sa1", "I'll ", TS, "opencode");
        transcripts.addToolCall("sa1", "t1", "search_text", "{}", "search", TS, "opencode");
        transcripts.appendThinking("sa1", "Look for ", TS, "opencode");
        transcripts.appendText("sa1", "search the repository.", TS, "opencode");
        transcripts.addToolCall("sa1", "t2", "read_file", null, null, TS, "opencode");
        transcripts.appendThinking("sa1", "Foo.", TS, "opencode");

        List<EntryData> children = transcripts.snapshot("sa1");

        assertEquals(4, children.size());
        assertEquals("Look for Foo.", assertInstanceOf(EntryData.Thinking.class, children.get(0)).getRaw());
        assertEquals("t1", assertInstanceOf(EntryData.ToolCall.class, children.get(1)).getEntryId());
        EntryData.ToolCall second = assertInstanceOf(EntryData.ToolCall.class, children.get(2));
        assertEquals("t2", second.getEntryId());
        assertEquals("other", second.getKind(), "a tool call without a kind gets the default");
        assertEquals("I'll search the repository.",
            assertInstanceOf(EntryData.Text.class, children.get(3)).getRaw(),
            "a tool call in mid-sentence must not split the text");
    }

    @Test
    void subAgentsAreRecordedApart() {
        transcripts.appendText("sa1", "first", TS, "a");
        transcripts.appendText("sa2", "second", TS, "a");

        assertEquals("first", ((EntryData.Text) transcripts.snapshot("sa1").getFirst()).getRaw());
        assertEquals("second", ((EntryData.Text) transcripts.snapshot("sa2").getFirst()).getRaw());
    }

    @Test
    void toolCallUpdateFillsInStatusResultAndLateArguments() {
        transcripts.addToolCall("sa1", "t1", "execute", null, "other", TS, "a");

        boolean found = transcripts.updateToolCall("t1", new SubAgentTranscripts.ToolCallChange(
            "failed", "exit 1", "Ran tests", true, "denied by rule", "{\"cmd\":\"gradle\"}"));

        assertTrue(found);
        EntryData.ToolCall call = (EntryData.ToolCall) transcripts.snapshot("sa1").getFirst();
        assertEquals("failed", call.getStatus());
        assertEquals("exit 1", call.getResult());
        assertEquals("Ran tests", call.getDescription());
        assertTrue(call.getAutoDenied());
        assertEquals("denied by rule", call.getDenialReason());
        assertEquals("{\"cmd\":\"gradle\"}", call.getArguments());
    }

    @Test
    void updateWithoutResultKeepsTheEarlierOne() {
        transcripts.addToolCall("sa1", "t1", "execute", null, "other", TS, "a");
        transcripts.updateToolCall("t1", new SubAgentTranscripts.ToolCallChange("completed", "done"));

        // Stop ends unfinished tool calls with a status only.
        transcripts.updateToolCall("t1", new SubAgentTranscripts.ToolCallChange("failed"));

        EntryData.ToolCall call = (EntryData.ToolCall) transcripts.snapshot("sa1").getFirst();
        assertEquals("failed", call.getStatus());
        assertEquals("done", call.getResult());
    }

    @Test
    void unfinishedToolCallHasNoStatus() {
        transcripts.addToolCall("sa1", "t1", "execute", null, "other", TS, "a");

        assertNull(((EntryData.ToolCall) transcripts.snapshot("sa1").getFirst()).getStatus(),
            "restore shows a call without status as finished, not spinning");
    }

    @Test
    void updateOfAnUnknownToolCallIsReported() {
        assertFalse(transcripts.updateToolCall("main-tool", new SubAgentTranscripts.ToolCallChange("completed")));
    }

    @Test
    void blankTextAndReasoningAreLeftOut() {
        transcripts.appendText("sa1", "  \n", TS, "a");
        transcripts.appendThinking("sa1", " ", TS, "a");
        transcripts.appendText("sa1", "", TS, "a");

        assertTrue(transcripts.snapshot("sa1").isEmpty());
    }

    @Test
    void snapshotDoesNotChangeWhenTheSubAgentStreamsOn() {
        transcripts.appendText("sa1", "Found ", TS, "a");
        transcripts.addToolCall("sa1", "t1", "execute", null, "other", TS, "a");
        List<EntryData> saved = transcripts.snapshot("sa1");

        transcripts.appendText("sa1", "3 files.", TS, "a");
        transcripts.updateToolCall("t1", new SubAgentTranscripts.ToolCallChange("completed", "ok"));

        assertEquals("Found ", ((EntryData.Text) saved.get(1)).getRaw());
        assertNull(((EntryData.ToolCall) saved.getFirst()).getStatus());
        assertEquals("Found 3 files.", ((EntryData.Text) transcripts.snapshot("sa1").get(1)).getRaw());
    }

    @Test
    void entryIdsStayStableAcrossSnapshots() {
        transcripts.appendText("sa1", "Found ", TS, "a");
        String first = transcripts.snapshot("sa1").getFirst().getEntryId();
        transcripts.appendText("sa1", "more", TS, "a");

        assertEquals(first, transcripts.snapshot("sa1").getFirst().getEntryId(),
            "a later write must replace the same entry, not add another");
    }

    @Test
    void forgetStopsRecordingTheSubAgentAndItsToolCalls() {
        transcripts.addToolCall("sa1", "t1", "execute", null, "other", TS, "a");
        transcripts.appendText("sa2", "other", TS, "a");

        transcripts.forget("sa1");

        assertTrue(transcripts.snapshot("sa1").isEmpty());
        assertFalse(transcripts.updateToolCall("t1", new SubAgentTranscripts.ToolCallChange("completed")));
        assertEquals(1, transcripts.snapshot("sa2").size(), "other sub-agents are kept");
    }

    @Test
    void clearForgetsEverything() {
        transcripts.appendText("sa1", "x", TS, "a");
        transcripts.addToolCall("sa2", "t1", "execute", null, "other", TS, "a");

        transcripts.clear();

        assertTrue(transcripts.snapshot("sa1").isEmpty());
        assertFalse(transcripts.updateToolCall("t1", new SubAgentTranscripts.ToolCallChange("completed")));
    }

    @Test
    void recordsAgentAndTimestampOfTheFirstChunk() {
        transcripts.appendText("sa1", "a", TS, "opencode");
        transcripts.appendText("sa1", "b", "later", "other");

        EntryData.Text text = (EntryData.Text) transcripts.snapshot("sa1").getFirst();
        assertEquals(TS, text.getTimestamp());
        assertEquals("opencode", text.getAgent());
    }
}
