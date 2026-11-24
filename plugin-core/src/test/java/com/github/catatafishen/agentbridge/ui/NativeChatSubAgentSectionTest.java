package com.github.catatafishen.agentbridge.ui;

import com.github.catatafishen.agentbridge.bridge.EntryData;
import com.github.catatafishen.agentbridge.client.nested.NestedSessionChatView;
import com.github.catatafishen.agentbridge.session.db.ConversationDatabase;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

/**
 * A sub-agent's own work, as OpenCode forwards it while the main agent is still writing: its text streams
 * into its section, and neither that text nor its tool calls split the main agent's bubble. Its chip's popup
 * shows what it was given and what it answered.
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
        panel.updateSubAgentToolCall("t1", "completed", "done", null, false, null, null);
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

    public void testANestedSessionAddsNoChipOfItsOwn() {
        NativeChatPanel withNested = panelWithNestedSession();
        NativeChatPanel plain = new NativeChatPanel(getProject());

        // The chip of the run_nested_session call is the only one; the entry's header shows the state.
        assertEquals("no chip beside the call's own", countToolChips(plain), countToolChips(withNested));
    }

    public void testAnOrdinarySubAgentStillHasItsChip() {
        NativeChatPanel withSubAgent = panelWithSubAgent();
        NativeChatPanel plain = new NativeChatPanel(getProject());

        assertEquals(countToolChips(plain) + 1, countToolChips(withSubAgent));
    }

    public void testANestedSessionStillShowsItsResultWithoutAChip() {
        NativeChatPanel panel = panelWithNestedSession();

        panel.updateSubAgentResult(NESTED_ID, "complete", "the answer", null, false, null);

        assertTrue(rawTexts(panel).contains("the answer"));
        assertEquals("Completed", statusTooltip(panel));
    }

    public void testUpdatingTheDetailsOfANestedSessionNeedsNoChip() {
        NativeChatPanel panel = panelWithNestedSession();

        panel.updateSubAgentDetails(NESTED_ID, "Koog", "Another task", "do it");

        assertTrue(labelTexts(panel).contains("Nested session · Koog"));
    }

    private static int countToolChips(NativeChatPanel panel) {
        return countChips(panel.getComponent());
    }

    private static int countChips(Component c) {
        int n = c instanceof ToolChipComponent ? 1 : 0;
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) {
                n += countChips(child);
            }
        }
        return n;
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

    public void testSubAgentChipPopupShowsItsInputsAndAnswer() {
        NativeChatPanel panel = new NativeChatPanel(getProject());
        panel.addSubAgentEntry("sa2", "explore", "Find usages", "Find all usages of Foo",
            new ChatPanelApi.SubAgentInitialState());
        panel.updateSubAgentResult("sa2", "completed", FINDING, null, false, null);

        String inputs = panel.popupInputs("sa2");
        assertTrue(inputs, inputs.contains("\"agent\":\"explore\""));
        assertTrue(inputs, inputs.contains("\"prompt\":\"Find all usages of Foo\""));
        assertEquals(FINDING, panel.popupOutput("sa2"));
    }

    public void testDetailsArrivingLaterFillTheChipPopupInputs() {
        NativeChatPanel panel = panelWithSubAgent();
        panel.updateSubAgentDetails("sa1", "explore", "Find usages", "Find all usages of Foo");

        assertTrue(panel.popupInputs("sa1"), panel.popupInputs("sa1").contains("\"prompt\":\"Find all usages of Foo\""));
    }

    public void testBackgroundNotesDoNotReplaceTheChipPopupOutput() {
        NativeChatPanel panel = panelWithSubAgent();
        String launch = "The subagent is working in the background (sessionID: ses_1).";
        panel.updateSubAgentToolCall("sa1", "running", launch, null, false, null, null);
        panel.updateSubAgentResult("sa1", "running",
            "Working in the background. The main agent reports the result when the sub-agent finishes.", null, false, null);
        panel.updateSubAgentResult("sa1", "completed", "✓ Finished. The main agent's report follows.", null, false, null);

        assertEquals(launch, panel.popupOutput("sa1"));
    }

    public void testBackgroundNotesAreRecognisedButAnswersAreNot() {
        assertTrue(PromptOrchestrator.isBackgroundNote("✓ Finished. The main agent's report follows."));
        assertTrue(PromptOrchestrator.isBackgroundNote("✖ The sub-agent failed: rate limited"));
        assertFalse(PromptOrchestrator.isBackgroundNote(FINDING));
        assertFalse(PromptOrchestrator.isBackgroundNote("Finished. The main agent's report follows."));
    }

    // ── a nested session is framed apart from the other sub-agents ──

    private static final String NESTED_ID = NestedSessionChatView.ENTRY_ID_PREFIX + "abc-123";

    private NativeChatPanel panelWithNestedSession() {
        NativeChatPanel panel = new NativeChatPanel(getProject());
        panel.addSubAgentEntry(NESTED_ID, "Koog", "Review", "do it", new ChatPanelApi.SubAgentInitialState());
        return panel;
    }

    public void testANestedSessionHasAHeaderThatNamesItAndItsAgent() {
        NativeChatPanel panel = panelWithNestedSession();

        List<String> labels = labelTexts(panel);

        assertTrue("the header names the nested session and its agent, got " + labels,
            labels.contains("Nested session \u00B7 Koog"));
    }

    public void testANestedSessionIsDrawnInAFrameOfItsOwn() {
        NativeChatPanel panel = panelWithNestedSession();

        assertEquals("the nested session sits in one frame", 1, countRoundedPanelsWithBorder(panel));
    }

    public void testAnOrdinarySubAgentHasNoNestedHeaderOrFrame() {
        NativeChatPanel panel = panelWithSubAgent();

        assertFalse(labelTexts(panel).stream().anyMatch(t -> t.startsWith("Nested session")));
        assertEquals(0, countRoundedPanelsWithBorder(panel));
    }

    public void testTheHeaderShowsTheSessionAsRunningUntilItEnds() {
        NativeChatPanel panel = panelWithNestedSession();

        assertEquals("Running", statusTooltip(panel));
    }

    public void testTheHeaderShowsWhenTheSessionIsDone() {
        NativeChatPanel panel = panelWithNestedSession();

        panel.updateSubAgentResult(NESTED_ID, "complete", null, null, false, null);

        assertEquals("Completed", statusTooltip(panel));
    }

    public void testTheHeaderShowsWhenTheSessionFailed() {
        NativeChatPanel panel = panelWithNestedSession();

        panel.updateSubAgentResult(NESTED_ID, "failed", "rate limited", null, false, null);

        assertEquals("Failed", statusTooltip(panel));
    }

    public void testTheStateIsAnIconAndTakesNoTextInTheHeader() {
        NativeChatPanel panel = panelWithNestedSession();
        panel.updateSubAgentResult(NESTED_ID, "complete", null, null, false, null);

        javax.swing.JLabel status = statusLabel(panel);

        assertNotNull("the status has an icon", status.getIcon());
        assertTrue("no words beside the icon", isBlank(status.getText()));
    }

    public void testEachStateHasAnIconOfItsOwn() {
        NativeChatPanel panel = panelWithNestedSession();
        javax.swing.Icon running = statusLabel(panel).getIcon();
        panel.updateSubAgentResult(NESTED_ID, "complete", null, null, false, null);
        javax.swing.Icon done = statusLabel(panel).getIcon();
        panel.updateSubAgentResult(NESTED_ID, "failed", null, null, false, null);
        javax.swing.Icon failed = statusLabel(panel).getIcon();

        assertNotSame(running, done);
        assertNotSame(done, failed);
        assertNotSame(running, failed);
    }

    public void testASessionWithoutAnAnswerAddsNoLineThatOnlyRepeatsTheState() {
        NativeChatPanel panel = panelWithNestedSession();

        panel.updateSubAgentResult(NESTED_ID, "complete", null, null, false, null);

        List<String> texts = rawTexts(panel);
        assertFalse("no 'Completed' line, got " + texts, texts.stream().anyMatch(t -> t.contains("Completed")));
    }

    public void testAFailedSessionWithoutAnswerAddsNoLineThatOnlyRepeatsTheState() {
        NativeChatPanel panel = panelWithNestedSession();

        panel.updateSubAgentResult(NESTED_ID, "failed", null, null, false, null);

        List<String> texts = rawTexts(panel);
        assertFalse("no 'Failed' line, got " + texts, texts.stream().anyMatch(t -> t.contains("Failed")));
    }

    public void testASessionWithAnAnswerStillShowsIt() {
        NativeChatPanel panel = panelWithNestedSession();

        panel.updateSubAgentResult(NESTED_ID, "complete", "the answer", null, false, null);

        assertTrue(rawTexts(panel).contains("the answer"));
    }

    public void testAnOrdinarySubAgentStillGetsItsCompletedLine() {
        NativeChatPanel panel = panelWithSubAgent();

        panel.updateSubAgentResult("sa1", "complete", null, null, false, null);

        assertTrue(rawTexts(panel).stream().anyMatch(t -> t.contains("Completed")));
    }

    private static javax.swing.JLabel statusLabel(NativeChatPanel panel) {
        javax.swing.JLabel found = findStatusLabel(panel.getComponent());
        assertNotNull("the nested session's header has a status", found);
        return found;
    }

    private static String statusTooltip(NativeChatPanel panel) {
        return statusLabel(panel).getToolTipText();
    }

    private static boolean isBlank(String text) {
        return text == null || text.isEmpty();
    }

    /** The status is the label of the header that has a tooltip and no text: its icon carries the meaning. */
    private static javax.swing.JLabel findStatusLabel(Component c) {
        if (c instanceof javax.swing.JLabel label && label.getIcon() != null && isBlank(label.getText())
            && label.getToolTipText() != null) {
            return label;
        }
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) {
                javax.swing.JLabel found = findStatusLabel(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    public void testANestedSessionsAnswerStreamsIntoItsFrame() {
        NativeChatPanel panel = panelWithNestedSession();

        panel.appendSubAgentText(NESTED_ID, "the ");
        panel.appendSubAgentText(NESTED_ID, "answer");

        assertTrue(rawTexts(panel).contains("the answer"));
    }

    public void testANestedSessionDoesNotShiftTheColoursOfTheSubAgentsAfterIt() {
        NativeChatPanel withNested = new NativeChatPanel(getProject());
        withNested.addSubAgentEntry(NESTED_ID, "Koog", "Review", null, new ChatPanelApi.SubAgentInitialState());
        withNested.addSubAgentEntry("sa1", "explore", "Find", null, new ChatPanelApi.SubAgentInitialState());
        NativeChatPanel without = panelWithSubAgent();

        assertEquals("the sub-agent after a nested session keeps the colour it would have had alone",
            subAgentAccent(without, "sa1"), subAgentAccent(withNested, "sa1"));
    }

    private static Object subAgentAccent(NativeChatPanel panel, String id) {
        try {
            var field = NativeChatPanel.class.getDeclaredField("subAgentSections");
            field.setAccessible(true);
            Object section = ((java.util.Map<?, ?>) field.get(panel)).get(id);
            var accent = section.getClass().getDeclaredMethod("getAccent");
            accent.setAccessible(true);
            return accent.invoke(section);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not read the sub-agent's accent", e);
        }
    }

    private static List<String> labelTexts(NativeChatPanel panel) {
        List<String> out = new ArrayList<>();
        collectLabelTexts(((ChatPanelApi) panel).getComponent(), out);
        return out;
    }

    private static void collectLabelTexts(Component c, List<String> out) {
        if (c instanceof javax.swing.JLabel label && label.getText() != null) {
            out.add(label.getText());
        }
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) {
                collectLabelTexts(child, out);
            }
        }
    }

    /** Counts the rounded panels that draw a border: the frame of a nested session, which no sub-agent has. */
    private static int countRoundedPanelsWithBorder(NativeChatPanel panel) {
        return countFrames(((ChatPanelApi) panel).getComponent());
    }

    private static int countFrames(Component c) {
        int n = 0;
        if (c instanceof RoundedPanel rounded && hasNestedHeaderBelow(rounded)) {
            n++;
        }
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) {
                n += countFrames(child);
            }
        }
        return n;
    }

    private static boolean hasNestedHeaderBelow(Container container) {
        for (Component child : container.getComponents()) {
            if (child instanceof javax.swing.JLabel label && label.getText() != null
                && label.getText().startsWith("Nested session")) {
                return true;
            }
            if (child instanceof Container nested && hasNestedHeaderBelow(nested)) {
                return true;
            }
        }
        return false;
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
