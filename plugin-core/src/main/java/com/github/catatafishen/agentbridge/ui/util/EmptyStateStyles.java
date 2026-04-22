package com.github.catatafishen.agentbridge.ui.util;

import com.intellij.util.ui.JBUI;

import javax.swing.border.Border;

/**
 * Shared spacing for centered empty-state labels across side-panel tabs.
 */
public final class EmptyStateStyles {

    public static final Border PLACEHOLDER_PADDING = JBUI.Borders.empty(6, 8, 8, 8);

    private EmptyStateStyles() {
    }
}
