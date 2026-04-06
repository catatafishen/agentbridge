package com.github.catatafishen.agentbridge.services;

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
import java.util.Objects;

/**
 * Persists the list of user-recorded macros that are registered as MCP tools.
 * Stored per-project in {@code macroTools.xml}.
 */
@Service(Service.Level.PROJECT)
@State(name = "MacroToolSettings", storages = @Storage("macroTools.xml"))
public final class MacroToolSettings implements PersistentStateComponent<MacroToolSettings.State> {

    private State myState = new State();

    @SuppressWarnings("java:S1905") // Cast needed: IDE doesn't resolve Project→ComponentManager supertype
    public static MacroToolSettings getInstance(@NotNull Project project) {
        return ((ComponentManager) project).getService(MacroToolSettings.class);
    }

    @Override
    public @Nullable State getState() {
        return myState;
    }

    @Override
    public void loadState(@NotNull State state) {
        myState = state;
    }

    public List<MacroRegistration> getRegistrations() {
        return myState.registrations;
    }

    public void setRegistrations(List<MacroRegistration> registrations) {
        myState.registrations = new ArrayList<>(registrations);
    }

    /**
     * Represents a single macro registered as an MCP tool.
     */
    public static final class MacroRegistration {
        /**
         * Original name of the ActionMacro in IntelliJ.
         */
        private String macroName = "";
        /**
         * Custom MCP tool name (e.g., "clean_and_analyze").
         */
        private String toolName = "";
        /**
         * Custom description shown to agents.
         */
        private String description = "";
        /**
         * Whether this macro tool is currently active.
         */
        private boolean enabled = true;

        public MacroRegistration() {
        }

        public MacroRegistration(String macroName, String toolName, String description, boolean enabled) {
            this.macroName = macroName;
            this.toolName = toolName;
            this.description = description;
            this.enabled = enabled;
        }

        public String getMacroName() {
            return macroName;
        }

        public void setMacroName(String macroName) {
            this.macroName = macroName;
        }

        public String getToolName() {
            return toolName;
        }

        public void setToolName(String toolName) {
            this.toolName = toolName;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * Creates a copy of this registration.
         */
        public MacroRegistration copy() {
            return new MacroRegistration(macroName, toolName, description, enabled);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof MacroRegistration that)) return false;
            return enabled == that.enabled
                && Objects.equals(macroName, that.macroName)
                && Objects.equals(toolName, that.toolName)
                && Objects.equals(description, that.description);
        }

        @Override
        public int hashCode() {
            return Objects.hash(macroName, toolName, description, enabled);
        }
    }

    public static final class State {
        private List<MacroRegistration> registrations = new ArrayList<>();

        public List<MacroRegistration> getRegistrations() {
            return registrations;
        }

        public void setRegistrations(List<MacroRegistration> registrations) {
            this.registrations = registrations;
        }
    }
}
