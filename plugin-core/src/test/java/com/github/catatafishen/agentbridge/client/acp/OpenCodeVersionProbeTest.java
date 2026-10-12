package com.github.catatafishen.agentbridge.client.acp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OpenCodeVersionProbeTest {

    @ParameterizedTest
    @CsvSource({
        "1.19.0, 1.19.0",
        "'1.19.0\n', 1.19.0",
        "v2.0.27, 2.0.27",
        "opencode 1.2.3-beta.4, 1.2.3",
        "'WARN something\n2.0.27', 2.0.27",
        "10.0, 10.0"
    })
    void findsTheVersionInTheOutput(String output, String expected) {
        assertEquals(expected, OpenCodeVersionProbe.parseVersionOutput(output));
    }

    @Test
    void outputWithoutAVersionGivesNull() {
        assertNull(OpenCodeVersionProbe.parseVersionOutput(null));
        assertNull(OpenCodeVersionProbe.parseVersionOutput(""));
        assertNull(OpenCodeVersionProbe.parseVersionOutput("ERROR | Unrecognized flag: --version"));
        assertNull(OpenCodeVersionProbe.parseVersionOutput("2"), "a single number is not a version");
    }

    @Test
    void aVersionFoundByTheProbeIsClassifiedByTheClientGate() {
        // The probe strips a leading "v", which KiroClient.parseVersion (behind isOpenCodeV2OrLater)
        // would reject and silently treat as 2.x.
        assertEquals(false, OpenCodeClient.isOpenCodeV2OrLater(OpenCodeVersionProbe.parseVersionOutput("v1.19.0")));
        assertEquals(true, OpenCodeClient.isOpenCodeV2OrLater(OpenCodeVersionProbe.parseVersionOutput("v2.0.27")));
    }
}
