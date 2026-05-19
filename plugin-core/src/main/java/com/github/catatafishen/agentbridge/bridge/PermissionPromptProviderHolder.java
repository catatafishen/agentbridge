package com.github.catatafishen.agentbridge.bridge;

import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for the active {@link PermissionPromptProvider} per project.
 * <p>
 * The UI layer registers itself on creation and unregisters on dispose.
 * Backend services look up the provider via {@link #get(Project)}.
 */
public final class PermissionPromptProviderHolder {

    private static final ConcurrentHashMap<Project, PermissionPromptProvider> INSTANCES =
        new ConcurrentHashMap<>();

    private PermissionPromptProviderHolder() {}

    public static void register(@NotNull Project project, @NotNull PermissionPromptProvider provider) {
        INSTANCES.put(project, provider);
    }

    public static void unregister(@NotNull Project project) {
        INSTANCES.remove(project);
    }

    @Nullable
    public static PermissionPromptProvider get(@NotNull Project project) {
        return INSTANCES.get(project);
    }
}
