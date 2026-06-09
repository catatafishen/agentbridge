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
 * MCP tool is only registered when {@link #isEnabled()} is {@code true} <em>and</em>
 * the graph contains at least one node ({@link com.github.catatafishen.agentbridge.psi.graph.CodeGraphStore.GraphStats}).
 */
@Service(Service.Level.PROJECT)
@State(name = "CodeGraphSettings", storages = {@Storage("agentbridgeCodeGraph.xml")})
public final class CodeGraphSettings implements PersistentStateComponent<CodeGraphSettings.State> {

    private State myState = new State();

    public static CodeGraphSettings getInstance(@NotNull Project project) {
        return PlatformApiCompat.getService(project, CodeGraphSettings.class);
    }

    public boolean isEnabled() {
        return myState.enabled;
    }

    public void setEnabled(boolean enabled) {
        myState.enabled = enabled;
    }

    public boolean isAutoRefreshOnAgentEdit() {
        return myState.autoRefreshOnAgentEdit;
    }

    public void setAutoRefreshOnAgentEdit(boolean v) {
        myState.autoRefreshOnAgentEdit = v;
    }

    public long getLastFullIndexAt() {
        return myState.lastFullIndexAt;
    }

    public void setLastFullIndexAt(long v) {
        myState.lastFullIndexAt = v;
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
        public boolean enabled = false;
        public boolean autoRefreshOnAgentEdit = true;
        public long lastFullIndexAt = 0L;
    }
}
