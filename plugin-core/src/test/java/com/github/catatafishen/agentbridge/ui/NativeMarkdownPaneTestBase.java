package com.github.catatafishen.agentbridge.ui;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import javax.swing.Timer;
import java.awt.SecondaryLoop;
import java.awt.Toolkit;

/**
 * Base for tests that instantiate a {@link NativeMarkdownPane} against the
 * platform fixture. Disposes the per-test conversation database after each
 * test so SQLite handles do not leak across tests, with a short grace period
 * for the disposal to settle before the fixture tears the project down.
 */
public abstract class NativeMarkdownPaneTestBase extends BasePlatformTestCase {

    @Override
    protected void tearDown() throws Exception {
        try {
            com.github.catatafishen.agentbridge.session.db.ConversationDatabase
                .getInstance(getProject()).dispose();
            Thread.sleep(100);
        } finally {
            super.tearDown();
        }
    }

    /** Keeps the EDT pumping events for {@code delayMs}, so the pane's timers and invokeLater calls run. */
    protected void waitOnEdt(int delayMs) {
        SecondaryLoop loop = Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
        Timer timer = new Timer(delayMs, event -> {
            ((Timer) event.getSource()).stop();
            loop.exit();
        });
        timer.setRepeats(false);
        timer.start();
        assertTrue("wait timer did not fire", loop.enter());
    }
}
