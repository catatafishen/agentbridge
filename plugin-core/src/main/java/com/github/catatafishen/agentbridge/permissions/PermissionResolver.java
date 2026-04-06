package com.github.catatafishen.agentbridge.permissions;

import org.jetbrains.annotations.Nullable;

/**
 * Single decision point for tool permissions.
 * Checks abuse detection, explicit overrides, session cache, and client defaults — in that order.
 */
public class PermissionResolver {

    private final AbuseDetector abuseDetector;
    private final PermissionStore store;

    public PermissionResolver(AbuseDetector abuseDetector, PermissionStore store) {
        this.abuseDetector = abuseDetector;
        this.store = store;
    }

    /**
     * Resolve the permission for a tool call.
     *
     * @param toolId     canonical tool ID (e.g. "run_command", "git_commit")
     * @param arguments  raw arguments JSON (may be null)
     * @param category   the tool's category for default lookup
     * @param defaults   the client's default permissions
     * @return resolution result with permission level and optional denial reason
     */
    public PermissionResult resolve(String toolId, @Nullable String arguments,
                                    ToolCategory category, PermissionDefaults defaults) {
        // 1. Abuse detection — highest priority, overrides everything
        AbuseResult abuse = abuseDetector.check(toolId, arguments);
        if (abuse != null) {
            return PermissionResult.denied(abuse.reason());
        }

        // 2. Explicit per-tool override (user configured in settings)
        ToolPermission explicit = store.getExplicitPermission(toolId);
        if (explicit != null) {
            return PermissionResult.of(explicit);
        }

        // 3. Session-level cache ("Allow for Session" clicks)
        if (store.isSessionAllowed(toolId)) {
            return PermissionResult.allowed();
        }

        // 4. Client default for this tool category
        return PermissionResult.of(defaults.forCategory(category));
    }

    /**
     * Mark a tool as allowed for the remainder of this session.
     */
    public void allowForSession(String toolId) {
        store.allowForSession(toolId);
    }

    /**
     * Clear session-level permissions (called on agent restart).
     */
    public void clearSession() {
        store.clearSessionPermissions();
    }

    /**
     * Result of a permission resolution.
     */
    public record PermissionResult(ToolPermission permission, @Nullable String denialReason) {
        public static PermissionResult allowed() {
            return new PermissionResult(ToolPermission.ALLOW, null);
        }

        public static PermissionResult denied(String reason) {
            return new PermissionResult(ToolPermission.DENY, reason);
        }

        public static PermissionResult ask() {
            return new PermissionResult(ToolPermission.ASK, null);
        }

        public static PermissionResult of(ToolPermission permission) {
            return new PermissionResult(permission, null);
        }

        public boolean isAllowed() {
            return permission == ToolPermission.ALLOW;
        }

        public boolean isDenied() {
            return permission == ToolPermission.DENY;
        }
    }
}
