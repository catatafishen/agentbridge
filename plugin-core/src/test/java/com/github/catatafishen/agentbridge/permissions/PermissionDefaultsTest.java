package com.github.catatafishen.agentbridge.permissions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PermissionDefaults")
class PermissionDefaultsTest {

    @ParameterizedTest
    @CsvSource({
            "READ, ALLOW",
            "EDIT, ASK",
            "EXECUTE, ASK",
            "GIT_READ, ALLOW",
            "GIT_WRITE, ASK",
            "DESTRUCTIVE, DENY",
            "OTHER, ASK",
    })
    @DisplayName("STANDARD defaults")
    void standardDefaults(String category, String expectedPermission) {
        ToolCategory cat = ToolCategory.valueOf(category);
        ToolPermission expected = ToolPermission.valueOf(expectedPermission);
        assertEquals(expected, PermissionDefaults.STANDARD.forCategory(cat));
    }

    @ParameterizedTest
    @CsvSource({
            "READ, ALLOW",
            "EDIT, ALLOW",
            "EXECUTE, ALLOW",
            "GIT_READ, ALLOW",
            "GIT_WRITE, ALLOW",
            "DESTRUCTIVE, ASK",
            "OTHER, ASK",
    })
    @DisplayName("PERMISSIVE defaults")
    void permissiveDefaults(String category, String expectedPermission) {
        ToolCategory cat = ToolCategory.valueOf(category);
        ToolPermission expected = ToolPermission.valueOf(expectedPermission);
        assertEquals(expected, PermissionDefaults.PERMISSIVE.forCategory(cat));
    }

    @Test
    @DisplayName("STANDARD and PERMISSIVE are distinct instances")
    void distinctInstances() {
        assertNotSame(PermissionDefaults.STANDARD, PermissionDefaults.PERMISSIVE);
    }
}
