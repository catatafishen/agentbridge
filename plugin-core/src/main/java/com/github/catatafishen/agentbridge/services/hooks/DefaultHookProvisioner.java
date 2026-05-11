package com.github.catatafishen.agentbridge.services.hooks;

import com.github.catatafishen.agentbridge.settings.AgentBridgeStorageSettings;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Provisions the default hook directory structure on plugin startup.
 *
 * <p>Built-in hooks (abuse prevention, terminal nudges, stale naming check) are now
 * implemented in Java ({@link BuiltInPermissionHooks}, {@link BuiltInSuccessHooks}),
 * so no shell scripts or JSON configs need to be distributed. This class just ensures
 * the hooks/scripts directory exists so users can add their own custom hook scripts.
 *
 * <p>Called from {@link HookRegistry} on first access after each IDE startup.
 */
public final class DefaultHookProvisioner {

    private static final Logger LOG = Logger.getInstance(DefaultHookProvisioner.class);

    private DefaultHookProvisioner() {
    }

    /**
     * Ensures the hooks directory structure exists for user-defined scripts.
     */
    public static void provisionDefaults(@NotNull Project project) {
        Path hooksDir = resolveHooksDir(project);
        try {
            Files.createDirectories(hooksDir.resolve("scripts"));
        } catch (IOException e) {
            LOG.warn("Failed to create hooks directory: " + hooksDir, e);
        }
    }

    /**
     * Restores default hooks. Built-in hooks are now implemented in Java and require no
     * restoration. This method exists for API compatibility and triggers a registry reload.
     *
     * @return always {@code true}
     */
    public static boolean restoreDefaults(@NotNull Project project) {
        provisionDefaults(project);
        return true;
    }

    @NotNull
    private static Path resolveHooksDir(@NotNull Project project) {
        return AgentBridgeStorageSettings.getInstance()
            .getProjectStorageDir(project)
            .resolve("hooks");
    }
}
