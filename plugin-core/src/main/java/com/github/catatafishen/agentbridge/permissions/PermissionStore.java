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
 * <p>
 * Permissions can be scoped by {@link PathScope}. The legacy key {@code agentbridge.permission.<toolId>}
 * stores the inside-project / scope-agnostic permission; an additional key
 * {@code agentbridge.permission.<toolId>.outside} stores the outside-project permission for
 * path-bearing tools. Existing single-argument methods preserve the legacy behavior and target
 * {@link PathScope#INSIDE_PROJECT}.
 */
public class PermissionStore {

    private static final String PROPERTY_PREFIX = "agentbridge.permission.";
    private static final String OUTSIDE_SUFFIX = ".outside";

    private final Set<String> sessionAllowed = ConcurrentHashMap.newKeySet();
    private final PropertiesComponent properties;

    public PermissionStore(PropertiesComponent properties) {
        this.properties = properties;
    }

    /**
     * Get explicit user-configured permission for a tool (inside-project scope), or null if not set.
     */
    public @Nullable ToolPermission getExplicitPermission(String toolId) {
        return getExplicitPermission(toolId, PathScope.INSIDE_PROJECT);
    }

    /**
     * Get explicit user-configured permission for a tool at a specific path scope.
     * Returns null if no override is configured for that scope.
     * <p>
     * For {@link PathScope#NOT_APPLICABLE} the inside-project key is consulted —
     * tools without a scope distinction share the legacy unscoped key.
     */
    public @Nullable ToolPermission getExplicitPermission(String toolId, PathScope scope) {
        String value = properties.getValue(scopedKey(toolId, scope));
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
     * Set an explicit permission override for a tool (inside-project scope).
     */
    public void setExplicitPermission(String toolId, ToolPermission permission) {
        setExplicitPermission(toolId, PathScope.INSIDE_PROJECT, permission);
    }

    /**
     * Set an explicit permission override for a tool at a specific path scope.
     */
    public void setExplicitPermission(String toolId, PathScope scope, ToolPermission permission) {
        properties.setValue(scopedKey(toolId, scope), permission.name());
    }

    /**
     * Remove an explicit permission override for a tool (inside-project scope).
     */
    public void removeExplicitPermission(String toolId) {
        removeExplicitPermission(toolId, PathScope.INSIDE_PROJECT);
    }

    /**
     * Remove an explicit permission override for a tool at a specific path scope.
     */
    public void removeExplicitPermission(String toolId, PathScope scope) {
        properties.unsetValue(scopedKey(toolId, scope));
    }

    /**
     * Mark a tool as allowed for the current session (inside-project scope).
     */
    public void allowForSession(String toolId) {
        allowForSession(toolId, PathScope.INSIDE_PROJECT);
    }

    /**
     * Mark a tool as allowed for the current session at a specific path scope.
     */
    public void allowForSession(String toolId, PathScope scope) {
        sessionAllowed.add(sessionKey(toolId, scope));
    }

    /**
     * Check if a tool was allowed for the current session (inside-project scope).
     */
    public boolean isSessionAllowed(String toolId) {
        return isSessionAllowed(toolId, PathScope.INSIDE_PROJECT);
    }

    /**
     * Check if a tool was allowed for the current session at a specific path scope.
     */
    public boolean isSessionAllowed(String toolId, PathScope scope) {
        return sessionAllowed.contains(sessionKey(toolId, scope));
    }

    /**
     * Clear all session-level permissions (called on agent restart).
     */
    public void clearSessionPermissions() {
        sessionAllowed.clear();
    }

    private static String scopedKey(String toolId, PathScope scope) {
        return PROPERTY_PREFIX + toolId + suffix(scope);
    }

    private static String sessionKey(String toolId, PathScope scope) {
        return toolId + suffix(scope);
    }

    private static String suffix(PathScope scope) {
        return scope == PathScope.OUTSIDE_PROJECT ? OUTSIDE_SUFFIX : "";
    }
}
