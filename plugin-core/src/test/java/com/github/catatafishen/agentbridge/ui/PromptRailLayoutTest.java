package com.github.catatafishen.agentbridge.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PromptRailLayoutTest {

    // A rail of 220 px with 10 px insets maps the content onto 10..210, so 200 rail pixels.
    private static final int RAIL = 220;
    private static final int INSET = 10;
    private static final int GAP = 12;

    @Test
    void noPromptsGiveNoDots() {
        assertArrayEquals(new int[0], PromptRailLayout.dotCenters(new int[0], 1000, RAIL, INSET, GAP));
    }

    @Test
    void dotsSitWhereTheirPromptsAreInTheConversation() {
        int[] centers = PromptRailLayout.dotCenters(new int[]{0, 500, 1000}, 1000, RAIL, INSET, GAP);

        assertArrayEquals(new int[]{10, 110, 210}, centers);
    }

    @Test
    void dotsTooCloseArePushedApartInOrder() {
        int[] centers = PromptRailLayout.dotCenters(new int[]{100, 110, 120}, 1000, RAIL, INSET, GAP);

        assertArrayEquals(new int[]{30, 42, 54}, centers, "the first keeps its place, the next ones move down");
    }

    @Test
    void crowdedDotsAtTheBottomArePushedUpInsideTheRail() {
        int[] centers = PromptRailLayout.dotCenters(new int[]{990, 995, 1000}, 1000, RAIL, INSET, GAP);

        assertArrayEquals(new int[]{186, 198, 210}, centers, "the last dot stays at the bottom inset");
    }

    @Test
    void tooManyPromptsForTheRailAreSpacedEvenly() {
        int[] ys = {0, 10, 20, 30, 40};
        int[] centers = PromptRailLayout.dotCenters(ys, 1000, 50, INSET, GAP);

        assertArrayEquals(new int[]{10, 17, 25, 32, 40}, centers, "4 gaps of 12 px do not fit in 30 px");
    }

    @Test
    void aPromptBelowTheContentEndIsKeptOnTheRail() {
        int[] centers = PromptRailLayout.dotCenters(new int[]{2000}, 1000, RAIL, INSET, GAP);

        assertArrayEquals(new int[]{210}, centers);
    }

    @Test
    void emptyContentDoesNotDivideByZero() {
        int[] centers = PromptRailLayout.dotCenters(new int[]{0}, 0, RAIL, INSET, GAP);

        assertArrayEquals(new int[]{10}, centers);
    }

    @Test
    void aHiddenRailKeepsNoSpace() {
        assertEquals(0, PromptRailLayout.reservedWidth(false, 12, true, 14));
    }

    @Test
    void besideAnOverlayScrollbarTheRailKeepsRoomForBoth() {
        assertEquals(26, PromptRailLayout.reservedWidth(true, 12, true, 14),
            "the scrollbar is drawn over the strip's right part, so the rail goes left of it");
    }

    @Test
    void besideAnOpaqueScrollbarTheRailKeepsOnlyItsOwnWidth() {
        assertEquals(12, PromptRailLayout.reservedWidth(true, 12, false, 14),
            "the scroll pane already keeps room for an opaque scrollbar");
    }

    @Test
    void besideAnOverlayScrollbarDotsAreMidwayToTheScrollbarsMiddle() {
        assertEquals(13, PromptRailLayout.dotCenterX(20, true, 14),
            "13 px from the chat, 14 px from the scrollbar's middle at 27");
    }

    @Test
    void besideAnOpaqueScrollbarDotsAreInTheRailsMiddle() {
        assertEquals(10, PromptRailLayout.dotCenterX(20, false, 14));
    }

    @Test
    void currentPromptIsTheLastOneAboveTheUpperThirdOfTheView() {
        int[] ys = {0, 400, 700, 1200};

        // The view shows 600..900 of 2000: its upper third ends at 700.
        assertEquals(2, PromptRailLayout.currentIndex(ys, 600, 300, 2000));
        // At 500..800 the line is at 600, above the prompt at 700.
        assertEquals(1, PromptRailLayout.currentIndex(ys, 500, 300, 2000));
    }

    @Test
    void atTheBottomTheLastVisiblePromptIsCurrent() {
        int[] ys = {0, 400, 1900};

        // The newest prompt is low on screen because its reply is short.
        assertEquals(2, PromptRailLayout.currentIndex(ys, 1700, 300, 2000));
    }

    @Test
    void noPromptIsCurrentAboveTheFirstOne() {
        assertEquals(-1, PromptRailLayout.currentIndex(new int[]{500, 900}, 0, 300, 2000));
    }

    @Test
    void hitTestPicksTheNearestDotWithinReach() {
        int[] centers = {10, 30, 50};

        assertEquals(1, PromptRailLayout.hitTest(centers, 33, 6));
        assertEquals(2, PromptRailLayout.hitTest(centers, 56, 6), "6 px away is still in reach");
        assertEquals(-1, PromptRailLayout.hitTest(centers, 40, 6), "between two dots, out of reach of both");
        assertEquals(-1, PromptRailLayout.hitTest(new int[0], 10, 6));
    }

    @Test
    void scrollTargetPutsTheRowBelowTheTopMargin() {
        assertEquals(492, PromptRailLayout.scrollTarget(500, 2000, 300, 8));
    }

    @Test
    void scrollTargetStaysWithinTheScrollRange() {
        assertEquals(0, PromptRailLayout.scrollTarget(4, 2000, 300, 8), "the first prompt cannot scroll above 0");
        assertEquals(1700, PromptRailLayout.scrollTarget(1900, 2000, 300, 8), "the last one stops at the bottom");
        assertEquals(0, PromptRailLayout.scrollTarget(100, 200, 300, 8), "content shorter than the view");
    }

    @Test
    void previewKeepsTheFirstNonBlankLines() {
        assertEquals("one\ntwo", PromptRailLayout.preview("  one  \n\n two \n", 3, 100));
    }

    @Test
    void previewCutsExtraLinesWithAnEllipsis() {
        assertEquals("one\ntwo…", PromptRailLayout.preview("one\ntwo\nthree", 2, 100));
    }

    @Test
    void previewCutsLongTextWithAnEllipsis() {
        assertEquals("abcd…", PromptRailLayout.preview("abcdefgh", 3, 5));
    }

    @Test
    void previewDoesNotSplitAnEmojiAtTheCut() {
        // The emoji is a surrogate pair at index 3 and 4; a cut after 4 chars would keep only its first half.
        assertEquals("abc…", PromptRailLayout.preview("abc😀defgh", 3, 5));
    }

    @Test
    void previewOfAShortPromptIsUnchanged() {
        assertEquals("Fix the test", PromptRailLayout.preview("Fix the test", 3, 100));
    }
}
