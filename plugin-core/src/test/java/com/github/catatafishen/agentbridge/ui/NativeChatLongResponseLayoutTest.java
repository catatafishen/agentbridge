package com.github.catatafishen.agentbridge.ui;

import javax.swing.JComponent;
import javax.swing.text.View;
import java.awt.Component;
import java.awt.Container;
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
        if (root instanceof NativeMarkdownPane pane && pane.getRawText().startsWith("Response line 1:")) {
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
