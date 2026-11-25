package com.github.catatafishen.agentbridge.ui;

import com.github.catatafishen.agentbridge.bridge.EntryData;
import com.github.catatafishen.agentbridge.session.db.ConversationDatabase;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

/**
 * What a sub-agent streamed (reasoning, tool calls with their results, text) is attached to its entry when it
 * ends, so it is saved with the conversation and shown again after a restart.
 */
public class BroadcastChatPanelSubAgentTranscriptTest extends BasePlatformTestCase {

    private BroadcastChatPanel panel;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        panel = new BroadcastChatPanel(getProject(), new NativeChatPanel(getProject()));
        panel.addSubAgentEntry("sa1", "explore", "Find usages", "Look for Foo",
            new ChatPanelApi.SubAgentInitialState());
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            Disposer.dispose(panel);
            // Closes the conversation DB before the framework deletes the temp project dir (Windows locks it).
            ConversationDatabase.getInstance(getProject()).dispose();
        } finally {
            super.tearDown();
        }
    }

    private EntryData.SubAgent subAgentEntry() {
        return (EntryData.SubAgent) panel.getEntryStore().getEntries().getFirst();
    }

    private void streamWork() {
        panel.appendSubAgentThought("sa1", "Where is Foo used?");
        panel.addSubAgentToolCall("sa1", "t1", "search_text", "{\"query\":\"Foo\"}", "search");
        panel.updateSubAgentToolCall("t1", "completed", "3 hits", null, false, null, null);
        panel.appendSubAgentText("sa1", "Found 3 usages.");
    }

    public void testTheStreamedConversationIsAttachedWhenTheSubAgentEnds() {
        streamWork();

        panel.updateSubAgentResult("sa1", "completed", "Found 3 usages.", null, false, null);

        List<EntryData> children = subAgentEntry().getChildren();
        assertEquals(3, children.size());
        assertEquals("Where is Foo used?", ((EntryData.Thinking) children.get(0)).getRaw());
        EntryData.ToolCall call = (EntryData.ToolCall) children.get(1);
        assertEquals("completed", call.getStatus());
        assertEquals("3 hits", call.getResult());
        assertEquals("Found 3 usages.", ((EntryData.Text) children.get(2)).getRaw());
    }

    public void testNothingIsAttachedWhileTheSubAgentStillRuns() {
        streamWork();

        // A background sub-agent's launch reports "running"; it has not ended.
        panel.updateSubAgentResult("sa1", "running", "Working in the background", null, false, null);

        assertTrue(subAgentEntry().getChildren().isEmpty());
    }

    public void testAFailedSubAgentKeepsWhatItDid() {
        streamWork();

        panel.updateSubAgentResult("sa1", "failed", null, null, false, null);

        assertEquals(3, subAgentEntry().getChildren().size());
    }

    public void testTheMainAgentsToolCallsAreNotPartOfIt() {
        panel.addToolCallEntry("main-1", "read_file", null, "read", false);
        panel.updateSubAgentToolCall("main-1", "completed", "x", null, false, null, null);
        panel.appendSubAgentText("sa1", "Done.");

        panel.updateSubAgentResult("sa1", "completed", "Done.", null, false, null);

        List<EntryData> children = subAgentEntry().getChildren();
        assertEquals(1, children.size());
        assertInstanceOf(children.getFirst(), EntryData.Text.class);
    }
}
