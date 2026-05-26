package com.github.catatafishen.agentbridge.permissions;

/**
 * Default permission levels per tool category.
 * Each AcpClient subclass returns its own defaults.
 * <p>
 * The {@code readToolsOutside} field controls reads (and read-like searches/listings)
 * that target paths <b>outside</b> the project root. Inside-project reads use
 * {@link #readTools}. Write/edit/execute categories don't have a scope split because
 * write tools are project-scoped by construction, and shell execution is gated by
 * {@link ToolCategory#EXECUTE} regardless of which filesystem it touches.
 */
public record PermissionDefaults(
    ToolPermission readTools,
    ToolPermission readToolsOutside,
    ToolPermission editTools,
    ToolPermission executeTools,
    ToolPermission gitReadTools,
    ToolPermission gitWriteTools,
    ToolPermission destructiveTools
) {

    /**
     * Standard defaults: inside-project reads auto-allowed, outside-project reads ASK,
     * git-reads auto-allowed, edits/execute/git-write ASK, destructive denied.
     */
    public static final PermissionDefaults STANDARD = new PermissionDefaults(
        ToolPermission.ALLOW,
        ToolPermission.ASK,
        ToolPermission.ASK,
        ToolPermission.ASK,
        ToolPermission.ALLOW,
        ToolPermission.ASK,
        ToolPermission.DENY
    );

    /**
     * Permissive defaults: everything allowed except destructive (and outside-project reads,
     * which still ASK — the user has to opt in explicitly to expose files outside the project).
     */
    public static final PermissionDefaults PERMISSIVE = new PermissionDefaults(
        ToolPermission.ALLOW,
        ToolPermission.ASK,
        ToolPermission.ALLOW,
        ToolPermission.ALLOW,
        ToolPermission.ALLOW,
        ToolPermission.ALLOW,
        ToolPermission.ASK
    );

    /**
     * Get the default permission for a given tool category, assuming inside-project scope.
     * Equivalent to {@code forCategory(category, PathScope.INSIDE_PROJECT)}.
     */
    public ToolPermission forCategory(ToolCategory category) {
        return forCategory(category, PathScope.INSIDE_PROJECT);
    }

    /**
     * Get the default permission for a given tool category and path scope.
     * <p>
     * Only {@link ToolCategory#READ} differentiates between inside and outside project —
     * other categories ignore the scope parameter.
     */
    public ToolPermission forCategory(ToolCategory category, PathScope scope) {
        if (category == ToolCategory.READ && scope == PathScope.OUTSIDE_PROJECT) {
            return readToolsOutside;
        }
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
