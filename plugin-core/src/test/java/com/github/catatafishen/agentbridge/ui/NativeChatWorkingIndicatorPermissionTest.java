package com.github.catatafishen.agentbridge.ui;

import com.github.catatafishen.agentbridge.bridge.PermissionResponse;
import kotlin.Unit;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The "Working…" indicator steps aside while a permission card waits for the user and comes back once the
 * card is answered or expires, because the agent keeps working after the decision. A sleep or ask-user
 * countdown is never hidden, since its buttons need the user.
 */
public class NativeChatWorkingIndicatorPermissionTest extends NativeMarkdownPaneTestBase {

    private static final String TOOL = "List Project Files";
    private static final String ARGS = "{\"directory\":\"src\"}";
    private static final String ALLOW_ONCE = "Allow Once";

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

    public void testIndicatorIsHiddenWhileTheCardWaits() {
        NativeChatPanel panel = panelWithRunningTurn();

        showPermission(panel, "req-1", new ArrayList<>());

        assertNotNull("the indicator is kept, not removed", indicatorLabel(panel));
        assertFalse("the card replaces the indicator while it waits", indicatorVisible(panel));
    }

    public void testIndicatorReturnsAfterTheUserAllows() {
        NativeChatPanel panel = panelWithRunningTurn();
        List<PermissionResponse> responses = new ArrayList<>();
        showPermission(panel, "req-1", responses);

        button(panel, ALLOW_ONCE).doClick();

        assertEquals(List.of(PermissionResponse.ALLOW_ONCE), responses);
        assertTrue("the agent works on after the approval", indicatorVisible(panel));
    }

    public void testIndicatorReturnsAfterTheUserDenies() {
        NativeChatPanel panel = panelWithRunningTurn();
        showPermission(panel, "req-1", new ArrayList<>());

        button(panel, "Deny").doClick();

        assertTrue("the agent still finishes its turn after a denial", indicatorVisible(panel));
    }

    public void testIndicatorReturnsWhenTheRequestExpires() {
        NativeChatPanel panel = panelWithRunningTurn();
        showPermission(panel, "req-1", new ArrayList<>());

        panel.expirePermissionRequest("req-1");

        assertTrue(indicatorVisible(panel));
    }

    public void testIndicatorStaysHiddenWhileAnotherCardIsPending() {
        NativeChatPanel panel = panelWithRunningTurn();
        showPermission(panel, "req-1", new ArrayList<>());
        showPermission(panel, "req-2", new ArrayList<>());

        panel.resolvePermissionRequest("req-1", PermissionResponse.ALLOW_ONCE);
        assertFalse("req-2 still waits for the user", indicatorVisible(panel));

        panel.resolvePermissionRequest("req-2", PermissionResponse.ALLOW_ONCE);
        assertTrue(indicatorVisible(panel));
    }

    public void testNoIndicatorAfterTheTurnEndedWhileTheCardWaited() {
        NativeChatPanel panel = panelWithRunningTurn();
        showPermission(panel, "req-1", new ArrayList<>());
        panel.finishResponse(0, "", "1x");

        button(panel, ALLOW_ONCE).doClick();

        assertNull("an ended turn has no indicator to bring back", indicatorLabel(panel));
    }

    public void testCardLeftFromAnEndedTurnDoesNotKeepTheNextIndicatorHidden() {
        NativeChatPanel panel = panelWithRunningTurn();
        showPermission(panel, "stale", new ArrayList<>());
        panel.finishResponse(0, "", "1x");
        panel.addPromptEntry("next prompt", null, null);
        showPermission(panel, "req-2", new ArrayList<>());

        panel.resolvePermissionRequest("req-2", PermissionResponse.ALLOW_ONCE);

        assertTrue("the unanswered card from the ended turn never hid this indicator", indicatorVisible(panel));
    }

