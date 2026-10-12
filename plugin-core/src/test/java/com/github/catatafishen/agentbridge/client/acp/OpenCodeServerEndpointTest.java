package com.github.catatafishen.agentbridge.client.acp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OpenCodeServerEndpointTest {

    @AfterEach
    void reset() {
        OpenCodeServerEndpoint.publish(-1);
    }

    @Test
    void portFromArgsIsNullWhenFlagAbsent() {
        assertNull(OpenCodeServerEndpoint.portFromArgs(List.of("opencode", "acp", "--print-logs")));
    }

    @Test
    void portFromArgsReadsSeparateValue() {
        assertEquals(4096, OpenCodeServerEndpoint.portFromArgs(List.of("opencode", "acp", "--port", "4096")));
    }

    @Test
    void portFromArgsReadsEqualsForm() {
        assertEquals(4097, OpenCodeServerEndpoint.portFromArgs(List.of("opencode", "acp", "--port=4097")));
    }

    @Test
    void portFromArgsReportsZeroForUnparseableOrMissingValue() {
        assertEquals(0, OpenCodeServerEndpoint.portFromArgs(List.of("--port", "abc")));
        assertEquals(0, OpenCodeServerEndpoint.portFromArgs(List.of("--port")));
    }

    @Test
    void baseUrlIsNullUntilPortPublished() {
        assertNull(OpenCodeServerEndpoint.baseUrl());
        OpenCodeServerEndpoint.publish(0);
        assertNull(OpenCodeServerEndpoint.baseUrl());
        OpenCodeServerEndpoint.publish(5123);
        assertEquals("http://127.0.0.1:5123", OpenCodeServerEndpoint.baseUrl());
    }
}
