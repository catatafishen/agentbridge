package com.github.catatafishen.agentbridge.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Persists customizable startup instructions for all agents.
 * Allows users to modify the default template via Settings UI.
 */
@Service
@State(name = "StartupInstructionsSettings", storages = @Storage("agentbridge-instructions.xml"))
public final class StartupInstructionsSettings implements PersistentStateComponent<StartupInstructionsSettings.State> {

    @NotNull
    public static StartupInstructionsSettings getInstance() {
        return ApplicationManager.getApplication().getService(StartupInstructionsSettings.class);
    }

    private State state = new State();

    public static final class State {
        private String customInstructions = null; // null means use default template

        public String getCustomInstructions() {
            return customInstructions;
        }

        public void setCustomInstructions(String customInstructions) {
            this.customInstructions = customInstructions;
        }
    }

    @Override
    @Nullable
    public State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
    }

    /**
     * Gets the effective startup instructions - either custom or default template.
     */
    @NotNull
    public String getInstructions() {
        if (state.getCustomInstructions() != null && !state.getCustomInstructions().trim().isEmpty()) {
            return state.getCustomInstructions();
        }
        return getDefaultTemplate();
    }

    /**
     * Gets the custom instructions if set, or null if using default.
     */
    @Nullable
    public String getCustomInstructions() {
        return state.getCustomInstructions();
    }

    /**
     * Sets custom instructions. Use null or empty string to revert to default.
     */
    public void setCustomInstructions(@Nullable String customInstructions) {
        if (customInstructions != null && customInstructions.trim().isEmpty()) {
            customInstructions = null;
        }
        state.setCustomInstructions(customInstructions);
    }

    /**
     * Returns true if custom instructions are being used (not default template).
     */
    public boolean isUsingCustomInstructions() {
        return state.getCustomInstructions() != null && !state.getCustomInstructions().trim().isEmpty();
    }

    /**
     * Loads the default template from resources.
     */
    @NotNull
    public String getDefaultTemplate() {
        try (InputStream is = getClass().getResourceAsStream("/default-startup-instructions.md")) {
            if (is != null) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            // Fallback if resource not found
        }
        return "You are running inside an IntelliJ IDEA plugin with IDE tools accessible via MCP.";
    }
}
