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
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Persists the list of custom MCP server configurations per project.
 * Stored in {@code .idea/customMcp.xml}.
 */
@Service(Service.Level.PROJECT)
@State(name = "CustomMcpSettings", storages = @Storage("customMcp.xml"))
public final class CustomMcpSettings implements PersistentStateComponent<CustomMcpSettings.State> {

    private State myState = new State();
    private final List<SettingsListener> listeners = new CopyOnWriteArrayList<>();

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
        notifyListeners();
    }

    @NotNull
    public List<CustomMcpServerConfig> getServers() {
        return List.copyOf(myState.servers);
    }

    public void setServers(@NotNull List<CustomMcpServerConfig> servers) {
        myState.servers = new ArrayList<>(servers);
        notifyListeners();
    }

    /**
     * Resets the transient current active state from the persisted default state.
     * Called at session/project startup so manual toggles in the side panel do not
     * become the new startup default.
     */
    public void resetCurrentStatesToDefault() {
        for (CustomMcpServerConfig server : myState.servers) {
            server.setEnabled(server.isDefaultEnabled());
        }
        notifyListeners();
    }

    public boolean applyCurrentState(@NotNull String serverId, boolean enabled) {
        for (CustomMcpServerConfig server : myState.servers) {
            if (serverId.equals(server.getId())) {
                server.setEnabled(enabled);
                notifyListeners();
                return true;
            }
        }
        return false;
    }

    public void addListener(@NotNull SettingsListener listener) {
        listeners.add(listener);
    }

    public void removeListener(@NotNull SettingsListener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners() {
        for (SettingsListener listener : listeners) {
            listener.settingsChanged();
        }
    }

    public interface SettingsListener {
        void settingsChanged();
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
