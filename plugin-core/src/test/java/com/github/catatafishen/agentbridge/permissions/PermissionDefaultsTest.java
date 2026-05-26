package com.github.catatafishen.agentbridge.permissions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("PermissionDefaults")
class PermissionDefaultsTest {

    // ── STANDARD preset ─────────────────────────────────────────────────

    @Test
    @DisplayName("STANDARD: READ is ALLOW")
    void standard_readAllowed() {
        assertEquals(ToolPermission.ALLOW, PermissionDefaults.STANDARD.forCategory(ToolCategory.READ));
    }

    @Test
    @DisplayName("STANDARD: READ outside-project is ASK")
    void standard_readOutsideAsks() {
        assertEquals(ToolPermission.ASK,
            PermissionDefaults.STANDARD.forCategory(ToolCategory.READ, PathScope.OUTSIDE_PROJECT));
    }

    @Test
    @DisplayName("STANDARD: READ inside-project is ALLOW")
    void standard_readInsideAllowed() {
        assertEquals(ToolPermission.ALLOW,
            PermissionDefaults.STANDARD.forCategory(ToolCategory.READ, PathScope.INSIDE_PROJECT));
    }

    @Test
    @DisplayName("STANDARD: EDIT is ASK")
    void standard_editAsks() {
        assertEquals(ToolPermission.ASK, PermissionDefaults.STANDARD.forCategory(ToolCategory.EDIT));
    }

    @Test
    @DisplayName("STANDARD: EXECUTE is ASK")
    void standard_executeAsks() {
        assertEquals(ToolPermission.ASK, PermissionDefaults.STANDARD.forCategory(ToolCategory.EXECUTE));
    }

    @Test
    @DisplayName("STANDARD: GIT_READ is ALLOW")
    void standard_gitReadAllowed() {
        assertEquals(ToolPermission.ALLOW, PermissionDefaults.STANDARD.forCategory(ToolCategory.GIT_READ));
    }

    @Test
    @DisplayName("STANDARD: GIT_WRITE is ASK")
    void standard_gitWriteAsks() {
        assertEquals(ToolPermission.ASK, PermissionDefaults.STANDARD.forCategory(ToolCategory.GIT_WRITE));
    }

    @Test
    @DisplayName("STANDARD: DESTRUCTIVE is DENY")
    void standard_destructiveDenied() {
        assertEquals(ToolPermission.DENY, PermissionDefaults.STANDARD.forCategory(ToolCategory.DESTRUCTIVE));
    }

    @Test
    @DisplayName("STANDARD: OTHER is ASK")
    void standard_otherAsks() {
        assertEquals(ToolPermission.ASK, PermissionDefaults.STANDARD.forCategory(ToolCategory.OTHER));
    }

    // ── PERMISSIVE preset ───────────────────────────────────────────────

    @Test
    @DisplayName("PERMISSIVE: all categories except DESTRUCTIVE are ALLOW")
    void permissive_allExceptDestructive() {
        assertEquals(ToolPermission.ALLOW, PermissionDefaults.PERMISSIVE.forCategory(ToolCategory.READ));
        assertEquals(ToolPermission.ALLOW, PermissionDefaults.PERMISSIVE.forCategory(ToolCategory.EDIT));
        assertEquals(ToolPermission.ALLOW, PermissionDefaults.PERMISSIVE.forCategory(ToolCategory.EXECUTE));
        assertEquals(ToolPermission.ALLOW, PermissionDefaults.PERMISSIVE.forCategory(ToolCategory.GIT_READ));
        assertEquals(ToolPermission.ALLOW, PermissionDefaults.PERMISSIVE.forCategory(ToolCategory.GIT_WRITE));
    }

    @Test
    @DisplayName("PERMISSIVE: READ outside-project still ASKs (opt-in)")
    void permissive_readOutsideAsks() {
        assertEquals(ToolPermission.ASK,
            PermissionDefaults.PERMISSIVE.forCategory(ToolCategory.READ, PathScope.OUTSIDE_PROJECT));
    }

