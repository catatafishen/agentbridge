package com.github.catatafishen.agentbridge.ui;

import com.github.catatafishen.agentbridge.bridge.EntryData;
import kotlin.Unit;

import javax.swing.JComponent;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

/**
 * Dragging the scrollbar thumb up must hold the chat where the user drags it. While the thumb is held the
 * scrollbar reports every change as "adjusting", so auto-scroll is still on until the release; its safety timer
 * used to scroll to the bottom every 300 ms in the middle of the drag, and the chat flickered between the two.
 */
public class NativeChatScrollbarDragTest extends NativeMarkdownPaneTestBase {

    private static final int PROMPTS = 30;

    private final List<NativeChatPanel> panels = new ArrayList<>();

    @Override
    protected void tearDown() throws Exception {
        try {
            panels.forEach(NativeChatPanel::clear);
            panels.clear();
        } finally {
            super.tearDown();
        }
    }

    public void testAutoScrollDoesNotPullTheThumbBackWhileItIsHeld() {
        NativeChatPanel panel = tallChat();
        JScrollBar bar = scrollBar(panel);
        panel.setAutoScroll(true);
        waitOnEdt(100);
        assertTrue("precondition: auto-scroll keeps the chat at the bottom", bar.getValue() > 0);

        bar.setValueIsAdjusting(true);  // the user presses the thumb
        bar.setValue(0);                // and drags it to the top
        waitOnEdt(700);                 // more than two ticks of the 300 ms safety timer

        assertEquals("the chat stays where the user holds the thumb", 0, bar.getValue());
    }

    public void testReleasingTheThumbAboveTheBottomTurnsAutoScrollOff() {
        NativeChatPanel panel = tallChat();
        JScrollBar bar = scrollBar(panel);
        panel.setAutoScroll(true);
        waitOnEdt(100);
        boolean[] disabled = {false};
        panel.setOnAutoScrollDisabled(() -> {
            disabled[0] = true;
            return Unit.INSTANCE;
        });

        bar.setValueIsAdjusting(true);
        bar.setValue(0);
        bar.setValueIsAdjusting(false);  // the user lets go
        waitOnEdt(700);

        assertTrue("letting go above the bottom turns auto-scroll off", disabled[0]);
        assertEquals("and the chat stays there", 0, bar.getValue());
    }

    // ------------------------------------------------------------------
    // Machinery
    // ------------------------------------------------------------------

    /** A chat of one-line prompts taller than its 500 px view. */
    private NativeChatPanel tallChat() {
        NativeChatPanel panel = new NativeChatPanel(getProject());
        panels.add(panel);
        JComponent root = ((ChatPanelApi) panel).getComponent();
        root.setSize(400, 500);
        List<EntryData> prompts = new ArrayList<>();
        for (int i = 0; i < PROMPTS; i++) prompts.add(new EntryData.Prompt("question " + i));
        panel.appendEntries(prompts, -1);
        for (int pass = 0; pass < 5; pass++) {
            layoutTree(root);
            waitOnEdt(50);
        }
        JScrollBar bar = scrollBar(panel);
        assertTrue("the chat must be taller than its view for this test", bar.getMaximum() > bar.getVisibleAmount());
        return panel;
    }

    private static JScrollBar scrollBar(NativeChatPanel panel) {
        return ((JScrollPane) ((ChatPanelApi) panel).getComponent()).getVerticalScrollBar();
    }

    private static void layoutTree(Component component) {
        if (component instanceof Container container) {
            container.doLayout();
            for (Component child : container.getComponents()) layoutTree(child);
        }
    }
}
