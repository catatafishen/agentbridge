package com.github.catatafishen.agentbridge.ui;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.text.View;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Exercises the native chat layout with a long response delivered in streaming-sized chunks.
 */
public class NativeChatLongResponseLayoutTest extends NativeMarkdownPaneTestBase {

    private static final int PANEL_WIDTH = 520;
    private static final int CHUNK_SIZE = 128;
    private static final int RESPONSE_LINES = 2_500;

    public void testLongStreamingResponseGetsItsFullMarkdownHeight() {
        NativeChatPanel panel = new NativeChatPanel(getProject());
        try {
            JComponent root = panel.getComponent();
            root.setSize(PANEL_WIDTH, 900);
            String response = longResponse();

            for (int start = 0; start < response.length(); start += CHUNK_SIZE) {
                panel.appendText(response.substring(start, Math.min(start + CHUNK_SIZE, response.length())));
            }
            panel.finishResponse(0, "test-model", "");

            NativeMarkdownPane pane = findResponsePane(root);
            assertNotNull("response markdown pane not found", pane);
            assertEquals("streamed response text must be retained in full", response, pane.getRawText());

            layoutUntilStable(root);
            java.awt.Dimension measuredSize = pane.getPreferredSize();
            int paneWidth = measuredSize.width;
            assertTrue("response pane must have a laid-out width", paneWidth > 0);

            int measuredHeight = measuredSize.height;
            View rootView = pane.getUI().getRootView(pane);
            rootView.setSize((float) paneWidth, Integer.MAX_VALUE);
            int completeHeight = (int) Math.ceil(rootView.getPreferredSpan(View.Y_AXIS));
            Container rowWrapper = pane.getParent().getParent().getParent();
            int rowMaxHeight = rowWrapper.getMaximumSize().height;

            assertTrue("the test response must exceed the Swing measurement limit", completeHeight > 32_767);
            assertTrue(
                "message row maximum height (" + rowMaxHeight + ") must accommodate the full markdown height "
                    + completeHeight + " (pane preferred height=" + measuredHeight + ")",
                rowMaxHeight >= completeHeight
            );
            assertTrue(
                "long response height was truncated: measured=" + measuredHeight + ", complete=" + completeHeight
                    + ", pane width=" + paneWidth,
                measuredHeight >= completeHeight - 2
            );
        } finally {
            panel.dispose();
        }
    }

    public void testLongResponseFollowedByAnotherMessageIsNotClippedAtTheBottom() {
        NativeChatPanel panel = new NativeChatPanel(getProject());
        try {
            JComponent root = panel.getComponent();
            root.setSize(PANEL_WIDTH, 900);
            String response = longResponse();
            for (int start = 0; start < response.length(); start += CHUNK_SIZE) {
                panel.appendText(response.substring(start, Math.min(start + CHUNK_SIZE, response.length())));
            }
            panel.finishResponse(0, "test-model", "");
            panel.addPromptEntry("A follow-up question", null, null);

            NativeMarkdownPane pane = findResponsePane(root);
            assertNotNull("response markdown pane not found", pane);
            com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
            invalidateTree(root);
            layoutUntilStable(root);
            com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
            invalidateTree(root);
            layoutUntilStable(root);
            int paneWidth = pane.getWidth();
            assertTrue("response pane must have a laid-out width", paneWidth > 0);
            View rootView = pane.getUI().getRootView(pane);
            rootView.setSize((float) paneWidth, Integer.MAX_VALUE);
            int completeHeight = (int) Math.ceil(rootView.getPreferredSpan(View.Y_AXIS));

            StringBuilder chain = new StringBuilder();
            for (Container c = pane; c != null; c = c.getParent()) {
                chain.append(c.getClass().getSimpleName()).append("[h=").append(c.getHeight())
                    .append(",pref=").append(c.getPreferredSize().height)
                    .append(",min=").append(c.getMinimumSize().height)
                    .append(",max=").append(c.getMaximumSize().height).append("] < ");
            }
            assertTrue("the test response must exceed the Swing measurement limit", completeHeight > 32_767);
            assertTrue(
                "non-last bubble was clipped: pane height=" + pane.getHeight() + ", complete=" + completeHeight
                    + " chain=" + chain,
                pane.getHeight() >= completeHeight - 2
            );
        } finally {
            panel.dispose();
        }
    }

    public void testRealisticMultiParagraphResponsesAreNotClippedWhenMoreMessagesFollow() {
        for (int width : new int[]{380, 520, 777, 1100}) {
            NativeChatPanel panel = new NativeChatPanel(getProject());
            try {
                JComponent root = panel.getComponent();
                root.setSize(width, 700);
                String response = paragraphResponse();
                for (int start = 0; start < response.length(); start += CHUNK_SIZE) {
                    panel.appendText(response.substring(start, Math.min(start + CHUNK_SIZE, response.length())));
                }
                panel.finishResponse(0, "test-model", "");
                panel.addPromptEntry("Next question", null, null);
                panel.appendText("Second answer.");
                panel.finishResponse(0, "test-model", "");

                NativeMarkdownPane pane = findResponsePane(root);
                assertNotNull("response markdown pane not found", pane);
                for (int i = 0; i < 3; i++) {
                    com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
                    invalidateTree(root);
                    layoutUntilStable(root);
                }
                View rootView = pane.getUI().getRootView(pane);
                rootView.setSize((float) pane.getWidth(), Integer.MAX_VALUE);
                int complete = (int) Math.ceil(rootView.getPreferredSpan(View.Y_AXIS));
                assertTrue("pane width=" + pane.getWidth() + " at panel width " + width + " height="
                        + pane.getHeight() + " complete=" + complete,
                    pane.getHeight() >= complete - 2);
            } finally {
                panel.dispose();
            }
        }
    }

