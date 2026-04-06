package com.github.catatafishen.agentbridge.ui;

import org.intellij.lang.annotations.MagicConstant;

import java.awt.*;

/**
 * FlowLayout subclass that fully supports wrapping of components.
 * Unlike FlowLayout, preferred/minimum size accounts for wrapping
 * so parent containers allocate the correct height.
 */
public class WrapLayout extends FlowLayout {

    public WrapLayout(@MagicConstant(intValues = {LEFT, CENTER, RIGHT, LEADING, TRAILING}) int align, int hgap, int vgap) {
        super(align, hgap, vgap);
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        return computeSize(target, true);
    }

    @Override
    public Dimension minimumLayoutSize(Container target) {
        return computeSize(target, false);
    }

    private Dimension computeSize(Container target, boolean preferred) {
        synchronized (target.getTreeLock()) {
            int targetWidth = target.getWidth();
            if (targetWidth == 0) {
                targetWidth = Integer.MAX_VALUE;
            }

            Insets insets = target.getInsets();
            int maxWidth = targetWidth - insets.left - insets.right;
            int hgap = getHgap();
            int vgap = getVgap();

            int x = 0;
            int y = insets.top + vgap;
            int rowHeight = 0;

            for (int i = 0; i < target.getComponentCount(); i++) {
                Component c = target.getComponent(i);
                if (!c.isVisible()) continue;

                Dimension d = preferred ? c.getPreferredSize() : c.getMinimumSize();

                if (x > 0 && x + d.width > maxWidth) {
                    y += rowHeight + vgap;
                    x = 0;
                    rowHeight = 0;
                }

                x += d.width + hgap;
                rowHeight = Math.max(rowHeight, d.height);
            }

            y += rowHeight + vgap + insets.bottom;
            return new Dimension(maxWidth + insets.left + insets.right, y);
        }
    }
}
