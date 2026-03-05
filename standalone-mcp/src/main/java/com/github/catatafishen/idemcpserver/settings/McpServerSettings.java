package com.github.catatafishen.idemcpserver.settings;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Persistent project-level settings for the MCP server.
 */
@Service(Service.Level.PROJECT)
@State(name = "McpServerSettings", storages = @Storage("mcpServer.xml"))
public final class McpServerSettings implements PersistentStateComponent<McpServerSettings.State> {

    public static final int DEFAULT_PORT = 8642;

    private State myState = new State();

    public static McpServerSettings getInstance(@NotNull Project project) {
        return project.getService(McpServerSettings.class);
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

    @Override
    public @NotNull State getState() {
        return myState;
    }

    @Override
    public void loadState(@NotNull State state) {
        myState = state;
    }

    public static class State {
        public int port = DEFAULT_PORT;
        public boolean autoStart = false;
        public Set<String> disabledToolIds = new LinkedHashSet<>();
    }
}
