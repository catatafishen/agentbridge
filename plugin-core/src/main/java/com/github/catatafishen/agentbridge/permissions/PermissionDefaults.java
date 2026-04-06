package com.github.catatafishen.agentbridge.permissions;

/**
 * Default permission levels per tool category.
 * Each AcpClient subclass returns its own defaults.
 */
public record PermissionDefaults(
        ToolPermission readTools,
        ToolPermission editTools,
        ToolPermission executeTools,
        ToolPermission gitReadTools,
        ToolPermission gitWriteTools,
        ToolPermission destructiveTools
) {

    /**
     * Standard defaults: read/git-read auto-allowed, edits/execute/git-write ask, destructive denied.
     */
    public static final PermissionDefaults STANDARD = new PermissionDefaults(
            ToolPermission.ALLOW,
            ToolPermission.ASK,
            ToolPermission.ASK,
            ToolPermission.ALLOW,
            ToolPermission.ASK,
            ToolPermission.DENY
    );

    /**
     * Permissive defaults: everything allowed except destructive.
     */
    public static final PermissionDefaults PERMISSIVE = new PermissionDefaults(
            ToolPermission.ALLOW,
            ToolPermission.ALLOW,
            ToolPermission.ALLOW,
            ToolPermission.ALLOW,
            ToolPermission.ALLOW,
            ToolPermission.ASK
    );

    /**
     * Get the default permission for a given tool category.
     */
    public ToolPermission forCategory(ToolCategory category) {
        return switch (category) {
            case READ -> readTools;
            case EDIT -> editTools;
            case EXECUTE -> executeTools;
            case GIT_READ -> gitReadTools;
            case GIT_WRITE -> gitWriteTools;
            case DESTRUCTIVE -> destructiveTools;
            case OTHER -> ToolPermission.ASK;
        };
    }
}
