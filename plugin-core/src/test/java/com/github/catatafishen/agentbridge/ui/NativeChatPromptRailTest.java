package com.github.catatafishen.agentbridge.ui;

import com.github.catatafishen.agentbridge.bridge.EntryData;
import com.intellij.ide.util.PropertiesComponent;
import kotlin.Unit;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * The prompt rail beside the chat: a dot for each prompt the user sent, shown from the second prompt on.
 * Clicking a dot scrolls the chat to that prompt and stops auto-scroll, so streaming output does not pull the
 * chat back down.
 */
public class NativeChatPromptRailTest extends NativeMarkdownPaneTestBase {

    /** Enough one-line prompts to make the chat taller than the 500 px view. */
    private static final int PROMPTS = 30;

    private final List<NativeChatPanel> panels = new ArrayList<>();

    /** The light fixture reuses one project, so the preference must not leak between tests. */
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        PropertiesComponent.getInstance(getProject()).unsetValue(NativeChatPanel.PREF_SHOW_PROMPT_RAIL);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            panels.forEach(NativeChatPanel::clear);
            panels.clear();
            PropertiesComponent.getInstance(getProject()).unsetValue(NativeChatPanel.PREF_SHOW_PROMPT_RAIL);
        } finally {
            super.tearDown();
        }
    }

    public void testRailAppearsFromTheSecondPrompt() {
        NativeChatPanel panel = newPanel();
        PromptRail rail = rail(panel);

        panel.addPromptEntry("first question", null, null);
        assertFalse("one prompt needs no navigation", rail.isVisible());

        panel.finishResponse(0, "", "1x");
        panel.addPromptEntry("second question", null, null);
        assertTrue(rail.isVisible());
        assertEquals(2, rail.promptCount());
    }

    public void testPermissionDecisionsGetNoDot() {
        NativeChatPanel panel = newPanel();
        panel.addPromptEntry("do something", null, null);
        panel.showPermissionRequest("req-1", "List Project Files", "{}", response -> Unit.INSTANCE);

        button(panel, "Allow Once").doClick();

        assertEquals("the Allow Once bubble answers within the turn; it is not a prompt",
            1, rail(panel).promptCount());
        assertFalse(rail(panel).isVisible());
    }

    public void testClearingTheChatForgetsItsPrompts() {
        NativeChatPanel panel = panelWithTwoPrompts();

        panel.clear();

        assertEquals(0, rail(panel).promptCount());
        assertFalse(rail(panel).isVisible());
    }

    public void testTurningTheRailOffHidesItAndIsRemembered() {
        NativeChatPanel panel = panelWithTwoPrompts();

        panel.setPromptRailEnabled(false);

        assertFalse(rail(panel).isVisible());
        assertFalse("a new chat panel starts with the rail off", newPanel().getPromptRailEnabled());

        panel.setPromptRailEnabled(true);
        assertTrue(rail(panel).isVisible());
    }

    public void testTheRailSitsBetweenTheChatAndTheScrollbar() {
        NativeChatPanel panel = panelWithManyPrompts();
        JScrollPane scrollPane = (JScrollPane) ((ChatPanelApi) panel).getComponent();
        JViewport viewport = scrollPane.getViewport();
        JScrollBar bar = scrollPane.getVerticalScrollBar();
        PromptRail rail = rail(panel);
        assertTrue("precondition: the chat scrolls", bar.isVisible());

        assertEquals("the rail starts where the chat ends", viewport.getX() + viewport.getWidth(), rail.getX());
        assertTrue("the rail ends before the scrollbar: rail " + rail.getBounds() + ", bar " + bar.getBounds(),
            rail.getX() + rail.getWidth() <= bar.getX());
        assertEquals(viewport.getHeight(), rail.getHeight());

        int narrowed = viewport.getWidth();
        panel.setPromptRailEnabled(false);
        settleLayout(scrollPane);
        assertTrue("turning the rail off gives its space back to the chat", viewport.getWidth() > narrowed);
    }

    /** IntelliJ draws the scrollbar over the chat and insets the chat for it; the rail's strip already moves it off. */
    public void testTheChatIsNotInsetAgainForAScrollbarDrawnOverIt() {
        NativeChatPanel panel = panelWithManyPrompts();
        JScrollPane scrollPane = (JScrollPane) ((ChatPanelApi) panel).getComponent();
        JScrollBar bar = scrollPane.getVerticalScrollBar();
        bar.setOpaque(false);
        panel.setPromptRailEnabled(false);
        settleLayout(scrollPane);
        int barInset = ((JComponent) scrollPane.getViewport().getView()).getInsets().right;
        assertTrue("precondition: without the rail the chat is inset for the scrollbar: " + barInset, barInset > 0);

        panel.setPromptRailEnabled(true);
        settleLayout(scrollPane);

        assertEquals("the chat ends at the rail", 0, ((JComponent) scrollPane.getViewport().getView()).getInsets().right);
    }

    public void testTheDotsAreMidwayBetweenThePromptsAndTheScrollbar() {
        NativeChatPanel panel = panelWithManyPrompts();
        JScrollPane scrollPane = (JScrollPane) ((ChatPanelApi) panel).getComponent();
        JScrollBar bar = scrollPane.getVerticalScrollBar();
        bar.setOpaque(false);
        panel.setPromptRailEnabled(false);
        panel.setPromptRailEnabled(true);
        settleLayout(scrollPane);
        PromptRail rail = rail(panel);
        RoundedPanel bubble = bubbleOf(scrollPane, question(PROMPTS - 1));

        int bubbleRight = SwingUtilities.convertPoint(bubble, bubble.getWidth(), 0, scrollPane).x;
        int dotCenter = rail.getX() + rail.dotCenterX();
        int barMiddle = bar.getX() + bar.getWidth() / 2;
        assertTrue("bubble ends at " + bubbleRight + ", dot at " + dotCenter + ", scrollbar middle at " + barMiddle,
            Math.abs((dotCenter - bubbleRight) - (barMiddle - dotCenter)) <= 2);
    }

    public void testClickingADotScrollsToItsPromptAndStopsAutoScroll() {
        NativeChatPanel panel = panelWithManyPrompts();
        JComponent root = ((ChatPanelApi) panel).getComponent();
        JViewport viewport = viewport(panel);
        boolean[] autoScrollDisabled = {false};
        panel.setOnAutoScrollDisabled(() -> {
            autoScrollDisabled[0] = true;
            return Unit.INSTANCE;
        });

        PromptRail rail = rail(panel);
        click(rail, dotOf(rail, question(10)));

        int offset = topOf(root, question(10)) - viewport.getViewPosition().y;
        assertTrue("the clicked prompt is just below the top of the chat: " + offset, offset >= 0 && offset < 40);
        assertTrue("auto-scroll must stop, or streaming would scroll back down", autoScrollDisabled[0]);

        click(rail, dotOf(rail, question(0)));
        assertEquals("the first prompt cannot scroll above the start", 0, viewport.getViewPosition().y);
    }

    /** The newest prompt's target is the bottom the chat already shows, so the chat keeps following the reply. */
    public void testClickingADotWhoseTargetIsTheBottomKeepsAutoScroll() {
        NativeChatPanel panel = panelWithManyPrompts();
        panel.setAutoScroll(true);
        boolean[] autoScrollDisabled = {false};
        panel.setOnAutoScrollDisabled(() -> {
            autoScrollDisabled[0] = true;
            return Unit.INSTANCE;
        });

        PromptRail rail = rail(panel);
        click(rail, dotOf(rail, question(PROMPTS - 1)));

        assertFalse("the view did not move, so nothing would turn auto-scroll back on", autoScrollDisabled[0]);
    }

    public void testSendingAPromptAfterAJumpWatchesTheWheelAgain() {
        NativeChatPanel panel = panelWithManyPrompts();
        JScrollPane scrollPane = (JScrollPane) ((ChatPanelApi) panel).getComponent();
        panel.setAutoScroll(true);
        int wheelListeners = scrollPane.getMouseWheelListeners().length;
        PromptRail rail = rail(panel);
        click(rail, dotOf(rail, question(0)));
        assertTrue("precondition: the jump stops watching the wheel",
            scrollPane.getMouseWheelListeners().length < wheelListeners);

        panel.addPromptEntry("new question", null, null);

        assertEquals("scrolling up with the wheel stops auto-scroll again",
            wheelListeners, scrollPane.getMouseWheelListeners().length);
    }

    /** Streaming queues scrolls to the bottom; one still queued when the user jumps must not undo the jump. */
    public void testAScrollToBottomQueuedBeforeTheJumpDoesNotUndoIt() {
        NativeChatPanel panel = panelWithManyPrompts();
        JViewport viewport = viewport(panel);
        panel.setAutoScroll(true);  // queues a scroll to the bottom, as streaming does

        PromptRail rail = rail(panel);
        click(rail, dotOf(rail, question(0)));
        waitOnEdt(100);

        assertEquals("the chat stays at the clicked prompt", 0, viewport.getViewPosition().y);
    }

    public void testTheWheelOverTheRailScrollsTheChat() {
        NativeChatPanel panel = panelWithManyPrompts();
        JViewport viewport = viewport(panel);
        viewport.setViewPosition(new Point(0, 0));
        panel.setAutoScroll(false);

        PromptRail rail = rail(panel);
        rail.dispatchEvent(new MouseWheelEvent(rail, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0,
            rail.getWidth() / 2, 100, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 3));
        waitOnEdt(400);

        assertTrue("the chat scrolled down", viewport.getViewPosition().y > 0);
    }

    // ------------------------------------------------------------------
    // Machinery
    // ------------------------------------------------------------------

    private NativeChatPanel newPanel() {
        NativeChatPanel panel = new NativeChatPanel(getProject());
        panels.add(panel);
        return panel;
    }

    private NativeChatPanel panelWithTwoPrompts() {
        NativeChatPanel panel = newPanel();
        panel.addPromptEntry("first question", null, null);
        panel.finishResponse(0, "", "1x");
        panel.addPromptEntry("second question", null, null);
        assertTrue("precondition: two prompts show the rail", rail(panel).isVisible());
        return panel;
    }

    /** A chat of {@link #PROMPTS} one-line prompts, taller than its view, scrolled to the bottom. */
    private NativeChatPanel panelWithManyPrompts() {
        NativeChatPanel panel = newPanel();
        JComponent root = ((ChatPanelApi) panel).getComponent();
        root.setSize(400, 500);
        List<EntryData> prompts = new ArrayList<>();
        for (int i = 0; i < PROMPTS; i++) prompts.add(new EntryData.Prompt(question(i)));
        panel.appendEntries(prompts, -1);
        settleLayout(root);

        JViewport viewport = viewport(panel);
        int bottom = viewport.getView().getHeight() - viewport.getExtentSize().height;
        assertTrue("the prompts must be taller than the view for this test: " + bottom, bottom > 0);
        viewport.setViewPosition(new Point(0, bottom));
        return panel;
    }

    private static JViewport viewport(NativeChatPanel panel) {
        return ((JScrollPane) ((ChatPanelApi) panel).getComponent()).getViewport();
    }

    private static String question(int i) {
        return String.format("question %02d", i);
    }

    private void settleLayout(JComponent root) {
        for (int pass = 0; pass < 5; pass++) {
            layoutTree(root);
            waitOnEdt(50);
        }
    }

    private static void layoutTree(Component component) {
        if (component instanceof Container container) {
            container.doLayout();
            for (Component child : container.getComponents()) layoutTree(child);
        }
    }

    /** The y of the dot whose hover preview shows {@code text}. */
    private static int dotOf(PromptRail rail, String text) {
        for (int y = 0; y < rail.getHeight(); y++) {
            MouseEvent hover = new MouseEvent(rail, MouseEvent.MOUSE_MOVED, 0, 0, rail.getWidth() / 2, y, 0, false);
            String tooltip = rail.getToolTipText(hover);
            if (tooltip != null && tooltip.contains(text)) return y;
        }
        fail("no dot previews '" + text + "' in a rail of height " + rail.getHeight());
        return -1;
    }

    private static void click(PromptRail rail, int y) {
        rail.dispatchEvent(new MouseEvent(rail, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0,
            rail.getWidth() / 2, y, 1, false, MouseEvent.BUTTON1));
    }

    /** The y, in the scrolled content, of the user bubble that shows {@code text}. */
    private static int topOf(JComponent root, String text) {
        JViewport viewport = ((JScrollPane) root).getViewport();
        List<NativeMarkdownPane> panes = new ArrayList<>();
        collectPanes(viewport.getView(), panes);
        for (NativeMarkdownPane pane : panes) {
            if (text.equals(pane.getRawText())) {
                return SwingUtilities.convertPoint(pane, 0, 0, viewport.getView()).y;
            }
        }
        fail("no bubble shows '" + text + "'");
        return -1;
    }

    /** The user bubble that shows {@code text}. */
    private static RoundedPanel bubbleOf(JScrollPane scrollPane, String text) {
        List<NativeMarkdownPane> panes = new ArrayList<>();
        collectPanes(scrollPane.getViewport().getView(), panes);
        for (NativeMarkdownPane pane : panes) {
            if (text.equals(pane.getRawText())) {
                RoundedPanel bubble = (RoundedPanel) SwingUtilities.getAncestorOfClass(RoundedPanel.class, pane);
                assertNotNull("the prompt '" + text + "' is in no bubble", bubble);
                return bubble;
            }
        }
        fail("no bubble shows '" + text + "'");
        return null;
    }

    private static void collectPanes(Component c, List<NativeMarkdownPane> out) {
        if (c instanceof NativeMarkdownPane pane) out.add(pane);
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) collectPanes(child, out);
        }
    }

    private static PromptRail rail(NativeChatPanel panel) {
        PromptRail rail = find(((ChatPanelApi) panel).getComponent(), PromptRail.class);
        assertNotNull("the chat must have a prompt rail", rail);
        return rail;
    }

    private static JButton button(NativeChatPanel panel, String text) {
        List<JButton> found = new ArrayList<>();
        collectButtons(((ChatPanelApi) panel).getComponent(), text, found);
        assertFalse("no enabled '" + text + "' button", found.isEmpty());
        return found.getLast();
    }

    private static void collectButtons(Component c, String text, List<JButton> out) {
        if (c instanceof JButton b && text.equals(b.getText()) && b.isEnabled()) out.add(b);
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) collectButtons(child, text, out);
        }
    }

    private static <T extends Component> T find(Component c, Class<T> type) {
        if (type.isInstance(c)) return type.cast(c);
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) {
                T found = find(child, type);
                if (found != null) return found;
            }
        }
        return null;
    }
}
