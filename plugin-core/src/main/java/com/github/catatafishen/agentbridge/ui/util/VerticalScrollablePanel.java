package com.github.catatafishen.agentbridge.ui.util;

import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;

/**
 * A {@link JPanel} that implements {@link Scrollable} to track the viewport width
 * (suppressing the horizontal scrollbar) while allowing vertical scrolling.
 *
 * <p>Use this inside a {@link javax.swing.JScrollPane} when you need a vertically
 * scrollable container whose children stretch to the full viewport width.
 */
public final class VerticalScrollablePanel extends JPanel implements Scrollable {

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        return JBUI.scale(16);
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        return visibleRect.height;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }
}
