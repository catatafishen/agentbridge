package com.github.catatafishen.agentbridge.ui;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Geometry and text for {@link PromptRail}, the column of dots beside the chat that marks each prompt the user
 * sent. Pure arithmetic on pixel positions, so it can be tested without Swing.
 *
 * <p>Positions are in two spaces: <em>content</em> pixels (the y of a prompt row in the whole scrollable chat)
 * and <em>rail</em> pixels (the y of a dot in the rail, which is as tall as the visible chat).
 */
public final class PromptRailLayout {

    private static final String ELLIPSIS = "…";

    private PromptRailLayout() {
    }

    /**
     * The centre of each prompt's dot in the rail.
     *
     * <p>A dot sits where its prompt is in the conversation, like a minimap. Dots closer than {@code minGap} are
     * pushed apart, keeping their order and staying within the insets. When the rail is too short for that, the
     * dots are spaced evenly from top to bottom instead.
     *
     * @param promptYs      the y of each prompt row in content pixels, ascending
     * @param contentHeight the height of the whole chat content
     * @param railHeight    the height of the rail
     * @param inset         the space kept free at the top and bottom of the rail
     * @param minGap        the smallest distance between two dot centres
     */
    public static int @NotNull [] dotCenters(int @NotNull [] promptYs, int contentHeight, int railHeight,
                                             int inset, int minGap) {
        int n = promptYs.length;
        int[] centers = new int[n];
        if (n == 0) return centers;
        int top = inset;
        int bottom = Math.max(top, railHeight - inset);
        int span = bottom - top;
        int content = Math.max(1, contentHeight);
        for (int i = 0; i < n; i++) {
            long scaled = (long) Math.clamp(promptYs[i], 0, content) * span / content;
            centers[i] = top + (int) scaled;
        }
        for (int i = 1; i < n; i++) {
            centers[i] = Math.max(centers[i], centers[i - 1] + minGap);
        }
        centers[n - 1] = Math.min(centers[n - 1], bottom);
        for (int i = n - 2; i >= 0; i--) {
            centers[i] = Math.min(centers[i], centers[i + 1] - minGap);
        }
        if (centers[0] < top) return evenlySpaced(n, top, span);
        return centers;
    }

    private static int[] evenlySpaced(int n, int top, int span) {
        int[] centers = new int[n];
        for (int i = 0; i < n; i++) {
            centers[i] = n == 1 ? top + span / 2 : top + (int) ((long) i * span / (n - 1));
        }
        return centers;
    }

    /**
     * How much of the chat's width to keep free for the rail, between the chat and its scrollbar.
     *
     * @param railShown        whether the rail is visible; a hidden rail needs no space
     * @param railWidth        the width of the rail
     * @param barOverlaysChat  whether the scrollbar is drawn over the chat instead of beside it; the strip then
     *                         also covers the scrollbar
     * @param barWidth         the width of the scrollbar
     */
    public static int reservedWidth(boolean railShown, int railWidth, boolean barOverlaysChat, int barWidth) {
        if (!railShown) return 0;
        return barOverlaysChat ? railWidth + barWidth : railWidth;
    }

    /**
     * The x of the dots' centers in the rail. A scrollbar drawn over the chat paints its thumb in the middle of its
     * strip, not at the strip's edge, so the dots are centered between the chat and that middle.
     *
     * @param railWidth       the width of the rail, which starts where the chat ends
     * @param barOverlaysChat whether the scrollbar is drawn over the chat; it then starts where the rail ends
     * @param barWidth        the width of the scrollbar
     */
    public static int dotCenterX(int railWidth, boolean barOverlaysChat, int barWidth) {
        return barOverlaysChat ? (railWidth + barWidth / 2) / 2 : railWidth / 2;
    }

    /**
     * The prompt whose turn is being read: the last one that starts above the upper third of the visible area.
     * At the bottom of the chat it is the last prompt in view, so the newest prompt is current even when its
     * short reply leaves it low on screen.
     *
     * @return an index into {@code promptYs}, or -1 when no prompt starts at or above that line
     */
    public static int currentIndex(int @NotNull [] promptYs, int viewTop, int viewHeight, int contentHeight) {
        int viewBottom = viewTop + viewHeight;
        boolean atBottom = viewBottom >= contentHeight;
        int line = atBottom ? viewBottom : viewTop + viewHeight / 3;
        int current = -1;
        for (int i = 0; i < promptYs.length; i++) {
            if (promptYs[i] <= line) current = i;
        }
        return current;
    }

    /** The dot nearest to {@code y} within {@code reach} pixels, or -1 when no dot is that close. */
    public static int hitTest(int @NotNull [] centers, int y, int reach) {
        int best = -1;
        int bestDistance = reach + 1;
        for (int i = 0; i < centers.length; i++) {
            int distance = Math.abs(centers[i] - y);
            if (distance < bestDistance) {
                best = i;
                bestDistance = distance;
            }
        }
        return best;
    }

    /**
     * Where to scroll so a prompt row sits {@code margin} pixels below the top of the visible area, within the
     * range the chat can scroll.
     */
    public static int scrollTarget(int rowY, int contentHeight, int viewHeight, int margin) {
        int maxTop = Math.max(0, contentHeight - viewHeight);
        return Math.clamp(rowY - (long) margin, 0, maxTop);
    }

    /**
     * The start of a prompt for the hover preview: its first {@code maxLines} non-blank lines, trimmed, cut to
     * {@code maxChars} in all. Ends with an ellipsis when anything was left out.
     */
    public static @NotNull String preview(@NotNull String text, int maxLines, int maxChars) {
        List<String> lines = text.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
        boolean cut = lines.size() > maxLines;
        String joined = String.join("\n", lines.subList(0, Math.min(maxLines, lines.size())));
        if (joined.length() > maxChars) {
            int end = Math.max(0, maxChars - 1);
            // Do not split a surrogate pair (an emoji, say) at the cut.
            if (end > 0 && Character.isHighSurrogate(joined.charAt(end - 1))) end--;
            joined = joined.substring(0, end).stripTrailing();
            cut = true;
        }
        return cut ? joined + ELLIPSIS : joined;
    }
}
