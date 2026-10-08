package com.github.catatafishen.agentbridge.client.acp;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the OpenCode version gating that routes model changes:
 * v2+ uses {@code session/set_config_option(configId=model)}, v1 keeps
 * the legacy {@code session/set_model}.
 */
class OpenCodeClientModelRoutingTest {

    // ── isOpenCodeV2OrLater (version gating) ────────────────────────────

    @ParameterizedTest
    @CsvSource({
        "2.0.0, true",
        "2.1.7, true",
        "10.0.0, true",
        "1.179.7, false",
        "1.15.13, false",
        "0.9.1, false"
    })
    void isOpenCodeV2OrLater_versionThreshold(String version, boolean expected) {
        assertEquals(expected, OpenCodeClient.isOpenCodeV2OrLater(version));
    }

    @Test
    void isOpenCodeV2OrLater_nullIsTrue() {
        assertTrue(OpenCodeClient.isOpenCodeV2OrLater(null));
    }

    @Test
    void isOpenCodeV2OrLater_blankIsTrue() {
        assertTrue(OpenCodeClient.isOpenCodeV2OrLater("  "));
    }

    @Test
    void isOpenCodeV2OrLater_preReleaseSuffixIsIgnored() {
        assertTrue(OpenCodeClient.isOpenCodeV2OrLater("2.0.0-beta.12"));
        assertFalse(OpenCodeClient.isOpenCodeV2OrLater("1.99.9-nightly.3"));
    }

    @Test
    void isOpenCodeV2OrLater_vPrefixedVersionIsUnknown() {
        // 'v'-prefixed strings don't parse, so they land on the default-true
        // v2 path; pinned so a future parser change can't silently flip the gate.
        assertTrue(OpenCodeClient.isOpenCodeV2OrLater("v2.0.0"));
        assertTrue(OpenCodeClient.isOpenCodeV2OrLater("v1.2.3"));
    }

    @Test
    void isOpenCodeV2OrLater_singleSegmentVersion() {
        assertTrue(OpenCodeClient.isOpenCodeV2OrLater("2"));
        assertFalse(OpenCodeClient.isOpenCodeV2OrLater("1"));
    }
}
