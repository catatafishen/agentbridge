package com.github.catatafishen.agentbridge.client.acp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OpenCodeServerEndpointTest {

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
        OpenCodeServerEndpoint endpoint = new OpenCodeServerEndpoint();

        assertNull(endpoint.baseUrl());
        endpoint.publish(0);
        assertNull(endpoint.baseUrl());
        endpoint.publish(5123);
        assertEquals("http://127.0.0.1:5123", endpoint.baseUrl());
    }

    @Test
    void clearForgetsThePort() {
        OpenCodeServerEndpoint endpoint = new OpenCodeServerEndpoint();
        endpoint.publish(5123);

        endpoint.clear();

        assertNull(endpoint.baseUrl(), "a stopped agent's port must not be used for later requests");
    }

    @Test
    void endpointsOfDifferentClientsAreIndependent() {
        OpenCodeServerEndpoint projectA = new OpenCodeServerEndpoint();
        OpenCodeServerEndpoint projectB = new OpenCodeServerEndpoint();

        projectA.publish(5001);
        projectB.publish(5002);
        projectB.clear();

        assertEquals("http://127.0.0.1:5001", projectA.baseUrl(), "launching or stopping B must not affect A");
        assertNull(projectB.baseUrl());
    }
}
