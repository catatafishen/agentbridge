package com.github.catatafishen.agentbridge.psi;

import com.github.catatafishen.agentbridge.services.ToolPermission;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Default {@link ToolLayerSettings} for standalone MCP clients (no agent plugin active).
 * Reads the shared "follow agent files" setting from {@link PropertiesComponent};
 * returns permissive defaults for permissions and {@code null} for agent metadata.
 *
 * <p>Registered as a project service in standalone-mcp's {@code plugin.xml}.
 * Also used as a static fallback when no service is registered.</p>
 */
public final class DefaultToolLayerSettings implements ToolLayerSettings {

    @Nullable
    private final Project project;

    public DefaultToolLayerSettings(@Nullable Project project) {
        this.project = project;
    }

    @Override
    public boolean getFollowAgentFiles() {
        if (project == null) return true;
        return PropertiesComponent.getInstance(project)
            .getBoolean(FOLLOW_AGENT_FILES_KEY, true);
    }

    @Override
    public @Nullable String getActiveAgentLabel() {
        return null;
    }

    @Override
    public @Nullable String getSelectedModel() {
        return null;
    }

    @Override
    public @NotNull ToolPermission resolveEffectivePermission(@NotNull String toolId, boolean insideProject) {
        return ToolPermission.ALLOW;
    }

    @Override
    public @NotNull ToolPermission getToolPermission(@NotNull String toolId) {
        return ToolPermission.ALLOW;
    }
}
