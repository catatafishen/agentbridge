package com.github.catatafishen.agentbridge.custommcp;

import com.intellij.openapi.components.ComponentManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Persists the list of custom MCP server configurations per project.
 * Stored in {@code .idea/customMcp.xml}.
 */
@Service(Service.Level.PROJECT)
@State(name = "CustomMcpSettings", storages = @Storage("customMcp.xml"))
public final class CustomMcpSettings implements PersistentStateComponent<CustomMcpSettings.State> {

    private State myState = new State();

    @SuppressWarnings("java:S1905") // Cast needed: IDE doesn't resolve Project→ComponentManager supertype
    public static CustomMcpSettings getInstance(@NotNull Project project) {
        return ((ComponentManager) project).getService(CustomMcpSettings.class);
    }

    @Override
    public @Nullable State getState() {
        return myState;
    }

    @Override
    public void loadState(@NotNull State state) {
        myState = state;
    }

    @NotNull
    public List<CustomMcpServerConfig> getServers() {
        return List.copyOf(myState.servers);
    }

    public void setServers(@NotNull List<CustomMcpServerConfig> servers) {
        myState.servers = new ArrayList<>(servers);
    }

    /** Serialized state container. */
    public static final class State {
        private List<CustomMcpServerConfig> servers = new ArrayList<>();

        public List<CustomMcpServerConfig> getServers() {
            return servers;
        }

        public void setServers(List<CustomMcpServerConfig> servers) {
            this.servers = servers;
        }
    }
}
