package com.github.catatafishen.agentbridge.services;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Persistent settings controlling automatic cleanup of agent-created resources.
 */
@Service(Service.Level.PROJECT)
@State(name = "CleanupSettings", storages = @Storage("ideAgentCleanup.xml"))
public final class CleanupSettings implements PersistentStateComponent<CleanupSettings.State> {

    public static final class State {
        /**
         * Hours to retain agent-created scratch files. 0 = keep forever.
         */
        public int scratchRetentionHours = 24; // NOSONAR - IntelliJ XmlSerializer persists public state fields directly.

        /**
         * If true, close agent-created tool window tabs when a new turn starts.
         */
        public boolean autoCloseAgentTabs = true; // NOSONAR - IntelliJ XmlSerializer persists public state fields directly.

        /**
         * If true, also close terminal tabs that may still be running.
         * Only applies when {@link #autoCloseAgentTabs} is true.
         */
        public boolean autoCloseRunningTerminals = false; // NOSONAR - IntelliJ XmlSerializer persists public state fields directly.
    }

    private State state = new State();

    public static @NotNull CleanupSettings getInstance(@NotNull Project project) {
        return project.getService(CleanupSettings.class);
    }

    @Override
    public @NotNull State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State loaded) {
        this.state = loaded;
    }

    public int getScratchRetentionHours() {
        return state.scratchRetentionHours;
    }

    public void setScratchRetentionHours(int hours) {
        state.scratchRetentionHours = Math.max(0, hours);
    }

    public boolean isAutoCloseAgentTabs() {
        return state.autoCloseAgentTabs;
    }

    public void setAutoCloseAgentTabs(boolean value) {
        state.autoCloseAgentTabs = value;
    }

    public boolean isAutoCloseRunningTerminals() {
        return state.autoCloseRunningTerminals;
    }

    public void setAutoCloseRunningTerminals(boolean value) {
        state.autoCloseRunningTerminals = value;
    }
}
