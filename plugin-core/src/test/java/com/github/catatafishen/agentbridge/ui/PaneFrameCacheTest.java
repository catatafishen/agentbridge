package com.github.catatafishen.agentbridge.ui;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaneFrameCacheTest {

    private static final int BACKDROP = 0x202a33;
    private static final int OTHER_BACKDROP = 0x26221f;

    private static PaneFrameCache cacheWithFrame(Integer backdrop) {
        PaneFrameCache cache = new PaneFrameCache();
        cache.store(new BufferedImage(100, 50, BufferedImage.TYPE_INT_RGB), 100, 50, 7, backdrop);
        return cache;
    }

    @Test
    void emptyCacheNeitherMatchesNorFits() {
        PaneFrameCache cache = new PaneFrameCache();
        assertNull(cache.getImage());
        assertFalse(cache.matches(-1, -1, -1, null));
        assertFalse(cache.fits(100, 50, null));
        assertFalse(cache.isOfVersion(-1));
    }

    @Test
    void matchesOnlyTheExactStateItWasRenderedFor() {
        PaneFrameCache cache = cacheWithFrame(BACKDROP);
        assertTrue(cache.matches(100, 50, 7, BACKDROP));
        assertFalse(cache.matches(101, 50, 7, BACKDROP), "width");
        assertFalse(cache.matches(100, 49, 7, BACKDROP), "height");
        assertFalse(cache.matches(100, 50, 8, BACKDROP), "content version");
        assertFalse(cache.matches(100, 50, 7, OTHER_BACKDROP), "backdrop");
        assertFalse(cache.matches(100, 50, 7, null), "translucent frame wanted");
    }

    @Test
    void translucentFrameMatchesOnlyATranslucentPaint() {
        PaneFrameCache cache = cacheWithFrame(null);
        assertTrue(cache.matches(100, 50, 7, null));
        assertFalse(cache.matches(100, 50, 7, BACKDROP));
    }

    @Test
    void fitsAWiderOrShorterPaneOverTheSameBackdrop() {
        PaneFrameCache cache = cacheWithFrame(BACKDROP);
        assertTrue(cache.fits(100, 50, BACKDROP));
        assertTrue(cache.fits(140, 50, BACKDROP), "wider pane: no text is cropped");
        assertTrue(cache.fits(100, 30, BACKDROP), "shorter pane: no blank bottom");
    }

    @Test
    void doesNotFitANarrowerOrTallerPane() {
        PaneFrameCache cache = cacheWithFrame(BACKDROP);
        assertFalse(cache.fits(80, 50, BACKDROP), "narrower pane would crop text");
        assertFalse(cache.fits(100, 70, BACKDROP), "taller pane would leave a blank bottom");
    }

    @Test
    void doesNotFitOverAnotherBackdrop() {
        PaneFrameCache cache = cacheWithFrame(BACKDROP);
        assertFalse(cache.fits(100, 50, OTHER_BACKDROP));
        assertFalse(cache.fits(100, 50, null));
    }

    @Test
    void clearDropsTheFrame() {
        PaneFrameCache cache = cacheWithFrame(BACKDROP);
        cache.clear();
        assertNull(cache.getImage());
        assertFalse(cache.matches(100, 50, 7, BACKDROP));
        assertFalse(cache.isOfVersion(7));
    }
}
