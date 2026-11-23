package com.github.catatafishen.agentbridge.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Graphics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PaneBackdropTest {

    private static final Color BASE = new Color(40, 40, 40);
    private static final Color FILL = new Color(150, 200, 150, 51);
    /** {@link #FILL} over {@link #BASE}: 20 % of the fill over 80 % of the base. */
    private static final Color FILL_OVER_BASE = new Color(62, 72, 62);

    private static JPanel opaquePanel(Color background) {
        JPanel panel = new JPanel();
        panel.setOpaque(true);
        panel.setBackground(background);
        return panel;
    }

    private static RoundedPanel bubble(Color fill, String style) {
        RoundedPanel bubble = new RoundedPanel(fill, null, 10, BubbleCorner.NONE);
        bubble.setBubbleStyle(style);
        return bubble;
    }

    private static JPanel transparentPanel() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        return panel;
    }

    /** Places a leaf component inside the given containers, outermost first, and returns the leaf. */
    private static JLabel nest(JPanel... containers) {
        for (int i = 1; i < containers.length; i++) containers[i - 1].add(containers[i]);
        JLabel leaf = new JLabel();
        containers[containers.length - 1].add(leaf);
        return leaf;
    }

    @Test
    void overBlendsATranslucentFillOverTheBase() {
        assertEquals(FILL_OVER_BASE, PaneBackdrop.INSTANCE.over(FILL, BASE));
    }

    @Test
    void overWithAnOpaqueFillIsTheFill() {
        assertEquals(Color.RED, PaneBackdrop.INSTANCE.over(Color.RED, BASE));
    }

    @Test
    void resolveCompositesBubbleFillOverTheOpaqueAncestor() {
        JLabel pane = nest(opaquePanel(BASE), transparentPanel(), bubble(FILL, "modern"));
        assertEquals(FILL_OVER_BASE, PaneBackdrop.INSTANCE.resolve(pane));
    }

    @Test
    void resolveAppliesOuterFillsBeforeInnerOnes() {
        JLabel pane = nest(opaquePanel(BASE), bubble(Color.RED, "modern"), bubble(new Color(0, 0, 255, 128), "modern"));
        assertEquals(new Color(127, 0, 128), PaneBackdrop.INSTANCE.resolve(pane));
    }

    @Test
    void resolveUsesTheFillOfAnAccessibleBubble() {
        JLabel pane = nest(opaquePanel(BASE), bubble(FILL, "accessible"));
        assertEquals(FILL_OVER_BASE, PaneBackdrop.INSTANCE.resolve(pane));
    }

    @Test
    void resolveIgnoresTheFillOfAMinimalBubble() {
        JLabel pane = nest(opaquePanel(BASE), bubble(FILL, "minimal"));
        assertEquals(BASE, PaneBackdrop.INSTANCE.resolve(pane));
    }

    @Test
    void resolveStopsAtTheNearestOpaqueAncestor() {
        JLabel pane = nest(opaquePanel(BASE), bubble(FILL, "modern"), opaquePanel(Color.WHITE));
        assertEquals(Color.WHITE, PaneBackdrop.INSTANCE.resolve(pane));
    }

    @Test
    void resolveIsNullWithoutAnOpaqueAncestor() {
        assertNull(PaneBackdrop.INSTANCE.resolve(nest(transparentPanel(), bubble(FILL, "modern"))));
    }

    @Test
    void resolveIsNullWhenTheOpaqueAncestorHasATranslucentBackground() {
        assertNull(PaneBackdrop.INSTANCE.resolve(nest(opaquePanel(new Color(40, 40, 40, 200)))));
    }

    @Test
    void resolveIsNullForAComponentWithoutParent() {
        assertNull(PaneBackdrop.INSTANCE.resolve(new JLabel()));
    }

    @Test
    void resolveIsNullWhenAnAncestorPaintsItsOwnBackground() {
        JPanel custom = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                g.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        custom.setOpaque(false);
        assertNull(PaneBackdrop.INSTANCE.resolve(nest(opaquePanel(BASE), custom, bubble(FILL, "modern"))));
    }

    @Test
    void resolveIsNullWhenAnAncestorPaintsOverItsChildren() {
        JPanel overlay = new JPanel() {
            @Override
            protected void paintChildren(Graphics g) {
                super.paintChildren(g);
                g.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        overlay.setOpaque(false);
        assertNull(PaneBackdrop.INSTANCE.resolve(nest(opaquePanel(BASE), overlay)));
    }

    @Test
    void resolveIsNullWhenABubbleSubclassPaintsItsOwnBackground() {
        RoundedPanel custom = new RoundedPanel(FILL, null, 10, BubbleCorner.NONE) {
            @Override
            protected void paintComponent(Graphics g) {
                g.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        assertNull(PaneBackdrop.INSTANCE.resolve(nest(opaquePanel(BASE), custom)));
    }

    /** Declares a {@code paint} overload that is not a Swing paint hook. */
    public static final class PanelWithPaintOverload extends JPanel {
        public void paint(Graphics g, int times) {
            for (int i = 0; i < times; i++) super.paint(g);
        }
    }

    @Test
    void resolveIgnoresMethodsNamedPaintWithOtherParameters() {
        JPanel helper = new PanelWithPaintOverload();
        helper.setOpaque(false);
        assertEquals(BASE, PaneBackdrop.INSTANCE.resolve(nest(opaquePanel(BASE), helper)));
    }
}
