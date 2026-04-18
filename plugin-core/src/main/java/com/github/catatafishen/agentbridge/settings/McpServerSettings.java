package com.github.catatafishen.agentbridge.settings;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Persistent project-level settings for MCP server and tool registration.
 */
@Service(Service.Level.PROJECT)
@State(name = "McpServerSettings", storages = @Storage("mcpServer.xml"))
public final class McpServerSettings implements PersistentStateComponent<McpServerSettings.State> {

    public static final int DEFAULT_PORT = 8642;

    private State myState = new State();

    public static McpServerSettings getInstance(@NotNull Project project) {
        return PlatformApiCompat.getService(project, McpServerSettings.class);
    }

    public int getPort() {
        return myState.port;
    }

    public void setPort(int port) {
        myState.port = port;
    }

    public boolean isAutoStart() {
        return myState.autoStart;
    }

    public void setAutoStart(boolean autoStart) {
        myState.autoStart = autoStart;
    }

    public boolean isDebugLoggingEnabled() {
        return myState.debugLoggingEnabled;
    }

    public void setDebugLoggingEnabled(boolean enabled) {
        myState.debugLoggingEnabled = enabled;
    }

    public Set<String> getDisabledToolIds() {
        return myState.disabledToolIds;
    }

    public void setDisabledToolIds(Set<String> ids) {
        myState.disabledToolIds = new LinkedHashSet<>(ids);
    }

    public boolean isToolEnabled(String toolId) {
        return !myState.disabledToolIds.contains(toolId);
    }

    public void setToolEnabled(String toolId, boolean enabled) {
        if (enabled) {
            myState.disabledToolIds.remove(toolId);
        } else {
            myState.disabledToolIds.add(toolId);
        }
    }

    public TransportMode getTransportMode() {
        return myState.transportMode;
    }

    public void setTransportMode(TransportMode mode) {
        myState.transportMode = mode;
    }

    public @org.jetbrains.annotations.Nullable String getKindReadColorKey() {
        return myState.getKindReadColorKey();
    }

    public void setKindReadColorKey(@org.jetbrains.annotations.Nullable String key) {
        myState.setKindReadColorKey(key);
    }

    public @org.jetbrains.annotations.Nullable String getKindEditColorKey() {
        return myState.getKindEditColorKey();
    }

    public void setKindEditColorKey(@org.jetbrains.annotations.Nullable String key) {
        myState.setKindEditColorKey(key);
    }

    public @org.jetbrains.annotations.Nullable String getKindExecuteColorKey() {
        return myState.getKindExecuteColorKey();
    }

    public void setKindExecuteColorKey(@org.jetbrains.annotations.Nullable String key) {
        myState.setKindExecuteColorKey(key);
    }

    public @org.jetbrains.annotations.Nullable String getKindSearchColorKey() {
        return myState.getKindSearchColorKey();
    }

    public void setKindSearchColorKey(@org.jetbrains.annotations.Nullable String key) {
        myState.setKindSearchColorKey(key);
    }

    public boolean isSmoothScrollEnabled() {
        return myState.smoothScrollEnabled;
    }

    public void setSmoothScrollEnabled(boolean enabled) {
        myState.smoothScrollEnabled = enabled;
    }

    public boolean isShowTurnStats() {
        return myState.showTurnStats;
    }

    public void setShowTurnStats(boolean show) {
        myState.showTurnStats = show;
    }

    /**
     * When true, agent edits are auto-approved as soon as they land. Disabling makes new
     * rows appear as PENDING — they accumulate in the Review panel and the user must
     * accept (or revert) them. Toggling this on also sweeps any existing PENDING rows
     * to APPROVED. The Diff Review session itself is always on; this flag only controls
     * the default approval state of new rows.
     *
     * <p>See {@link com.github.catatafishen.agentbridge.psi.review.AgentEditSession}.
     */
    public boolean isAutoApproveAgentEdits() {
        return myState.autoApproveAgentEdits;
    }

    public void setAutoApproveAgentEdits(boolean enabled) {
        myState.autoApproveAgentEdits = enabled;
    }

    /**
     * When true, the Review panel automatically removes all approved rows when the user
     * starts a fresh prompt (not nudges or follow-ups within the same turn). The list
     * still grows during a single agent turn so the user can audit a full batch of edits
     * after it completes.
     */
    public boolean isAutoCleanReviewOnNewPrompt() {
        return myState.autoCleanReviewOnNewPrompt;
    }

    public void setAutoCleanReviewOnNewPrompt(boolean enabled) {
        myState.autoCleanReviewOnNewPrompt = enabled;
    }

    /**
     * @deprecated The Diff Review session is now always-on. The on/off toggle has been
     * replaced by the Auto-Approve toggle ({@link #isAutoApproveAgentEdits()}). This
     * accessor is kept only to read legacy persisted state during migration.
     */
    @Deprecated(forRemoval = true)
    public boolean isReviewAgentEdits() {
        return myState.reviewAgentEdits;
    }

