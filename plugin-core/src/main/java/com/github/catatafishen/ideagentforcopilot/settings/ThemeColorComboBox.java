package com.github.catatafishen.ideagentforcopilot.settings;

import com.github.catatafishen.ideagentforcopilot.ui.ThemeColor;
import com.intellij.ui.JBColor;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * A combo box for picking a {@link ThemeColor}, with a null "Default" option at the top.
 * The selected color is theme-aware: when the IDE theme changes, the swatch automatically
 * reflects the new palette because the underlying {@link JBColor} adapts at paint time.
 */
final class ThemeColorComboBox extends JComboBox<ThemeColor> {

    ThemeColorComboBox() {
        super(buildItems());
        setRenderer(new ThemeColorRenderer());
        setMaximumRowCount(12);
    }

    /** Returns the selected {@link ThemeColor}, or {@code null} if "Default" is selected. */
    @Nullable
    ThemeColor getSelectedThemeColor() {
        Object sel = getSelectedItem();
        return sel instanceof ThemeColor tc ? tc : null;
    }

    /** Selects the given {@link ThemeColor}, or selects "Default" if {@code null}. */
    void setSelectedThemeColor(@Nullable ThemeColor color) {
        setSelectedItem(color);
    }

    private static ThemeColor[] buildItems() {
        ThemeColor[] values = ThemeColor.values();
        ThemeColor[] items = new ThemeColor[values.length + 1];
        // items[0] stays null → rendered as "Default"
        System.arraycopy(values, 0, items, 1, values.length);
        return items;
    }

    /** Renders a colored swatch alongside the color name. */
    private static final class ThemeColorRenderer extends DefaultListCellRenderer {

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                       boolean isSelected, boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(
                list, value, index, isSelected, cellHasFocus);
            if (value == null) {
                label.setText("Default");
                label.setIcon(null);
            } else {
                ThemeColor tc = (ThemeColor) value;
                label.setText(tc.getDisplayName());
                label.setIcon(new SwatchIcon(tc.getColor()));
            }
            return label;
        }
    }

    /** A small rounded-square icon painted in the given theme color. */
    private static final class SwatchIcon implements Icon {
        private static final int SIZE = 12;
        private final JBColor color;

        SwatchIcon(JBColor color) {
            this.color = color;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            g.setColor(color);
            g.fillRoundRect(x, y + 1, SIZE, SIZE - 2, 4, 4);
            g.setColor(color.darker());
            g.drawRoundRect(x, y + 1, SIZE, SIZE - 2, 4, 4);
        }

        @Override
        public int getIconWidth() {
            return SIZE + 4;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }
    }
}
