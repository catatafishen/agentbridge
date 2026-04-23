package com.github.catatafishen.agentbridge.shim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sanity checks for {@link ShimManager#currentPlatformKey()} — the lookup key
 * used to resolve the bundled native shim from resources.
 *
 * <p>This is a value-only test (no IDE services), so it runs in plain JUnit
 * without {@code BasePlatformTestCase}.
 */
class ShimManagerPlatformKeyTest {

    @Test
    void platformKeyMatchesCurrentJvm() {
        String key = ShimManager.currentPlatformKey();

        // Must be one of the supported (os, arch) pairs we ship.
        assertTrue(
            key.matches("(linux|darwin|windows)-(amd64|arm64)"),
            "Unexpected platform key: " + key
        );
    }

    @Test
    void platformKeyComponentsSeparated() {
        String key = ShimManager.currentPlatformKey();
        String[] parts = key.split("-", -1);
        assertEquals(2, parts.length, "Key must have exactly one '-' separator: " + key);
        assertTrue(!parts[0].isEmpty() && !parts[1].isEmpty(), "Both components must be non-empty: " + key);
    }
}
