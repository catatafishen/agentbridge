package com.github.catatafishen.agentbridge.psi.graph;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Persistent project-level settings for the Knowledge Graph feature.
 * Knowledge Graph is <b>opt-in</b> — disabled by default. The {@code query_knowledge_graph}
 * MCP tool is always registered (via {@code GraphToolFactory}) so it appears in settings,
 * but is only advertised to agents when enabled (via {@code McpToolFilter}) and the graph
 * contains data.
 */
@Service(Service.Level.PROJECT)
@State(name = "CodeGraphSettings", storages = {@Storage("agentbridgeCodeGraph.xml")})
public final class CodeGraphSettings implements PersistentStateComponent<CodeGraphSettings.State> {

    private State myState = new State();

    public static CodeGraphSettings getInstance(@NotNull Project project) {
        return PlatformApiCompat.getService(project, CodeGraphSettings.class);
    }

    public boolean isEnabled() {
        return myState.isEnabled();
    }

    public void setEnabled(boolean enabled) {
        myState.setEnabled(enabled);
    }

    public boolean isAutoRefreshOnAgentEdit() {
        return myState.isAutoRefreshOnAgentEdit();
    }

    public void setAutoRefreshOnAgentEdit(boolean v) {
        myState.setAutoRefreshOnAgentEdit(v);
    }

    public long getLastFullIndexAt() {
        return myState.getLastFullIndexAt();
    }

    public void setLastFullIndexAt(long v) {
        myState.setLastFullIndexAt(v);
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
        private boolean enabled = false;
        private boolean autoRefreshOnAgentEdit = true;
        private long lastFullIndexAt = 0L;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isAutoRefreshOnAgentEdit() {
            return autoRefreshOnAgentEdit;
        }

        public void setAutoRefreshOnAgentEdit(boolean v) {
            this.autoRefreshOnAgentEdit = v;
        }

        public long getLastFullIndexAt() {
            return lastFullIndexAt;
        }

        public void setLastFullIndexAt(long v) {
            this.lastFullIndexAt = v;
        }
    }
}
