package com.github.catatafishen.agentbridge.ui;

import com.github.catatafishen.agentbridge.client.nested.NestedSessionChatView;
import com.github.catatafishen.agentbridge.session.db.ConversationDatabase;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import javax.swing.JComponent;
import java.awt.Component;
import java.awt.Container;

/**
 * The "Show Sub-Agents" toggle hides each sub-agent's section (its prompt, chips, reasoning and text) while its
 * chip in the main agent's row stays; nested sessions always show. A sub-agent's reasoning streams into a
 * collapsed thought chip of its section.
 */
public class NativeChatSubAgentToggleTest extends BasePlatformTestCase {

    private static final Logger LOG = Logger.getInstance(NativeChatSubAgentToggleTest.class);
    private static final String PROMPT = "Look for Foo";
    private static final String SUB_AGENT_TEXT = "Found 3 usages.";
    private static final String NESTED_ID = NestedSessionChatView.ENTRY_ID_PREFIX + "abc-123";

    /** The light fixture reuses one project, so a stored preference would leak into the next test. */
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        PropertiesComponent.getInstance(getProject()).unsetValue(NativeChatPanel.PREF_SHOW_SUB_AGENTS);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            PropertiesComponent.getInstance(getProject()).unsetValue(NativeChatPanel.PREF_SHOW_SUB_AGENTS);
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
        panel.addSubAgentEntry("sa1", "explore", "Find usages", PROMPT, new ChatPanelApi.SubAgentInitialState());
        panel.appendSubAgentText("sa1", SUB_AGENT_TEXT);
        return panel;
    }

    public void testSectionsShowByDefault() {
        NativeChatPanel panel = panelWithSubAgent();

        assertTrue(panel.getSubAgentSectionsVisible());
        assertTrue(isDisplayed(panel, SUB_AGENT_TEXT));
        assertTrue(isDisplayed(panel, PROMPT));
    }

    public void testTurningItOffHidesTheSectionButKeepsTheChip() {
        NativeChatPanel panel = panelWithSubAgent();

        panel.setSubAgentSectionsVisible(false);

        assertFalse("the sub-agent's text must be hidden", isDisplayed(panel, SUB_AGENT_TEXT));
        assertFalse("the prompt bubble must be hidden", isDisplayed(panel, PROMPT));
        assertEquals("the sub-agent's chip must stay", 1, countDisplayedToolChips(panel.getComponent()));
    }

    public void testTurningItBackOnShowsWhatStreamedWhileHidden() {
        NativeChatPanel panel = panelWithSubAgent();
        panel.setSubAgentSectionsVisible(false);
        panel.appendSubAgentText("sa1", " More.");

        panel.setSubAgentSectionsVisible(true);

        assertTrue(isDisplayed(panel, SUB_AGENT_TEXT + " More."));
        assertTrue(isDisplayed(panel, PROMPT));
    }

    public void testASubAgentStartedWhileItIsOffStartsHidden() {
        NativeChatPanel panel = new NativeChatPanel(getProject());
        panel.setSubAgentSectionsVisible(false);

        panel.addSubAgentEntry("sa1", "explore", "Find usages", null, new ChatPanelApi.SubAgentInitialState());
        panel.appendSubAgentText("sa1", SUB_AGENT_TEXT);
        // OpenCode reveals the prompt only later.
        panel.updateSubAgentDetails("sa1", "explore", "Find usages", PROMPT);

        assertFalse(isDisplayed(panel, SUB_AGENT_TEXT));
        assertFalse("a prompt that arrives later must be hidden too", isDisplayed(panel, PROMPT));
    }

    public void testANestedSessionStaysWhenSubAgentsAreHidden() {
        NativeChatPanel panel = new NativeChatPanel(getProject());
        panel.setSubAgentSectionsVisible(false);

        panel.addSubAgentEntry(NESTED_ID, "Koog", "Review", "do it", new ChatPanelApi.SubAgentInitialState());
        panel.appendSubAgentText(NESTED_ID, "Nested answer");

        assertTrue(isDisplayed(panel, "Nested answer"));
    }

    public void testTheChoiceIsKeptForTheNextPanel() {
        NativeChatPanel panel = new NativeChatPanel(getProject());

        panel.setSubAgentSectionsVisible(false);

        assertFalse(PropertiesComponent.getInstance(getProject())
            .getBoolean(NativeChatPanel.PREF_SHOW_SUB_AGENTS, true));
        assertFalse(new NativeChatPanel(getProject()).getSubAgentSectionsVisible());
    }

    public void testSubAgentReasoningGoesIntoACollapsedThoughtChipOfItsSection() {
        NativeChatPanel panel = panelWithSubAgent();

        panel.appendSubAgentThought("sa1", "Where is ");
        panel.appendSubAgentThought("sa1", "Foo used?");

        ChipStripPanel strip = findStripWithThinkingChip(panel.getComponent());
        assertNotNull("the section must get a thought chip", strip);
        assertFalse("the reasoning starts collapsed", strip.isThinkingBubbleVisible());
        assertEquals("the chunks must join in one bubble", 1, countThinkingChips(panel.getComponent()));
        assertNotNull(findPane(panel.getComponent(), "Where is Foo used?"));
        NativeMarkdownPane text = findPane(panel.getComponent(), SUB_AGENT_TEXT);
        assertNotNull(text);
        assertEquals("the reasoning must not join the sub-agent's text", SUB_AGENT_TEXT, text.getRawText());
    }

    public void testReasoningForAnUnknownSubAgentIsDropped() {
        NativeChatPanel panel = new NativeChatPanel(getProject());

        panel.appendSubAgentThought("missing", "lost");

        assertEquals(0, countThinkingChips(panel.getComponent()));
    }

    /** Whether the pane holding [text] would be painted: it and every ancestor up to the chat are visible. */
    private static boolean isDisplayed(NativeChatPanel panel, String text) {
        JComponent root = panel.getComponent();
        NativeMarkdownPane pane = findPane(root, text);
        assertNotNull("no pane holds '" + text + "'", pane);
        return isDisplayedWithin(pane, root);
    }

    private static boolean isDisplayedWithin(Component c, Component root) {
        for (Component at = c; at != null; at = at.getParent()) {
            if (!at.isVisible()) return false;
            if (at == root) return true;
        }
        return false;
    }

    private static NativeMarkdownPane findPane(Component c, String text) {
        if (c instanceof NativeMarkdownPane pane) {
            return pane.getRawText().contains(text) ? pane : null;
        }
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) {
                NativeMarkdownPane found = findPane(child, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static int countDisplayedToolChips(JComponent root) {
        return countDisplayedToolChips(root, root);
    }

    private static int countDisplayedToolChips(Component c, Component root) {
        int n = c instanceof ToolChipComponent && isDisplayedWithin(c, root) ? 1 : 0;
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) n += countDisplayedToolChips(child, root);
        }
        return n;
    }

    private static int countThinkingChips(Component c) {
        int n = c instanceof ThinkingChipComponent ? 1 : 0;
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) n += countThinkingChips(child);
        }
        return n;
    }

    private static ChipStripPanel findStripWithThinkingChip(Component c) {
        if (c instanceof ChipStripPanel strip && countThinkingChips(strip) > 0) return strip;
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) {
                ChipStripPanel found = findStripWithThinkingChip(child);
                if (found != null) return found;
            }
        }
        return null;
    }
}
