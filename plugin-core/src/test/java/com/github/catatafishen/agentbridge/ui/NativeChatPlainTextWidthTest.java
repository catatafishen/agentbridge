package com.github.catatafishen.agentbridge.ui;

import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.SecondaryLoop;
import java.awt.Toolkit;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks response text at a fixed narrow panel width, without a corrective resize.
 */
public class NativeChatPlainTextWidthTest extends NativeMarkdownPaneTestBase {
    private static final String PARAGRAPH =
        "This is a normal reply with short words and spaces. "
            + "Each line must fit in the chat bubble so that all of the text can be read. "
            + "The panel stays narrow as more text is added. "
            + "No table, link or code block is part of this reply.";
    private static final String RESPONSE = (PARAGRAPH + "\n\n").repeat(4) + "The end.";

    public void testPlainTextFitsAtFixedNarrowWidthsWhileStreaming() throws Exception {
        List<String> failures = new ArrayList<>();
        for (int width : new int[]{180, 220, 260, 320, 400}) {
            checkResponse(width, RESPONSE, false, failures);
        }
        assertTrue(String.join("\n", failures), failures.isEmpty());
    }

    public void testTableDoesNotPushSurroundingProseOutsideTheBubble() throws Exception {
        String response = PARAGRAPH + "\n\n"
            + "| State | Name | Note |\n"
            + "| --- | --- | --- |\n"
            + "| Ready | Chat | Short words in a table cell. |\n\n"
            + PARAGRAPH;
        List<String> failures = new ArrayList<>();
        for (int width : new int[]{180, 220, 260, 320, 400}) {
            checkResponse(width, response, true, failures);
        }
        assertTrue(String.join("\n", failures), failures.isEmpty());
    }

    private void checkResponse(int width, String response, boolean surroundingProseOnly,
                               List<String> failures) throws Exception {
        NativeChatPanel panel = new NativeChatPanel(getProject());
        try {
            JComponent root = panel.getComponent();
            root.setSize(width, 700);
            layoutTree(root);
            pumpEvents(300);
            for (int start = 0; start < response.length(); start += 128) {
                panel.appendText(response.substring(start, Math.min(start + 128, response.length())));
                // Let the real streaming render timer run without blocking the EDT.
                pumpEvents(50);
                assertFits(root, width, "stream offset " + start, surroundingProseOnly, failures);
            }
            panel.finishResponse(0, "test-model", "");
            assertFits(root, width, "completed", surroundingProseOnly, failures);
            pumpEvents(400);
            assertFits(root, width, "settled", surroundingProseOnly, failures);
            NativeMarkdownPane pane = findPane(root);
            assertNotNull("response pane not found", pane);
            assertEquals("the complete response must be retained", response, pane.getRawText());
            assertEquals("the panel must never be resized", width, root.getWidth());
        } finally {
            panel.dispose();
        }
    }

    private void assertFits(JComponent root, int width, String phase, boolean surroundingProseOnly,
                            List<String> failures)
        throws Exception {
        for (int pass = 0; pass < 10; pass++) layoutTree(root);
        NativeMarkdownPane pane = findPane(root);
        assertNotNull("response pane not found", pane);
        assertTrue("response must have a positive width", pane.getWidth() > 0);
        assertTrue("response must have a positive height", pane.getHeight() > 0);

        BufferedImage image = new BufferedImage(pane.getWidth(), pane.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            pane.paint(graphics);
        } finally {
            graphics.dispose();
        }

        // Inspect the painted view's geometry, without forcing a different root-view size.
        String text = pane.getDocument().getText(0, pane.getDocument().getLength());
        Rectangle viewportBounds = SwingUtilities.convertRectangle(
            ((JScrollPane) root).getViewport(),
            new Rectangle(((JScrollPane) root).getViewport().getSize()), pane
        );
        double firstLineY = -1;
        boolean wrapped = false;
        int leadingStart = text.indexOf("This is a normal reply");
        int trailingStart = text.lastIndexOf("This is a normal reply");
        assertTrue("leading paragraph must be present", leadingStart >= 0);
        for (int offset = 0; offset < text.length(); offset++) {
            if (surroundingProseOnly
                && !(offset >= leadingStart && offset < leadingStart + PARAGRAPH.length())
                && !(trailingStart > leadingStart && offset >= trailingStart)) continue;
            if (Character.isWhitespace(text.charAt(offset))) continue;
            Rectangle2D bounds = pane.modelToView2D(offset);
            assertNotNull("missing text position at offset " + offset, bounds);
            if (firstLineY < 0) firstLineY = bounds.getY();
            wrapped |= bounds.getY() > firstLineY;
            if (bounds.getX() < 0 || bounds.getMaxX() > pane.getWidth()
                || bounds.getX() < viewportBounds.getX()
                || bounds.getMaxX() > viewportBounds.getMaxX()) {
                failures.add("panel width=" + width + ", " + phase + ", offset=" + offset
                    + ", character bounds=" + bounds + ", pane width=" + pane.getWidth()
                    + ", viewport=" + viewportBounds);
                return;
            }
        }
        assertTrue("plain text must wrap onto multiple lines", wrapped);
    }

    private static void pumpEvents(int delayMs) {
        SecondaryLoop loop = Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
        Timer timer = new Timer(delayMs, event -> loop.exit());
        timer.setRepeats(false);
        timer.start();
        try {
            assertTrue("could not dispatch Swing events", loop.enter());
        } finally {
            timer.stop();
        }
    }

    private static void layoutTree(Component component) {
        if (component instanceof Container container) {
            container.doLayout();
            for (Component child : container.getComponents()) layoutTree(child);
        }
    }

    private static NativeMarkdownPane findPane(Component component) {
        if (component instanceof NativeMarkdownPane pane) return pane;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                NativeMarkdownPane pane = findPane(child);
                if (pane != null) return pane;
            }
        }
        return null;
    }
}
