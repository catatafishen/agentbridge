package com.github.catatafishen.agentbridge.sandbox;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.SystemInfo;
import org.jetbrains.annotations.NotNull;

/**
 * Application-level per-agent sandbox toggle.
 *
 * <p>Settings are persisted via {@link PropertiesComponent} at the application level
 * (not per-project) because sandboxing is an OS-level, host-wide concern. Each agent
 * has its own toggle so users can sandbox individual agents (e.g. only Copilot, which
 * is the only agent currently tested with bwrap) without affecting the others.</p>
 *
 * <p>Per-agent key format: {@code agentbridge.sandbox.<agentId>.enabled}.</p>
 *
 * <p>A legacy global key {@code agentbridge.sandbox.enabled} is honoured as a fallback
 * so existing installs that enabled sandboxing under the old global "Security" page
 * keep their setting until the user opens the per-agent settings page and explicitly
 * toggles the per-agent value.</p>
 */
public final class SandboxSettings {

    /**
     * Legacy global key kept only for the migration fallback in {@link #isSandboxEnabled(String)}.
     */
    private static final String LEGACY_KEY_SANDBOX_ENABLED = "agentbridge.sandbox.enabled";

    private static final String PER_AGENT_KEY_PREFIX = "agentbridge.sandbox.";
    private static final String PER_AGENT_KEY_SUFFIX = ".enabled";

    private SandboxSettings() {
    }

    @NotNull
    private static String perAgentKey(@NotNull String agentId) {
        return PER_AGENT_KEY_PREFIX + agentId + PER_AGENT_KEY_SUFFIX;
    }

    /**
     * Returns whether the sandbox is enabled for the given agent.
     *
     * <p>If no per-agent value has been stored we fall back to the legacy global key
     * so existing installs that enabled sandboxing under the old global "Security" page
     * keep their setting until the user explicitly toggles the per-agent value.</p>
     */
    public static boolean isSandboxEnabled(@NotNull String agentId) {
        Application app = ApplicationManager.getApplication();
        if (app == null) return false;
        PropertiesComponent props = PropertiesComponent.getInstance();
        String key = perAgentKey(agentId);
        if (props.isValueSet(key)) {
            return props.getBoolean(key, false);
        }
        return props.getBoolean(LEGACY_KEY_SANDBOX_ENABLED, false);
    }

    public static void setSandboxEnabled(@NotNull String agentId, boolean enabled) {
        Application app = ApplicationManager.getApplication();
        if (app == null) return;
        // Persist even the "false" value so we stop falling back to the legacy global key.
        PropertiesComponent.getInstance().setValue(perAgentKey(agentId), enabled, false);
    }

    /**
     * Returns true if sandbox should be applied to a given agent launch: the feature must
     * be enabled for this agent and bwrap must be available on the current system.
     */
    public static boolean shouldSandbox(@NotNull String agentId) {
        return isSandboxEnabled(agentId) && BwrapSandbox.isAvailable();
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
