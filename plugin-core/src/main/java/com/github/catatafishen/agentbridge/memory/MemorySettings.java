package com.github.catatafishen.agentbridge.memory;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Persistent project-level settings for the Semantic Memory feature.
 * Memory is <b>opt-in</b> — disabled by default.
 */
@Service(Service.Level.PROJECT)
@State(name = "MemorySettings", storages = {@Storage("agentbridgeMemory.xml")})
public final class MemorySettings implements PersistentStateComponent<MemorySettings.State> {

    private State myState = new State();

    public static MemorySettings getInstance(@NotNull Project project) {
        return PlatformApiCompat.getService(project, MemorySettings.class);
    }

    // ── Accessors ─────────────────────────────────────────────────────────

    public boolean isEnabled() {
        return myState.enabled;
    }

    public void setEnabled(boolean enabled) {
        myState.enabled = enabled;
    }

    public boolean isAutoMineOnTurnComplete() {
        return myState.autoMineOnTurnComplete;
    }

    public void setAutoMineOnTurnComplete(boolean autoMine) {
        myState.autoMineOnTurnComplete = autoMine;
    }

    public boolean isAutoMineOnSessionArchive() {
        return myState.autoMineOnSessionArchive;
    }

    public void setAutoMineOnSessionArchive(boolean autoMine) {
        myState.autoMineOnSessionArchive = autoMine;
    }

    public int getMinChunkLength() {
        return myState.minChunkLength;
    }

    public void setMinChunkLength(int minChunkLength) {
        myState.minChunkLength = minChunkLength;
    }

    public int getMaxDrawersPerTurn() {
        return myState.maxDrawersPerTurn;
    }

    public void setMaxDrawersPerTurn(int maxDrawersPerTurn) {
        myState.maxDrawersPerTurn = maxDrawersPerTurn;
    }

    public @NotNull String getPalaceWing() {
        return myState.palaceWing;
    }

    public void setPalaceWing(@NotNull String palaceWing) {
        myState.palaceWing = palaceWing;
    }

    // ── PersistentStateComponent ──────────────────────────────────────────

    @Override
    public @NotNull State getState() {
        return myState;
    }

    @Override
    public void loadState(@NotNull State state) {
        myState = state;
    }

    // ── State POJO ────────────────────────────────────────────────────────

    public static class State {
        private boolean enabled = false;
        private boolean autoMineOnTurnComplete = true;
        private boolean autoMineOnSessionArchive = true;
        private int minChunkLength = 200;
        private int maxDrawersPerTurn = 10;
        private String palaceWing = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isAutoMineOnTurnComplete() {
            return autoMineOnTurnComplete;
        }

        public void setAutoMineOnTurnComplete(boolean v) {
            this.autoMineOnTurnComplete = v;
        }

        public boolean isAutoMineOnSessionArchive() {
            return autoMineOnSessionArchive;
        }

        public void setAutoMineOnSessionArchive(boolean v) {
            this.autoMineOnSessionArchive = v;
        }

        public int getMinChunkLength() {
            return minChunkLength;
        }

        public void setMinChunkLength(int v) {
            this.minChunkLength = v;
        }

        public int getMaxDrawersPerTurn() {
            return maxDrawersPerTurn;
        }

        public void setMaxDrawersPerTurn(int v) {
            this.maxDrawersPerTurn = v;
        }

        public String getPalaceWing() {
            return palaceWing;
        }

        public void setPalaceWing(String v) {
            this.palaceWing = v;
        }
    }
}
