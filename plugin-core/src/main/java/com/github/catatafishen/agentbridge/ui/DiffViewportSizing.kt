package com.github.catatafishen.agentbridge.ui

/**
 * Pure sizing rules for the scrollable diff block in the edit-approval card.
 *
 * A scroll pane sized to exactly `contentHeight` still grows a vertical scrollbar as soon as a
 * horizontal scrollbar appears, because the horizontal bar steals its own height from the
 * viewport. The result is a pointless vertical scrollbar on diffs that are only a few pixels
 * over. [viewportHeight] accounts for that bar, so the vertical scrollbar only appears when the
 * diff genuinely exceeds [maxHeight].
 *
 * No Swing/IntelliJ dependencies, so it is unit-testable in isolation.
 */
object DiffViewportSizing {

    /**
     * Height the scroll pane's viewport area (excluding its own border insets) should prefer.
     *
     * @param contentHeight preferred height of the diff text
     * @param contentWidth preferred (unwrapped) width of the diff text
     * @param availableWidth width the viewport has when no vertical scrollbar is shown; `<= 0`
     *   means the pane has not been laid out yet, in which case no horizontal bar is assumed
     * @param horizontalBarHeight height the horizontal scrollbar takes when visible
     * @param maxHeight maximum viewport height; beyond this the diff scrolls vertically
     */
    fun viewportHeight(
        contentHeight: Int,
        contentWidth: Int,
        availableWidth: Int,
        horizontalBarHeight: Int,
        maxHeight: Int,
    ): Int {
        val needsHorizontalBar = availableWidth > 0 && contentWidth > availableWidth
        val fitted = contentHeight + if (needsHorizontalBar) horizontalBarHeight else 0
        return minOf(fitted, maxHeight)
    }

    /** Whether the diff is taller than the viewport cap, i.e. vertical scrolling is really needed. */
    fun needsVerticalScroll(contentHeight: Int, maxHeight: Int): Boolean = contentHeight > maxHeight
}