    @Test
    @DisplayName("PERMISSIVE: OTHER is ASK (hardcoded fallback)")
    void permissive_otherAsks() {
        assertEquals(ToolPermission.ASK, PermissionDefaults.PERMISSIVE.forCategory(ToolCategory.OTHER));
    }

    @Test
    @DisplayName("PERMISSIVE: DESTRUCTIVE is ASK")
    void permissive_destructiveAsks() {
        assertEquals(ToolPermission.ASK, PermissionDefaults.PERMISSIVE.forCategory(ToolCategory.DESTRUCTIVE));
    }

    // ── Custom instance ─────────────────────────────────────────────────

    @Test
    @DisplayName("Custom PermissionDefaults: forCategory returns configured values")
    void custom_forCategory() {
        PermissionDefaults custom = new PermissionDefaults(
            ToolPermission.DENY,    // readTools (inside)
            ToolPermission.DENY,    // readToolsOutside
            ToolPermission.DENY,    // editTools
            ToolPermission.ALLOW,   // executeTools
            ToolPermission.ASK,     // gitReadTools
            ToolPermission.DENY,    // gitWriteTools
            ToolPermission.ALLOW    // destructiveTools
        );
        assertEquals(ToolPermission.DENY, custom.forCategory(ToolCategory.READ));
        assertEquals(ToolPermission.DENY, custom.forCategory(ToolCategory.EDIT));
        assertEquals(ToolPermission.ALLOW, custom.forCategory(ToolCategory.EXECUTE));
        assertEquals(ToolPermission.ASK, custom.forCategory(ToolCategory.GIT_READ));
        assertEquals(ToolPermission.DENY, custom.forCategory(ToolCategory.GIT_WRITE));
        assertEquals(ToolPermission.ALLOW, custom.forCategory(ToolCategory.DESTRUCTIVE));
        // OTHER always returns ASK regardless of custom configuration
        assertEquals(ToolPermission.ASK, custom.forCategory(ToolCategory.OTHER));
    }

    @Test
    @DisplayName("Custom PermissionDefaults: outside-READ uses readToolsOutside slot")
    void custom_readOutsideSlot() {
        PermissionDefaults custom = new PermissionDefaults(
            ToolPermission.ALLOW,   // readTools (inside)
            ToolPermission.DENY,    // readToolsOutside
            ToolPermission.ASK,
            ToolPermission.ASK,
            ToolPermission.ALLOW,
            ToolPermission.ASK,
            ToolPermission.DENY
        );
        assertEquals(ToolPermission.ALLOW,
            custom.forCategory(ToolCategory.READ, PathScope.INSIDE_PROJECT));
        assertEquals(ToolPermission.DENY,
            custom.forCategory(ToolCategory.READ, PathScope.OUTSIDE_PROJECT));
        // NOT_APPLICABLE falls back to inside-project rules.
        assertEquals(ToolPermission.ALLOW,
            custom.forCategory(ToolCategory.READ, PathScope.NOT_APPLICABLE));
    }

    @Test
    @DisplayName("Non-READ categories ignore the PathScope parameter")
    void scopeIgnoredForNonRead() {
        PermissionDefaults custom = new PermissionDefaults(
            ToolPermission.ALLOW,
            ToolPermission.DENY,
            ToolPermission.ASK,
            ToolPermission.ASK,
            ToolPermission.ALLOW,
            ToolPermission.ASK,
            ToolPermission.DENY
        );
        for (PathScope scope : PathScope.values()) {
            assertEquals(ToolPermission.ASK, custom.forCategory(ToolCategory.EDIT, scope));
            assertEquals(ToolPermission.ASK, custom.forCategory(ToolCategory.EXECUTE, scope));
            assertEquals(ToolPermission.DENY, custom.forCategory(ToolCategory.DESTRUCTIVE, scope));
        }
    }
}