    public void testAnsweringACardFromAnEndedTurnLeavesTheNextIndicatorAlone() {
        NativeChatPanel panel = panelWithRunningTurn();
        showPermission(panel, "stale", new ArrayList<>());
        panel.finishResponse(0, "", "1x");
        panel.addPromptEntry("next prompt", null, null);
        showPermission(panel, "req-2", new ArrayList<>());

        panel.resolvePermissionRequest("stale", PermissionResponse.DENY);

        assertFalse("req-2 still waits for the user", indicatorVisible(panel));
    }

    public void testSleepCountdownStaysVisibleWhileTheCardWaits() {
        NativeChatPanel panel = panelWithRunningTurn();
        startSleep(panel);

        showPermission(panel, "req-1", new ArrayList<>());

        assertTrue("the countdown's buttons stay reachable", indicatorVisible(panel));
        button(panel, ALLOW_ONCE).doClick();
        assertTrue(indicatorVisible(panel));
        assertTrue("the sleep countdown is still running: " + indicatorLabel(panel).getText(),
            indicatorLabel(panel).getText().startsWith("Sleeping"));
        assertNotNull("the sleep's Skip button is still offered", button(panel, "Skip"));
    }

    public void testSleepStartedWhileACardWaitsIsShown() {
        NativeChatPanel panel = panelWithRunningTurn();
        showPermission(panel, "req-1", new ArrayList<>());

        startSleep(panel);

        assertTrue("a countdown started during the card is shown", indicatorVisible(panel));
        assertTrue(isShowing(panel, button(panel, "Skip")));
    }

    public void testIndicatorHidesAgainWhenTheSleepEndsWhileACardWaits() {
        NativeChatPanel panel = panelWithRunningTurn();
        showPermission(panel, "req-1", new ArrayList<>());
        startSleep(panel);

        panel.endSleepRequest("sleep-1");
        assertFalse("back to plain Working… while req-1 still waits", indicatorVisible(panel));

        button(panel, ALLOW_ONCE).doClick();
        assertTrue(indicatorVisible(panel));
    }

    private NativeChatPanel panelWithRunningTurn() {
        NativeChatPanel panel = new NativeChatPanel(getProject());
        panels.add(panel);
        panel.addPromptEntry("do something", null, null);
        assertTrue("precondition: a sent prompt shows the indicator", indicatorVisible(panel));
        return panel;
    }

    private static void showPermission(NativeChatPanel panel, String reqId, List<PermissionResponse> responses) {
        panel.showPermissionRequest(reqId, TOOL, ARGS, response -> {
            responses.add(response);
            return Unit.INSTANCE;
        });
    }

    private static void startSleep(NativeChatPanel panel) {
        long deadline = System.currentTimeMillis() + 60_000;
        panel.showSleepRequest("sleep-1", deadline, new SleepControls() {
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
                // Not exercised: the tests only check whether the countdown is shown.
            }
        });
    }

    private static JLabel indicatorLabel(NativeChatPanel panel) {
        List<JLabel> found = new ArrayList<>();
        collect(root(panel), JLabel.class, c -> {
            String text = c.getText();
            return text != null && (text.startsWith("Working") || text.startsWith("Sleeping"));
        }, found);
        return found.isEmpty() ? null : found.get(0);
    }

    private static boolean indicatorVisible(NativeChatPanel panel) {
        return isShowing(panel, indicatorLabel(panel));
    }

    /** Visible as the user would see it: the component and every container up to the panel are visible. */
    private static boolean isShowing(NativeChatPanel panel, Component component) {
        if (component == null) return false;
        for (Component c = component; c != null && c != root(panel); c = c.getParent()) {
            if (!c.isVisible()) return false;
        }
        return true;
    }

    private static JButton button(NativeChatPanel panel, String text) {
        List<JButton> found = new ArrayList<>();
        collect(root(panel), JButton.class, b -> text.equals(b.getText()) && b.isEnabled(), found);
        return found.isEmpty() ? null : found.get(found.size() - 1);
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
