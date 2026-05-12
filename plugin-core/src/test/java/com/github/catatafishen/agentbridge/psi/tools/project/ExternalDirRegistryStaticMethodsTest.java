package com.github.catatafishen.agentbridge.psi.tools.project;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ExternalDirRegistry#isUnderDirectory} — the path-guard logic
 * that prevents write tools from modifying attached external directories.
 *
 * <p>These tests focus on the path normalization behavior that blocks traversal attacks
 * and works correctly across POSIX and Windows paths.
 */
@DisplayName("ExternalDirRegistry path guard")
class ExternalDirRegistryStaticMethodsTest {

    @Test
    @DisplayName("Path exactly equal to the base directory is blocked")
    void exactMatch_isBlocked() {
        assertTrue(ExternalDirRegistry.isUnderDirectory(
            Path.of("/home/user/external"),
            Path.of("/home/user/external")
        ));
    }

    @Test
    @DisplayName("Path directly inside the base directory is blocked")
    void directChild_isBlocked() {
        assertTrue(ExternalDirRegistry.isUnderDirectory(
            Path.of("/home/user/external/file.txt"),
            Path.of("/home/user/external")
        ));
    }

    @Test
    @DisplayName("Deeply nested path inside the base directory is blocked")
    void deeplyNested_isBlocked() {
        assertTrue(ExternalDirRegistry.isUnderDirectory(
            Path.of("/home/user/external/src/main/java/Foo.java"),
            Path.of("/home/user/external")
        ));
    }

    @Test
    @DisplayName("Path outside the base directory is allowed")
    void outsideDir_isAllowed() {
        assertFalse(ExternalDirRegistry.isUnderDirectory(
            Path.of("/home/user/other-project/file.txt"),
            Path.of("/home/user/external")
        ));
    }

    @Test
    @DisplayName("Sibling directory with same prefix is allowed (no prefix confusion)")
    void siblingWithSamePrefix_isAllowed() {
        assertFalse(ExternalDirRegistry.isUnderDirectory(
            Path.of("/home/user/external-extra/file.txt"),
            Path.of("/home/user/external")
        ));
    }

    @Test
    @DisplayName("Traversal path resolving to inside the base is blocked")
    void traversalResolvingToInside_isBlocked() {
        // /home/user/ext/../external/file.txt normalizes to /home/user/external/file.txt
        assertTrue(ExternalDirRegistry.isUnderDirectory(
            Path.of("/home/user/ext/../external/file.txt"),
            Path.of("/home/user/external")
        ));
    }

    @Test
    @DisplayName("Traversal path resolving to outside the base is allowed")
    void traversalResolvingToOutside_isAllowed() {
        // /home/user/external/../other/secret.txt normalizes to /home/user/other/secret.txt
        assertFalse(ExternalDirRegistry.isUnderDirectory(
            Path.of("/home/user/external/../other/secret.txt"),
            Path.of("/home/user/external")
        ));
    }

    @Test
    @DisplayName("Traversal in base directory is also normalized")
    void traversalInBase_isNormalized() {
        // Base: /home/user/a/../external == /home/user/external
        assertTrue(ExternalDirRegistry.isUnderDirectory(
            Path.of("/home/user/external/file.txt"),
            Path.of("/home/user/a/../external")
        ));
    }
}
