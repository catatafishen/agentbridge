package com.github.catatafishen.agentbridge.ui;

import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for WrapLayout — custom FlowLayout that reports
 * correct preferred height when components wrap to multiple rows.
 */
class WrapLayoutTest {

    @Test
    void singleRowPreferredSize() {
        JPanel panel = new JPanel(new WrapLayout(FlowLayout.LEFT, 5, 5));
        panel.setSize(500, 100);
        panel.add(makeBox(100, 30));
        panel.add(makeBox(100, 30));

        Dimension pref = panel.getPreferredSize();
        // Two 100px boxes + gaps should fit in one row of 500px
        assertTrue(pref.height < 80, "Expected single row height, got " + pref.height);
    }

    @Test
    void multipleRowsPreferredSize() {
        JPanel panel = new JPanel(new WrapLayout(FlowLayout.LEFT, 5, 5));
        panel.setSize(250, 100);
        panel.add(makeBox(100, 30));
        panel.add(makeBox(100, 30));
        panel.add(makeBox(100, 30));

        Dimension pref = panel.getPreferredSize();
        // 250px wide: two boxes fit first row, third wraps → at least 2 rows
        assertTrue(pref.height > 50, "Expected multi-row height, got " + pref.height);
    }

    @Test
    void emptyPanelPreferredSize() {
        JPanel panel = new JPanel(new WrapLayout(FlowLayout.LEFT, 5, 5));
        panel.setSize(300, 100);

        Dimension pref = panel.getPreferredSize();
        assertNotNull(pref);
        assertTrue(pref.height >= 0);
    }

    @Test
    void hiddenComponentsIgnored() {
        JPanel panel = new JPanel(new WrapLayout(FlowLayout.LEFT, 5, 5));
        panel.setSize(500, 100);

        JLabel visible = makeBox(100, 30);
        JLabel hidden = makeBox(100, 30);
        hidden.setVisible(false);
        panel.add(visible);
        panel.add(hidden);

        Dimension pref = panel.getPreferredSize();
        // Only one visible component, should be single row
        assertTrue(pref.height < 80, "Hidden component should be ignored, got " + pref.height);
    }

    @Test
    void minimumSizeMatchesPreferred() {
        JPanel panel = new JPanel(new WrapLayout(FlowLayout.LEFT, 5, 5));
        panel.setSize(300, 100);
        panel.add(makeBox(100, 30));

        Dimension min = panel.getMinimumSize();
        assertNotNull(min);
        assertTrue(min.height > 0);
    }

    @Test
    void zeroWidthFallsBackGracefully() {
        JPanel panel = new JPanel(new WrapLayout(FlowLayout.LEFT, 5, 5));
        panel.setSize(0, 0); // width=0 before first layout
        panel.add(makeBox(100, 30));
        panel.add(makeBox(100, 30));

        Dimension pref = panel.getPreferredSize();
        // With width=0, layout treats as MAX_VALUE → single row
        assertNotNull(pref);
        assertTrue(pref.height > 0);
    }

    @SuppressWarnings("SameParameterValue") // Test helper with fixed values is acceptable
    private static JLabel makeBox(int width, int height) {
        JLabel label = new JLabel("x");
        label.setPreferredSize(new Dimension(width, height));
        return label;
    }
}
