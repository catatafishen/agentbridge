package com.github.catatafishen.agentbridge.memory.mining;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import kotlinx.coroutines.CoroutineScope;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

/**
 * Factory for the memory mining status bar indicator.
 * Registered in plugin.xml as a {@code statusBarWidgetFactory}.
 */
public final class MiningStatusBarWidgetFactory implements StatusBarWidgetFactory {

    @Override
    public @NotNull String getId() {
        return MiningStatusBarWidget.WIDGET_ID;
    }

    @Override
    public @Nls @NotNull String getDisplayName() {
        return "Memory Mining Status";
    }

    @Override
    public @NotNull StatusBarWidget createWidget(@NotNull Project project,
                                                 @NotNull CoroutineScope scope) {
        return new MiningStatusBarWidget(project);
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }
}
