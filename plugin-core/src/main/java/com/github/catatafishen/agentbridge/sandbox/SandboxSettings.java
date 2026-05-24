package com.github.catatafishen.agentbridge.sandbox;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.SystemInfo;
import org.jetbrains.annotations.NotNull;

/**
 * Application-level settings for the bwrap sandbox feature.
 *
 * <p>Settings are persisted via {@link PropertiesComponent} at the application level
 * (not per-project) because sandboxing is an OS-level, host-wide concern.</p>
 */
public final class SandboxSettings {

    private static final String KEY_SANDBOX_ENABLED = "agentbridge.sandbox.enabled";

    private SandboxSettings() {
    }

    public static boolean isSandboxEnabled() {
        Application app = ApplicationManager.getApplication();
        if (app == null) return false;
        return PropertiesComponent.getInstance().getBoolean(KEY_SANDBOX_ENABLED, false);
    }

    public static void setSandboxEnabled(boolean enabled) {
        Application app = ApplicationManager.getApplication();
        if (app == null) return;
        PropertiesComponent.getInstance().setValue(KEY_SANDBOX_ENABLED, enabled, false);
    }

    /**
     * Returns true if sandbox should be applied to a given agent launch: the feature must be
     * enabled and bwrap must be available on the current system.
     */
    public static boolean shouldSandbox() {
        return isSandboxEnabled() && BwrapSandbox.isAvailable();
    }

    /**
     * Returns a human-readable status string for display in the settings UI.
     */
    @NotNull
    public static String getBwrapStatus() {
        if (!SystemInfo.isLinux) {
            return "Not available (Linux only)";
        }
        return BwrapSandbox.isAvailable() ? "bwrap found — ready" : "bwrap not found — install bubblewrap";
    }
}
