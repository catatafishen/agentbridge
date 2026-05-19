package com.github.catatafishen.agentbridge.bridge;

import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry that holds the active {@link PermissionPromptProvider} per project.
 * The UI layer registers itself here during initialization; backend services look it up.
 */
public final class PermissionPromptProviderHolder {

    private static final ConcurrentHashMap<Project, PermissionPromptProvider> INSTANCES =
        new ConcurrentHashMap<>();

    private PermissionPromptProviderHolder() {
    }

    @Nullable
    public static PermissionPromptProvider getInstance(@NotNull Project project) {
        return INSTANCES.get(project);
    }

    public static void register(@NotNull Project project, @NotNull PermissionPromptProvider provider) {
        INSTANCES.put(project, provider);
    }

    public static void unregister(@NotNull Project project) {
        INSTANCES.remove(project);
    }
}