    public void testPaintDoesNotReuseCachedFrameThatIsShorterThanThePane() {
        NativeMarkdownPane pane = new NativeMarkdownPane(new FileNavigator(getProject()));
        try {
            pane.setCompleteMarkdown(paragraphResponse());
            int width = 600;
            int shortHeight = 200;
            int tallHeight = 1200;
            pane.setBounds(0, 0, width, shortHeight);
            paintToImage(pane, width, shortHeight);

            pane.setBounds(0, 0, width, tallHeight);
            BufferedImage img = paintToImage(pane, width, tallHeight);

            boolean drawnBelowOldFrame = false;
            for (int y = shortHeight + 20; y < tallHeight && !drawnBelowOldFrame; y++) {
                for (int x = 0; x < width; x++) {
                    if ((img.getRGB(x, y) >>> 24) != 0) {
                        drawnBelowOldFrame = true;
                        break;
                    }
                }
            }
            assertTrue("area below the previously cached frame must be painted, not left blank", drawnBelowOldFrame);
        } finally {
            pane.dispose();
        }
    }

    private static BufferedImage paintToImage(NativeMarkdownPane pane, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            pane.paint(g);
        } finally {
            g.dispose();
        }
        return img;
    }

    public void testSleepButtonsStayInsideTheBubbleOnNarrowPanels() {
        for (int width : new int[]{260, 340, 520}) {
            NativeChatPanel panel = new NativeChatPanel(getProject());
            try {
                JComponent root = panel.getComponent();
                root.setSize(width, 600);
                long now = System.currentTimeMillis();
                panel.showSleepRequest("req-1", now + 60_000, new SleepControls() {
                    @Override
                    public long getMaxDeadlineEpochMs() {
                        return now + 600_000;
                    }

                    @Override
                    public long extend(int seconds) {
                        return now + 60_000 + seconds * 1000L;
                    }

                    @Override
                    public void skip() {
                        // not needed
                    }
                });
                for (int i = 0; i < 3; i++) {
                    com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
                    invalidateTree(root);
                    layoutUntilStable(root);
                }
                JButton skip = findButton(root, "Skip");
                assertNotNull("Skip button not found", skip);
                Container bubble = skip;
                while (bubble != null && !(bubble instanceof RoundedPanel)) bubble = bubble.getParent();
                assertNotNull("bubble not found", bubble);
                int buttonBottom = SwingUtilities
                    .convertPoint(skip.getParent(), skip.getX(), skip.getY() + skip.getHeight(), bubble).y;
                assertTrue("Skip button is clipped at panel width " + width + ": bottom=" + buttonBottom
                    + " bubble height=" + bubble.getHeight(), buttonBottom <= bubble.getHeight());
            } finally {
                panel.dispose();
            }
        }
    }

    private static JButton findButton(Component root, String text) {
        if (root instanceof JButton b && text.equals(b.getText())) return b;
        if (root instanceof Container c) {
            for (Component child : c.getComponents()) {
                JButton found = findButton(child, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String paragraphResponse() {
        StringBuilder text = new StringBuilder();
        for (int part = 1; part <= 10; part++) {
            text.append("**Teil ").append(part).append(": Ein Titel**\n\n");
            for (int p = 0; p < 4; p++) {
                text.append("Ein Tag am Meer beginnt oft mit dem Geräusch der Wellen. Sie rollen langsam an den ")
                    .append("Strand, ziehen sich zurück und kommen wieder. Die Sonne steigt über den Horizont.\n\n");
            }
        }
        text.append("FINAL RESPONSE MARKER");
        return text.toString();
    }

    private static String longResponse() {
        StringBuilder text = new StringBuilder(RESPONSE_LINES * 90);
        for (int i = 1; i <= RESPONSE_LINES; i++) {
            text.append("Response line ").append(i)
                .append(": this is a long generated explanation that wraps inside the chat bubble. ")
                .append("The layout should keep allocating vertical space as more streamed text arrives.\n");
        }
        text.append("\nFINAL RESPONSE MARKER");
        return text.toString();
    }

    private static NativeMarkdownPane findResponsePane(Component root) {
        if (root instanceof NativeMarkdownPane pane
            && (pane.getRawText().startsWith("Response line 1:") || pane.getRawText().startsWith("**Teil 1:"))) {
            return pane;
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                NativeMarkdownPane found = findResponsePane(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void layoutUntilStable(Component root) {
        String previous = signature(root);
        for (int i = 0; i < 10; i++) {
            layoutTree(root);
            String current = signature(root);
            if (current.equals(previous)) return;
            previous = current;
        }
    }

    private static void invalidateTree(Component component) {
        component.invalidate();
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                invalidateTree(child);
            }
        }
    }

    private static void layoutTree(Component component) {
        if (component instanceof Container container) {
            container.doLayout();
            for (Component child : container.getComponents()) {
                layoutTree(child);
            }
        }
    }

    private static String signature(Component root) {
        List<Component> components = new ArrayList<>();
        collectComponents(root, components);
        StringBuilder result = new StringBuilder(components.size() * 12);
        for (Component component : components) {
            result.append(component.getWidth()).append('x').append(component.getHeight()).append(';');
        }
        return result.toString();
    }

    private static void collectComponents(Component component, List<Component> out) {
        out.add(component);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                collectComponents(child, out);
            }
        }
    }
}
