package com.github.catatafishen.agentbridge.client.acp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Unit tests for {@link GooseClient}.
 *
 * <p>Tests focus on the pure static/instance methods (agent ID, display name, command,
 * auth support) so they can run without the IntelliJ platform or a live ACP process.</p>
 */
class GooseClientTest {

    @Test
    void agentId_isGoose() {
        assertEquals("goose", GooseClient.AGENT_ID);
    }

    @Test
    void buildCommand_returnsGooseAcp() {
        GooseClient client = new GooseClient(null);
        assertEquals(List.of("goose", "acp"), client.buildCommand(null, 0));
    }

    @Test
    void supportsAuthenticate_isFalse() {
        GooseClient client = new GooseClient(null);
        assertFalse(client.supportsAuthenticate());
    }
}
