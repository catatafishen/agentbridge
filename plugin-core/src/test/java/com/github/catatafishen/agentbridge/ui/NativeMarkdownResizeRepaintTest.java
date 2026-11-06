package com.github.catatafishen.agentbridge.ui;

import javax.swing.Timer;
import java.awt.Graphics2D;
import java.awt.SecondaryLoop;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;

public class NativeMarkdownResizeRepaintTest extends NativeMarkdownPaneTestBase {

    public void testNarrowingDuringResizeRewrapsInsteadOfClippingTheCachedFrame() {
        NativeMarkdownPane pane = new NativeMarkdownPane(new FileNavigator(getProject()));
        try {
            pane.setCompleteMarkdown(("The same response must wrap again at the new panel width. ").repeat(12));
            pane.setBounds(0, 0, 360, 180);
            paint(pane);
            waitForTimer(400);
            BufferedImage originalFrame = paint(pane);

            ResizeBurstClock.INSTANCE.tick();
            pane.setBounds(0, 0, 180, 180);
            BufferedImage duringResize = paint(pane);
            BufferedImage croppedOldFrame = crop(originalFrame, duringResize.getWidth(), duringResize.getHeight());
            assertFalse(
                "shrinking the pane must re-wrap text instead of clipping the wider cached frame",
                samePixels(croppedOldFrame, duringResize)
            );

            waitForTimer(400);

            BufferedImage afterResize = paint(pane);
            assertTrue(
                "the settled pane should keep the correctly wrapped frame painted during the shrink",
                samePixels(duringResize, afterResize)
            );
        } finally {
            pane.dispose();
        }
    }

    private void waitForTimer(int delayMs) {
        SecondaryLoop settleLoop = Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
        Timer timer = new Timer(delayMs, event -> {
            ((Timer) event.getSource()).stop();
            settleLoop.exit();
        });
        timer.setRepeats(false);
        timer.start();
        assertTrue("settle timer did not fire", settleLoop.enter());
    }

    private static BufferedImage paint(NativeMarkdownPane pane) {
        BufferedImage image = new BufferedImage(pane.getWidth(), pane.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            pane.paint(graphics);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static BufferedImage crop(BufferedImage source, int width, int height) {
        BufferedImage cropped = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = cropped.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return cropped;
    }

    private static boolean samePixels(BufferedImage first, BufferedImage second) {
        if (first.getWidth() != second.getWidth() || first.getHeight() != second.getHeight()) return false;
        for (int y = 0; y < first.getHeight(); y++) {
            for (int x = 0; x < first.getWidth(); x++) {
                if (first.getRGB(x, y) != second.getRGB(x, y)) return false;
            }
        }
        return true;
    }
}
