package com.github.catatafishen.agentbridge.ui;

import kotlin.Unit;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * A background sub-agent can call sleep or prompt_user after the turn has ended. Its countdown then brings up the
 * working indicator on its own, and the indicator must go when the countdown ends: no turn is left to be working.
 */
public class NativeChatLateCountdownTest extends NativeMarkdownPaneTestBase {

    private static final String SLEEP_ID = "sleep-1";
    private static final String TIME_EXPIRED = "⏱ Time expired";

    private final List<NativeChatPanel> panels = new ArrayList<>();

    @Override
    protected void tearDown() throws Exception {
        try {
            // clear() stops the indicator's Swing timer and disposes the markdown panes.
            panels.forEach(NativeChatPanel::clear);
            panels.clear();
        } finally {
            super.tearDown();
        }
    }

    public void testASleepAfterTheTurnLeavesNoIndicatorWhenItEnds() {
        NativeChatPanel panel = panelWithEndedTurn();
        startSleep(panel);
        assertTrue("the countdown is shown", indicatorText(panel).startsWith("Sleeping"));

        panel.endSleepRequest(SLEEP_ID);

        assertNull("no turn is running, so nothing is working", indicatorText(panel));
    }

    public void testASleepDuringTheTurnFallsBackToWorking() {
        NativeChatPanel panel = newPanel();
        panel.addPromptEntry("do something", null, null);
        startSleep(panel);

        panel.endSleepRequest(SLEEP_ID);

        String text = indicatorText(panel);
        assertNotNull("the turn is still running", text);
        assertTrue(text, text.startsWith("Working"));
    }

    public void testAnAnsweredAskUserAfterTheTurnLeavesNoIndicator() {
        NativeChatPanel panel = panelWithEndedTurn();
        List<String> answers = askUser(panel, System.currentTimeMillis() + 60_000);
        assertTrue("the countdown is shown", indicatorText(panel).startsWith("Waiting"));

        button(panel, "Yes").doClick();

        assertEquals(List.of("Yes"), answers);
        assertNull(indicatorText(panel));
    }

    public void testATimedOutAskUserAfterTheTurnLeavesNoIndicator() {
        NativeChatPanel panel = panelWithEndedTurn();

        // A deadline already passed times out at once, as the countdown's next tick would.
        List<String> answers = askUser(panel, System.currentTimeMillis() - 1);

        assertTrue("a timeout sends no answer", answers.isEmpty());
        assertNull("no turn is left to take down a \"Time expired\" row", indicatorText(panel));
    }

    public void testATimedOutAskUserDuringTheTurnShowsTimeExpired() {
        NativeChatPanel panel = newPanel();
        panel.addPromptEntry("do something", null, null);

        askUser(panel, System.currentTimeMillis() - 1);

        assertEquals("the turn's indicator stays until the turn ends", TIME_EXPIRED, indicatorText(panel));
    }

    public void testAPromptSentDuringALateSleepKeepsItsIndicatorWhenTheSleepEnds() {
        NativeChatPanel panel = panelWithEndedTurn();
        startSleep(panel);
        panel.addPromptEntry("next prompt", null, null);

        panel.endSleepRequest(SLEEP_ID);

        String text = indicatorText(panel);
        assertNotNull("the new turn is running", text);
        assertTrue(text, text.startsWith("Working"));
    }

    private NativeChatPanel newPanel() {
        NativeChatPanel panel = new NativeChatPanel(getProject());
        panels.add(panel);
        return panel;
    }

    private NativeChatPanel panelWithEndedTurn() {
        NativeChatPanel panel = newPanel();
        panel.addPromptEntry("do something", null, null);
        panel.finishResponse(0, "", "1x");
        assertNull("precondition: an ended turn shows no indicator", indicatorText(panel));
        return panel;
    }

    private static void startSleep(NativeChatPanel panel) {
        long deadline = System.currentTimeMillis() + 60_000;
        panel.showSleepRequest(SLEEP_ID, deadline, new SleepControls() {
            @Override
            public long getMaxDeadlineEpochMs() {
                return deadline + 60_000;
            }

            @Override
            public long extend(int seconds) {
                return deadline + seconds * 1000L;
            }

            @Override
            public void skip() {
                // Not exercised: the tests end the sleep with endSleepRequest.
            }
        });
    }

    /** Shows a prompt_user with a "Yes" reply and returns the list its answers are added to. */
    private static List<String> askUser(NativeChatPanel panel, long deadlineEpochMs) {
        List<String> answers = new ArrayList<>();
        panel.showAskUserRequest("ask-1", "Continue?", List.of("Yes"), deadlineEpochMs,
            answer -> {
                answers.add(answer);
                return Unit.INSTANCE;
            },
            () -> System.currentTimeMillis() + 120_000,
            () -> Unit.INSTANCE);
        return answers;
    }

    private static String indicatorText(NativeChatPanel panel) {
        List<JLabel> found = new ArrayList<>();
        collect(root(panel), JLabel.class, c -> {
            String text = c.getText();
            return text != null
                && (text.startsWith("Working") || text.startsWith("Sleeping") || text.startsWith("Waiting")
                || text.equals(TIME_EXPIRED));
        }, found);
        return found.isEmpty() ? null : found.get(0).getText();
    }

    private static JButton button(NativeChatPanel panel, String text) {
        List<JButton> found = new ArrayList<>();
        collect(root(panel), JButton.class, b -> text.equals(b.getText()) && b.isEnabled(), found);
        assertFalse("no enabled button " + text, found.isEmpty());
        return found.get(found.size() - 1);
    }

    private static JComponent root(NativeChatPanel panel) {
        return ((ChatPanelApi) panel).getComponent();
    }

    private static <T extends Component> void collect(Component c, Class<T> type, Predicate<T> match, List<T> out) {
        if (type.isInstance(c) && match.test(type.cast(c))) out.add(type.cast(c));
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) collect(child, type, match, out);
        }
    }
}
