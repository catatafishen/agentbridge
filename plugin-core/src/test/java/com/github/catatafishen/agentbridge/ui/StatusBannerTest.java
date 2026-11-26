package com.github.catatafishen.agentbridge.ui;

import com.github.catatafishen.agentbridge.session.db.ConversationDatabase;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/**
 * {@link StatusBanner#dismissIfShowing} takes down only the banner it names, so a notice whose condition ended
 * never removes a newer banner the user has not read yet.
 */
public class StatusBannerTest extends BasePlatformTestCase {

    private static final Logger LOG = Logger.getInstance(StatusBannerTest.class);
    private static final String NOTICE = "Agent finished its turn but is still sending messages.";

    private StatusBanner banner;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        banner = new StatusBanner(getTestRootDisposable());
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            // PsiBridgeStartup opens the conversation DB when the test project opens; close it before the
            // framework deletes the project dir (Windows: "being used by another process").
            ConversationDatabase.getInstance(getProject()).dispose();
        } catch (Exception e) {
            LOG.warn("Could not close the conversation DB", e);
        } finally {
            super.tearDown();
        }
    }

    public void testTheNamedBannerIsDismissed() {
        banner.showWarning(NOTICE);
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertTrue(banner.isVisible());

        banner.dismissIfShowing(NOTICE);
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();

        assertFalse(banner.isVisible());
    }

    public void testANoticeDismissedBeforeItAppearedNeverShows() {
        banner.showWarning(NOTICE);
        banner.dismissIfShowing(NOTICE);
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();

        assertFalse(banner.isVisible());
    }

    public void testABannerThatReplacedItStays() {
        banner.showWarning(NOTICE);
        banner.showError("Connection lost");
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();

        banner.dismissIfShowing(NOTICE);
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();

        assertTrue("the error is still unread", banner.isVisible());
    }

    public void testNothingHappensAfterTheBannerWasClosed() {
        banner.showWarning(NOTICE);
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        banner.dismissCurrent();
        banner.showInfo("Saved");
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();

        banner.dismissIfShowing(NOTICE);
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();

        assertTrue("an info shown after the notice closed is not the notice", banner.isVisible());
    }
}
