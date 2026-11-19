package com.github.catatafishen.agentbridge.ui;

import com.github.catatafishen.agentbridge.bridge.EntryData;
import com.github.catatafishen.agentbridge.session.db.ConversationDatabase;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

/**
 * A sub-agent's own work, as OpenCode forwards it while the main agent is still writing: its text streams
 * into its section, and neither that text nor its tool calls split the main agent's bubble.
 */
public class NativeChatSubAgentSectionTest extends BasePlatformTestCase {

    private static final Logger LOG = Logger.getInstance(NativeChatSubAgentSectionTest.class);
    private static final String FINDING = "Found 3 issues.";
    private static final String LAST_WORDS = "Done.";

    @Override
    protected void tearDown() throws Exception {
        try {
            // A project service may hold the conversation DB open; close it before the framework deletes
            // the temp project dir (Windows: "being used by another process").
            ConversationDatabase.getInstance(getProject()).dispose();
        } catch (Exception e) {
            // Best effort: the DB may never have been opened by this test.
            LOG.debug("Could not close the conversation DB", e);
        } finally {
            super.tearDown();
        }
    }

    private NativeChatPanel panelWithSubAgent() {
        NativeChatPanel panel = new NativeChatPanel(getProject());
        panel.addSubAgentEntry("sa1", "explore", "Find usages", null, new ChatPanelApi.SubAgentInitialState());
        return panel;
    }

    public void testSubAgentToolCompletingMidSentenceKeepsTheMainBubbleWhole() {
        NativeChatPanel panel = panelWithSubAgent();
        panel.addSubAgentToolCall("sa1", "t1", "execute", null, "other");

        panel.appendText("Searching for occurrences of `Foo");
        panel.updateSubAgentToolCall("t1", "completed", "done", null, false, null);
        panel.appendText("Config`.");

        assertTrue("the main agent's sentence must stay in one bubble, got " + rawTexts(panel),
            rawTexts(panel).contains("Searching for occurrences of `FooConfig`."));
    }

    public void testSubAgentTextStreamsIntoItsSectionNotTheMainBubble() {
        NativeChatPanel panel = panelWithSubAgent();

        panel.appendSubAgentText("sa1", "I'll ");
        panel.appendSubAgentText("sa1", "search the repository.");
        panel.appendText("Started a sub-agent.");

        List<String> texts = rawTexts(panel);
        assertTrue("the sub-agent's chunks must join in one bubble, got " + texts,
            texts.contains("I'll search the repository."));
        assertTrue("the main agent's text must be on its own, got " + texts,
            texts.contains("Started a sub-agent."));
    }

    /** Seen with OpenCode 2.3.2: "I'll ", then the sub-agent's tool call, then "search the repository…". */
    public void testSubAgentToolCallInMidSentenceKeepsTheSectionTextWhole() {
        NativeChatPanel panel = panelWithSubAgent();

        panel.appendSubAgentText("sa1", "I'll ");
        panel.addSubAgentToolCall("sa1", "t1", "execute", null, "other");
        panel.appendSubAgentText("sa1", "search the repository.");

        assertTrue("the sub-agent's sentence must stay in one bubble, got " + rawTexts(panel),
            rawTexts(panel).contains("I'll search the repository."));
    }

    public void testATranscriptIsNotShownAgainAfterTheSectionStreamedIt() {
        NativeChatPanel panel = panelWithSubAgent();
        panel.appendSubAgentText("sa1", FINDING);

        panel.setSubAgentTranscript("sa1", List.of(new EntryData.Text(FINDING), new EntryData.Text(LAST_WORDS)));

        List<String> texts = rawTexts(panel);
        assertEquals("the streamed text is shown once, got " + texts, 1,
            texts.stream().filter(FINDING::equals).count());
        assertFalse(texts.contains(LAST_WORDS));
    }

    public void testATranscriptIsShownWhenNothingWasStreamed() {
        NativeChatPanel panel = panelWithSubAgent();

        panel.setSubAgentTranscript("sa1", List.of(new EntryData.Text(FINDING), new EntryData.Text(LAST_WORDS)));

        assertTrue(rawTexts(panel).contains(FINDING));
    }

    public void testTextForAnUnknownSubAgentIsDropped() {
        NativeChatPanel panel = new NativeChatPanel(getProject());

        panel.appendSubAgentText("missing", "lost");

        assertFalse(rawTexts(panel).contains("lost"));
    }

    private static List<String> rawTexts(NativeChatPanel panel) {
        List<String> out = new ArrayList<>();
        collectRawTexts(((ChatPanelApi) panel).getComponent(), out);
        return out;
    }

    private static void collectRawTexts(Component c, List<String> out) {
        if (c instanceof NativeMarkdownPane pane) {
            out.add(pane.getRawText());
            return;
        }
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) collectRawTexts(child, out);
        }
    }
}
