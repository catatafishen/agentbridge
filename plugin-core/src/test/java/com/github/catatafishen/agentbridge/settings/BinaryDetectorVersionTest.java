package com.github.catatafishen.agentbridge.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link BinaryDetector#compareVersions} and version parsing logic.
 */
@DisplayName("BinaryDetector version comparison")
class BinaryDetectorVersionTest {

    @ParameterizedTest(name = "compareVersions(\"{0}\", \"{1}\") = {2}")
    @CsvSource({
        "1.0.40, 1.0.20, 1",
        "1.0.20, 1.0.40, -1",
        "1.0.40, 1.0.40, 0",
        "v1.0.40, v1.0.20, 1",
        "v1.0.40, 1.0.20, 1",
        "2.0.0, 1.99.99, 1",
        "1.10.0, 1.9.0, 1",
        "1.0.0, 1.0, 0",
    })
    @DisplayName("semantic version comparison")
    void compareVersions(String v1, String v2, int expected) {
        int result = BinaryDetector.compareVersions(v1, v2);
        assertEquals(Integer.signum(expected), Integer.signum(result),
            "compareVersions(\"" + v1 + "\", \"" + v2 + "\")");
    }

    @Test
    @DisplayName("null versions compare equal")
    void nullVersions() {
        assertEquals(0, BinaryDetector.compareVersions(null, null));
    }

    @Test
    @DisplayName("non-null > null")
    void nonNullGreaterThanNull() {
        assertTrue(BinaryDetector.compareVersions("1.0.0", null) > 0);
        assertTrue(BinaryDetector.compareVersions(null, "1.0.0") < 0);
    }

    @Test
    @DisplayName("version with prefix text (e.g. 'Copilot v1.0.40')")
    void versionWithPrefixText() {
        assertTrue(BinaryDetector.compareVersions("Copilot v1.0.40", "Copilot v1.0.20") > 0);
    }

    @Test
    @DisplayName("version with suffix (e.g. '1.0.40-1')")
    void versionWithSuffix() {
        // 1.0.40-1 is treated as 1.0.40 (suffix stripped)
        assertEquals(0, BinaryDetector.compareVersions("1.0.40-1", "1.0.40"));
    }

    @Test
    @DisplayName("empty and blank versions compare equal to null")
    void emptyVersions() {
        assertEquals(0, BinaryDetector.compareVersions("", null));
        assertEquals(0, BinaryDetector.compareVersions("  ", null));
    }
}
