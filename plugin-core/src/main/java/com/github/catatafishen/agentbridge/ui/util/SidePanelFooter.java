package com.github.catatafishen.agentbridge.ui.util;

import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.ui.JBColor;
import com.intellij.ui.SideBorder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;

/**
 * Shared footer chrome for side-panel tabs that host compact IntelliJ toolbar actions.
 */
public final class SidePanelFooter {
    private static final int FOOTER_HEIGHT = 32;

    private SidePanelFooter() {
    }

    public static @NotNull JPanel createToolbarFooter(@NotNull ActionToolbar toolbar) {
        return createToolbarFooter(toolbar, null);
    }

    public static @NotNull JPanel createToolbarFooter(@NotNull ActionToolbar toolbar,
                                                      @Nullable JComponent eastComponent) {
        JComponent toolbarComponent = toolbar.getComponent();
        toolbarComponent.setBorder(BorderFactory.createEmptyBorder());
        int footerHeight = JBUI.scale(FOOTER_HEIGHT);
        toolbarComponent.setPreferredSize(new Dimension(0, footerHeight));
        toolbarComponent.setMinimumSize(new Dimension(0, footerHeight));

        Border topBorder = new SideBorder(JBColor.border(), SideBorder.TOP);
        Border padding = BorderFactory.createEmptyBorder(JBUI.scale(2), 0, JBUI.scale(2), 0);
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBorder(BorderFactory.createCompoundBorder(topBorder, padding));
        footer.add(toolbarComponent, BorderLayout.CENTER);
        if (eastComponent != null) {
            footer.add(eastComponent, BorderLayout.EAST);
        }
        return footer;
    }
}
