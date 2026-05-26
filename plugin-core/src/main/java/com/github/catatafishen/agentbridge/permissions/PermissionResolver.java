package com.github.catatafishen.agentbridge.permissions;

import org.jetbrains.annotations.Nullable;

/**
 * Single decision point for tool permissions.
 * Checks abuse detection, explicit overrides, session cache, and client defaults — in that order.
 * <p>
 * Path-bearing tools (e.g. {@code read_file}, {@code search_text}) can pass a {@link PathScope}
 * so that inside-project and outside-project calls resolve against separate explicit/session/default
 * rules. Tools without a path-scope distinction pass {@link PathScope#NOT_APPLICABLE} (or use the
 * legacy overload that omits the parameter).
 */
public class PermissionResolver {

    private final AbuseDetector abuseDetector;
    private final PermissionStore store;

    public PermissionResolver(AbuseDetector abuseDetector, PermissionStore store) {
        this.abuseDetector = abuseDetector;
        this.store = store;
    }

    /**
     * Resolve the permission for a tool call, treating the call as having no path-scope distinction
     * (equivalent to {@link PathScope#NOT_APPLICABLE}).
     */
    public PermissionResult resolve(String toolId, @Nullable String arguments,
                                    ToolCategory category, PermissionDefaults defaults) {
        return resolve(toolId, arguments, category, PathScope.NOT_APPLICABLE, defaults);
    }

    /**
     * Resolve the permission for a tool call.
     *
     * @param toolId    canonical tool ID (e.g. "run_command", "git_commit")
     * @param arguments raw arguments JSON (may be null)
     * @param category  the tool's category for default lookup
     * @param scope     whether this call targets a path inside or outside the project
     *                  ({@link PathScope#NOT_APPLICABLE} for tools without a path-scope distinction)
     * @param defaults  the client's default permissions
     * @return resolution result with permission level and optional denial reason
     */
    public PermissionResult resolve(String toolId, @Nullable String arguments,
                                    ToolCategory category, PathScope scope,
                                    PermissionDefaults defaults) {
        // 1. Abuse detection — highest priority, overrides everything
        AbuseResult abuse = abuseDetector.check(toolId, arguments);
        if (abuse != null) {
            return PermissionResult.denied(abuse.reason());
        }

        // 2. Explicit per-tool override (user configured in settings) — scoped lookup first,
        //    fall back to the unscoped/inside key so legacy overrides keep applying.
        ToolPermission explicit = store.getExplicitPermission(toolId, scope);
        if (explicit == null && scope == PathScope.OUTSIDE_PROJECT) {
            explicit = store.getExplicitPermission(toolId, PathScope.INSIDE_PROJECT);
        }
        if (explicit != null) {
            return PermissionResult.of(explicit);
        }

        // 3. Session-level cache ("Allow for Session" clicks) — also scoped
        if (store.isSessionAllowed(toolId, scope)) {
            return PermissionResult.allowed();
        }

        // 4. Client default for this tool category + scope
        return PermissionResult.of(defaults.forCategory(category, scope));
    }

    /**
     * Mark a tool as allowed for the remainder of this session (inside-project / unscoped).
     */
    public void allowForSession(String toolId) {
        store.allowForSession(toolId);
    }

    /**
     * Mark a tool as allowed for the remainder of this session at a specific path scope.
     */
    public void allowForSession(String toolId, PathScope scope) {
        store.allowForSession(toolId, scope);
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
