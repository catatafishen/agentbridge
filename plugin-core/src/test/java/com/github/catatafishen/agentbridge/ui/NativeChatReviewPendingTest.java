package com.github.catatafishen.agentbridge.ui;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The "waiting for your review" message the chat shows while a tool call is paused on the agent-edit
 * review gate. It must offer Approve / Open review while pending, and after the wait ends it must stay
 * in the chat with its buttons greyed out and its text rewritten, so the user can still see what
 * happened without being able to act on a stale request.
 */
public class NativeChatReviewPendingTest extends NativeMarkdownPaneTestBase {

    private static final String APPROVE = "Approve changes";
    private static final String OPEN = "Open review";

    private static final class CountingControls implements ReviewPendingControls {
        final AtomicInteger approvals = new AtomicInteger();
        final AtomicInteger opens = new AtomicInteger();

        @Override
        public void approve() {
            approvals.incrementAndGet();
        }

        @Override
        public void openReview() {
            opens.incrementAndGet();
        }
    }

    public void testShowsMessageWithBothButtonsEnabled() {
        NativeChatPanel panel = new NativeChatPanel(getProject());

        panel.showReviewPending("req-1", "git commit", 3, new CountingControls());

        assertNotNull("approve button must be shown", button(panel, APPROVE));
        assertNotNull("open review button must be shown", button(panel, OPEN));
        assertTrue(button(panel, APPROVE).isEnabled());
        assertTrue(button(panel, OPEN).isEnabled());
        String text = allMarkdown(panel);
        assertTrue("message must name the paused operation: " + text, text.contains("git commit"));
        assertTrue("message must give the file count: " + text, text.contains("3 files"));
    }

    public void testSingularFileWording() {
        NativeChatPanel panel = new NativeChatPanel(getProject());

        panel.showReviewPending("req-1", "git commit", 1, new CountingControls());

        String text = allMarkdown(panel);
        assertTrue("singular expected: " + text, text.contains("1 file "));
        assertFalse("plural must not appear for one file: " + text, text.contains("1 files"));
    }

    public void testApproveButtonRunsControlsAndDisablesItself() {
        NativeChatPanel panel = new NativeChatPanel(getProject());
        CountingControls controls = new CountingControls();
        panel.showReviewPending("req-1", "git commit", 2, controls);

        button(panel, APPROVE).doClick();

        assertEquals(1, controls.approvals.get());
        assertEquals(0, controls.opens.get());
        assertFalse("approve must not be clickable twice", button(panel, APPROVE).isEnabled());
    }

    public void testOpenReviewButtonRunsControlsAndStaysEnabled() {
        NativeChatPanel panel = new NativeChatPanel(getProject());
        CountingControls controls = new CountingControls();
        panel.showReviewPending("req-1", "git commit", 2, controls);

        button(panel, OPEN).doClick();
        button(panel, OPEN).doClick();

        assertEquals("opening the review panel is repeatable", 2, controls.opens.get());
        assertEquals(0, controls.approvals.get());
        assertTrue(button(panel, OPEN).isEnabled());
    }

    public void testEndApprovedKeepsMessageAndGreysButtons() {
        NativeChatPanel panel = new NativeChatPanel(getProject());
        panel.showReviewPending("req-1", "git commit", 2, new CountingControls());

        panel.endReviewPending("req-1", true);

        assertNotNull("the message must stay in the chat", button(panel, APPROVE));
        assertFalse(button(panel, APPROVE).isEnabled());
        assertFalse(button(panel, OPEN).isEnabled());
        String text = allMarkdown(panel);
        assertTrue("text must say the review is complete: " + text, text.contains("Review complete"));
        assertFalse("pending wording must be replaced: " + text, text.contains("Waiting for your review"));
    }

    public void testEndNotApprovedSaysNoLongerPending() {
        NativeChatPanel panel = new NativeChatPanel(getProject());
        panel.showReviewPending("req-1", "git commit", 2, new CountingControls());

        panel.endReviewPending("req-1", false);

        assertFalse(button(panel, APPROVE).isEnabled());
        assertFalse(button(panel, OPEN).isEnabled());
        String text = allMarkdown(panel);
        assertTrue("text must say it is no longer pending: " + text, text.contains("no longer pending"));
        assertFalse("must not claim approval: " + text, text.contains("Review complete"));
    }

    public void testEndUnknownIdIsNoOp() {
        NativeChatPanel panel = new NativeChatPanel(getProject());
        panel.showReviewPending("req-1", "git commit", 2, new CountingControls());

        panel.endReviewPending("some-other-id", true);

        assertTrue("an unrelated id must not touch the live message", button(panel, APPROVE).isEnabled());
        assertTrue(allMarkdown(panel).contains("Waiting for your review"));
    }

    public void testEndingTwiceIsHarmless() {
        NativeChatPanel panel = new NativeChatPanel(getProject());
        panel.showReviewPending("req-1", "git commit", 2, new CountingControls());

        panel.endReviewPending("req-1", true);
        panel.endReviewPending("req-1", false);

        assertTrue("the first outcome wins: " + allMarkdown(panel),
            allMarkdown(panel).contains("Review complete"));
    }

    public void testShowingSameIdTwiceDoesNotDuplicate() {
        NativeChatPanel panel = new NativeChatPanel(getProject());
        panel.showReviewPending("req-1", "git commit", 2, new CountingControls());
        panel.showReviewPending("req-1", "git commit", 2, new CountingControls());

        assertEquals("one message per request id", 1, buttons(panel, APPROVE).size());
    }

    public void testSeparateRequestsGetSeparateMessages() {
        NativeChatPanel panel = new NativeChatPanel(getProject());
        panel.showReviewPending("req-1", "git commit", 2, new CountingControls());
        panel.endReviewPending("req-1", true);

        panel.showReviewPending("req-2", "git commit", 1, new CountingControls());

        List<JButton> approves = buttons(panel, APPROVE);
        assertEquals("the earlier message stays and a new one is added", 2, approves.size());
        assertFalse("earlier message stays greyed out", approves.get(0).isEnabled());
        assertTrue("new message is live", approves.get(1).isEnabled());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static List<JButton> buttons(NativeChatPanel panel, String text) {
        List<JButton> found = new ArrayList<>();
        collectButtons(((ChatPanelApi) panel).getComponent(), text, found);
        return found;
    }

    private static JButton button(NativeChatPanel panel, String text) {
        List<JButton> found = buttons(panel, text);
        return found.isEmpty() ? null : found.get(0);
    }

    private static void collectButtons(Component c, String text, List<JButton> out) {
        if (c instanceof JButton b && text.equals(b.getText())) out.add(b);
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) collectButtons(child, text, out);
        }
    }

    private static String allMarkdown(NativeChatPanel panel) {
        StringBuilder sb = new StringBuilder();
        collectMarkdown(((ChatPanelApi) panel).getComponent(), sb);
        return sb.toString();
    }

    private static void collectMarkdown(Component c, StringBuilder out) {
        if (c instanceof NativeMarkdownPane pane) out.append(pane.getRawText()).append('\n');
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) collectMarkdown(child, out);
        }
    }
}
