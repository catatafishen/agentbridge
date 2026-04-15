package com.github.catatafishen.agentbridge.memory.mining;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;

/**
 * Status bar widget that shows a visible indicator while memory mining is active.
 * Displays "Mining memory..." for per-turn mining and progress text for backfill.
 * Returns empty text when idle so the widget occupies no space.
 */
final class MiningStatusBarWidget implements StatusBarWidget, StatusBarWidget.TextPresentation {

    static final String WIDGET_ID = "AgentBridge.MemoryMining";

    private final Project project;
    private volatile String displayText = "";
    private volatile String tooltipText = "";

    MiningStatusBarWidget(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public @NotNull String ID() {
        return WIDGET_ID;
    }

    @Override
    public void install(@NotNull StatusBar statusBar) {
        MiningTracker tracker = MiningTracker.getInstance(project);

        // Set initial state in case mining is already in progress
        updateFromState(tracker.getState(), null);

        tracker.subscribe(this, (state, progressText) -> {
            updateFromState(state, progressText);
            statusBar.updateWidget(WIDGET_ID);
        });
    }

    private void updateFromState(@NotNull MiningTracker.MiningState state,
                                 @Nullable String progressText) {
        switch (state) {
            case IDLE -> {
                displayText = "";
                tooltipText = "";
            }
            case MINING_TURN -> {
                displayText = "Mining memory...";
                tooltipText = "Extracting memories from the latest conversation turn";
            }
            case BACKFILLING -> {
                displayText = progressText != null
                    ? "Mining: " + progressText
                    : "Mining history...";
                tooltipText = "Mining past conversation sessions into semantic memory";
            }
        }
    }

    @Override
    public @NotNull String getText() {
        return displayText;
    }

    @Override
    public float getAlignment() {
        return Component.LEFT_ALIGNMENT;
    }

    @Override
    public @Nullable String getTooltipText() {
        return tooltipText.isEmpty() ? null : tooltipText;
    }

    @Override
    public @NotNull TextPresentation getPresentation() {
        return this;
    }

    @Override
    public void dispose() {
        // Listener auto-disposed via Disposer tree (subscribe registered with this as parent)
    }
}