    @Deprecated(forRemoval = true)
    public void setReviewAgentEdits(boolean enabled) {
        myState.reviewAgentEdits = enabled;
    }

    /**
     * Applies {@link McpToolFilter#DEFAULT_DISABLED} on first run (before any
     * persisted state exists). Once applied, the flag is persisted so subsequent
     * loads skip this step.
     */
    public void ensureDefaultsApplied() {
        if (!myState.defaultsApplied) {
            myState.disabledToolIds.addAll(McpToolFilter.DEFAULT_DISABLED);
            myState.defaultsApplied = true;
        }
    }

    @Override
    public @NotNull State getState() {
        return myState;
    }

    @Override
    public void loadState(@NotNull State state) {
        myState = state;
    }

    public static class State {
        private int port = DEFAULT_PORT;
        private boolean autoStart = false;
        private boolean debugLoggingEnabled = false;
        private TransportMode transportMode = TransportMode.STREAMABLE_HTTP;
        private Set<String> disabledToolIds = new LinkedHashSet<>();
        private boolean defaultsApplied = false;
        private boolean smoothScrollEnabled = false;
        private boolean showTurnStats = true;
        private boolean reviewAgentEdits = false;
        private boolean autoApproveAgentEdits = false;
        private boolean autoCleanReviewOnNewPrompt = false;
        private String kindReadColorKey = null;
        private String kindEditColorKey = null;
        private String kindExecuteColorKey = null;
        private String kindSearchColorKey = null;

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public boolean isAutoStart() {
            return autoStart;
        }

        public void setAutoStart(boolean autoStart) {
            this.autoStart = autoStart;
        }

        public boolean isDebugLoggingEnabled() {
            return debugLoggingEnabled;
        }

        public void setDebugLoggingEnabled(boolean debugLoggingEnabled) {
            this.debugLoggingEnabled = debugLoggingEnabled;
        }

        public TransportMode getTransportMode() {
            return transportMode;
        }

        public void setTransportMode(TransportMode transportMode) {
            this.transportMode = transportMode;
        }

        public Set<String> getDisabledToolIds() {
            return disabledToolIds;
        }

        public void setDisabledToolIds(Set<String> disabledToolIds) {
            this.disabledToolIds = disabledToolIds;
        }

        public boolean isDefaultsApplied() {
            return defaultsApplied;
        }

        public void setDefaultsApplied(boolean defaultsApplied) {
            this.defaultsApplied = defaultsApplied;
        }

        public boolean isSmoothScrollEnabled() {
            return smoothScrollEnabled;
        }

        public void setSmoothScrollEnabled(boolean smoothScrollEnabled) {
            this.smoothScrollEnabled = smoothScrollEnabled;
        }

        public boolean isShowTurnStats() {
            return showTurnStats;
        }

        public void setShowTurnStats(boolean showTurnStats) {
            this.showTurnStats = showTurnStats;
        }

        public boolean isReviewAgentEdits() {
            return reviewAgentEdits;
        }

        public void setReviewAgentEdits(boolean reviewAgentEdits) {
            this.reviewAgentEdits = reviewAgentEdits;
        }

        public boolean isAutoApproveAgentEdits() {
            return autoApproveAgentEdits;
        }

        public void setAutoApproveAgentEdits(boolean autoApproveAgentEdits) {
            this.autoApproveAgentEdits = autoApproveAgentEdits;
        }

        public boolean isAutoCleanReviewOnNewPrompt() {
            return autoCleanReviewOnNewPrompt;
        }

        public void setAutoCleanReviewOnNewPrompt(boolean autoCleanReviewOnNewPrompt) {
            this.autoCleanReviewOnNewPrompt = autoCleanReviewOnNewPrompt;
        }

        public @org.jetbrains.annotations.Nullable String getKindReadColorKey() {
            return kindReadColorKey;
        }

        public void setKindReadColorKey(@org.jetbrains.annotations.Nullable String kindReadColorKey) {
            this.kindReadColorKey = kindReadColorKey;
        }

        public @org.jetbrains.annotations.Nullable String getKindEditColorKey() {
            return kindEditColorKey;
        }

        public void setKindEditColorKey(@org.jetbrains.annotations.Nullable String kindEditColorKey) {
            this.kindEditColorKey = kindEditColorKey;
        }

        public @org.jetbrains.annotations.Nullable String getKindExecuteColorKey() {
            return kindExecuteColorKey;
        }

        public void setKindExecuteColorKey(@org.jetbrains.annotations.Nullable String kindExecuteColorKey) {
            this.kindExecuteColorKey = kindExecuteColorKey;
        }

        public @org.jetbrains.annotations.Nullable String getKindSearchColorKey() {
            return kindSearchColorKey;
        }

        public void setKindSearchColorKey(@org.jetbrains.annotations.Nullable String kindSearchColorKey) {
            this.kindSearchColorKey = kindSearchColorKey;
        }
    }
}
