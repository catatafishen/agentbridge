package com.github.catatafishen.agentbridge.ui;

import com.github.catatafishen.agentbridge.session.db.ConversationDatabase;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

/**
 * A sub-agent's section shows its text and tool calls in the order they came, as the main agent's turn does: tool
 * calls after text go into a chip strip below it, text after tool calls into a bubble below them, and the result
 * bubble stays last.
 */
public class NativeChatSubAgentOrderTest extends BasePlatformTestCase {

    private static final Logger LOG = Logger.getInstance(NativeChatSubAgentOrderTest.class);
    private static final String SA = "sa1";
    private static final String FIRST = "Let me look.";
    private static final String SECOND = "Found it.";

    /** The light fixture reuses one project, so a stored preference would leak in from another test. */
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        PropertiesComponent.getInstance(getProject()).unsetValue(NativeChatPanel.PREF_SHOW_SUB_AGENTS);
    }

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

    public void testTextAfterToolCallsStartsABubbleBelowThem() {
        NativeChatPanel panel = panelWithSubAgent();

        panel.appendSubAgentText(SA, FIRST, "msg_1");
        panel.addSubAgentToolCall(SA, "t1", "read_file", null, "read");
        panel.addSubAgentToolCall(SA, "t2", "search_text", null, "search");
        panel.appendSubAgentText(SA, SECOND, "msg_2");
        panel.addSubAgentToolCall(SA, "t3", "read_file", null, "read");

        assertEquals(List.of(text(FIRST), "chips:2", text(SECOND), "chips:1"), sectionLayout(panel));
    }

    /** OpenCode sends a tool call in mid-sentence; the rest of the sentence has the same message id. */
    public void testToolCallInMidSentenceKeepsTheSentenceInOneBubble() {
        NativeChatPanel panel = panelWithSubAgent();

        panel.appendSubAgentText(SA, "I'll ", "msg_1");
        panel.addSubAgentToolCall(SA, "t1", "execute", null, "other");
        panel.appendSubAgentText(SA, "search the repository.", "msg_1");

        assertEquals(List.of(text("I'll search the repository."), "chips:1"), sectionLayout(panel));
    }

    /** Claude's sub-agent text has no message id. */
    public void testTextWithoutMessageIdStartsABubbleAfterToolCalls() {
        NativeChatPanel panel = panelWithSubAgent();

        panel.appendSubAgentText(SA, FIRST, null);
        panel.appendSubAgentText(SA, " More.", null);
        panel.addSubAgentToolCall(SA, "t1", "read_file", null, "read");
        panel.appendSubAgentText(SA, SECOND, null);

        assertEquals(List.of(text(FIRST + " More."), "chips:1", text(SECOND)), sectionLayout(panel));
    }

    public void testReasoningGoesIntoTheStripWhereItCameAndDropsTheBreakBeforeTheNextBubble() {
        NativeChatPanel panel = panelWithSubAgent();

        panel.addSubAgentToolCall(SA, "t1", "read_file", null, "read");
        panel.appendSubAgentText(SA, FIRST, "msg_1");
        panel.appendSubAgentThought(SA, "Now search.");
        panel.addSubAgentToolCall(SA, "t2", "search_text", null, "search");
        panel.appendSubAgentText(SA, "\n\n" + SECOND, "msg_2");

        assertEquals(List.of("chips:1", text(FIRST), "chips:1+thought", text(SECOND)), sectionLayout(panel));
    }

    public void testBlankReasoningAddsNoThoughtChip() {
        NativeChatPanel panel = panelWithSubAgent();

        panel.appendSubAgentText(SA, FIRST, "msg_1");
        panel.appendSubAgentThought(SA, "\n");
        panel.appendSubAgentText(SA, " More.", "msg_1");

        assertEquals(List.of(text(FIRST + " More.")), sectionLayout(panel));
    }

    public void testTheResultBubbleStaysLast() {
        NativeChatPanel panel = panelWithSubAgent();
        String note = "Working in the background.";
        panel.updateSubAgentResult(SA, "running", note, null, false, null);

        panel.appendSubAgentText(SA, FIRST, "msg_1");
        panel.addSubAgentToolCall(SA, "t1", "read_file", null, "read");
        panel.appendSubAgentText(SA, SECOND, "msg_2");

        assertEquals(List.of(text(FIRST), "chips:1", text(SECOND), text(note)), sectionLayout(panel));
    }

    private NativeChatPanel panelWithSubAgent() {
        NativeChatPanel panel = new NativeChatPanel(getProject());
        panel.addSubAgentEntry(SA, "explore", "Find usages", null, new ChatPanelApi.SubAgentInitialState());
        return panel;
    }

    private static String text(String raw) {
        return "text:" + raw;
    }

    /**
     * The visible rows of the sub-agent's section, top to bottom: {@code text:<markdown>} for a bubble and
     * {@code chips:<tool chips>[+thought]} for a chip strip. The main agent's strip with the sub-agent's chip comes
     * first and is left out.
     */
    private static List<String> sectionLayout(NativeChatPanel panel) {
        List<String> rows = new ArrayList<>();
        collectRows(((ChatPanelApi) panel).getComponent(), rows);
        assertEquals("the main agent's strip holds the sub-agent's chip", "chips:1", rows.getFirst());
        return rows.subList(1, rows.size());
    }

    private static void collectRows(Component c, List<String> out) {
        if (!c.isVisible()) return;
        if (c instanceof ChipStripPanel strip) {
            out.add("chips:" + count(strip, ToolChipComponent.class)
                + (count(strip, ThinkingChipComponent.class) > 0 ? "+thought" : ""));
            return;
        }
        if (c instanceof NativeMarkdownPane pane) {
            out.add(text(pane.getRawText()));
            return;
        }
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) collectRows(child, out);
        }
    }

    private static int count(Component c, Class<?> type) {
        int n = type.isInstance(c) ? 1 : 0;
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) n += count(child, type);
        }
        return n;
    }
}
