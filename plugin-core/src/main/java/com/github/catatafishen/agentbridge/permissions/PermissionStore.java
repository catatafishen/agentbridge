package com.github.catatafishen.agentbridge.permissions;

import com.intellij.ide.util.PropertiesComponent;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistence for tool permissions. Two layers:
 * <ul>
 *   <li>Explicit per-tool overrides (persisted in PropertiesComponent)</li>
 *   <li>Session-level cache ("Allow for Session" clicks, in-memory only)</li>
 * </ul>
 */
public class PermissionStore {

    private static final String PROPERTY_PREFIX = "agentbridge.permission.";

    private final Set<String> sessionAllowed = ConcurrentHashMap.newKeySet();
    private final PropertiesComponent properties;

    public PermissionStore(PropertiesComponent properties) {
        this.properties = properties;
    }

    /**
     * Get explicit user-configured permission for a tool, or null if not set.
     */
    public @Nullable ToolPermission getExplicitPermission(String toolId) {
        String value = properties.getValue(PROPERTY_PREFIX + toolId);
        if (value == null) {
            return null;
        }
        try {
            return ToolPermission.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Set an explicit permission override for a tool.
     */
    public void setExplicitPermission(String toolId, ToolPermission permission) {
        properties.setValue(PROPERTY_PREFIX + toolId, permission.name());
    }

    /**
     * Remove an explicit permission override for a tool.
     */
    public void removeExplicitPermission(String toolId) {
        properties.unsetValue(PROPERTY_PREFIX + toolId);
    }

    /**
     * Mark a tool as allowed for the current session.
     */
    public void allowForSession(String toolId) {
        sessionAllowed.add(toolId);
    }

    /**
     * Check if a tool was allowed for the current session.
     */
    public boolean isSessionAllowed(String toolId) {
        return sessionAllowed.contains(toolId);
    }

    /**
     * Clear all session-level permissions (called on agent restart).
     */
    public void clearSessionPermissions() {
        sessionAllowed.clear();
    }
}
