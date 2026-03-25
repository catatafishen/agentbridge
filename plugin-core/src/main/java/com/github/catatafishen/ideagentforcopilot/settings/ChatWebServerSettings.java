package com.github.catatafishen.ideagentforcopilot.settings;

import com.github.catatafishen.ideagentforcopilot.psi.PlatformApiCompat;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Persistent project-level settings for the Chat Web Server feature.
 */
@Service(Service.Level.PROJECT)
@State(name = "ChatWebServerSettings", storages = @Storage("chatWebServer.xml"))
public final class ChatWebServerSettings implements PersistentStateComponent<ChatWebServerSettings.State> {

    public static final int DEFAULT_PORT = 9642;

    private State myState = new State();

    public static ChatWebServerSettings getInstance(@NotNull Project project) {
        return PlatformApiCompat.getService(project, ChatWebServerSettings.class);
    }

    public int getPort() {
        return myState.port;
    }

    public void setPort(int port) {
        myState.port = port;
    }

    public boolean isEnabled() {
        return myState.enabled;
    }

    public void setEnabled(boolean enabled) {
        myState.enabled = enabled;
    }

    public boolean isHttpsEnabled() {
        return myState.httpsEnabled;
    }

    public void setHttpsEnabled(boolean httpsEnabled) {
        myState.httpsEnabled = httpsEnabled;
    }

    @Override
    public @NotNull State getState() {
        return myState;
    }

    @Override
    public void loadState(@NotNull State state) {
        myState = state;
    }

    // ── VAPID key accessors ───────────────────────────────────────────────────

    public @org.jetbrains.annotations.Nullable String getVapidPrivateKey() {
        return myState.vapidPrivateKey;
    }

    public void setVapidPrivateKey(@org.jetbrains.annotations.Nullable String key) {
        myState.vapidPrivateKey = key;
    }

    public @org.jetbrains.annotations.Nullable String getVapidPublicKey() {
        return myState.vapidPublicKey;
    }

    public void setVapidPublicKey(@org.jetbrains.annotations.Nullable String key) {
        myState.vapidPublicKey = key;
    }

    public static class State {
        private int port = DEFAULT_PORT;
        private boolean enabled = false;
        private boolean httpsEnabled = true;
        /**
         * VAPID private key — base64url-encoded raw 32-byte P-256 scalar.
         */
        public String vapidPrivateKey = "";
        /**
         * VAPID public key — base64url-encoded uncompressed 65-byte P-256 point.
         */
        public String vapidPublicKey = "";

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isHttpsEnabled() {
            return httpsEnabled;
        }

        public void setHttpsEnabled(boolean httpsEnabled) {
            this.httpsEnabled = httpsEnabled;
        }
    }
}
