package com.github.catatafishen.ideagentforcopilot.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Service(Service.Level.APP)
@State(name = "BillingSettings", storages = @Storage("ideAgentBilling.xml"))
public final class BillingSettings implements PersistentStateComponent<BillingSettings.State> {

    private State myState = new State();

    public static BillingSettings getInstance() {
        return ApplicationManager.getApplication().getService(BillingSettings.class);
    }

    public boolean isShowCopilotUsage() {
        return myState.showCopilotUsage;
    }

    public void setShowCopilotUsage(boolean show) {
        myState.showCopilotUsage = show;
    }

    @Nullable
    public String getGhBinaryPath() {
        return myState.ghBinaryPath == null || myState.ghBinaryPath.isBlank() ? null : myState.ghBinaryPath;
    }

    public void setGhBinaryPath(@Nullable String path) {
        myState.ghBinaryPath = path != null ? path.trim() : null;
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
        private boolean showCopilotUsage = true;
        private String ghBinaryPath = null;

        public boolean isShowCopilotUsage() {
            return showCopilotUsage;
        }

        public void setShowCopilotUsage(boolean showCopilotUsage) {
            this.showCopilotUsage = showCopilotUsage;
        }

        public String getGhBinaryPath() {
            return ghBinaryPath;
        }

        public void setGhBinaryPath(String p) {
            this.ghBinaryPath = p;
        }
    }
}
