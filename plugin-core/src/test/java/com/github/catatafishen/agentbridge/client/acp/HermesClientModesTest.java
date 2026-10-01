package com.github.catatafishen.agentbridge.client.acp;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link HermesClient#buildSetModeParams(String, String)} — the pure builder for
 * the {@code session/set_mode} request that applies an ACP mode (e.g. a Hermes edit-approval
 * policy) to a live session.
 */
class HermesClientModesTest {

    @Test
    @DisplayName("uses standard ACP 'modeId' field, not the legacy 'mode' field")
    void usesModeIdField() {
        JsonObject params = HermesClient.buildSetModeParams("session-123", "dont_ask");

        assertTrue(params.has("modeId"), "expected 'modeId' key");
        assertFalse(params.has("mode"), "must not use the legacy 'mode' key");
        assertEquals("dont_ask", params.get("modeId").getAsString());
    }

    @Test
    @DisplayName("includes the session id")
    void includesSessionId() {
        JsonObject params = HermesClient.buildSetModeParams("session-abc", "accept_edits");

        assertEquals("session-abc", params.get("sessionId").getAsString());
        assertEquals("accept_edits", params.get("modeId").getAsString());
    }

    @Test
    @DisplayName("carries only the two ACP fields — no extras that a strict server would reject")
    void carriesOnlyAcpFields() {
        JsonObject params = HermesClient.buildSetModeParams("s", "default");

        assertEquals(2, params.keySet().size(), "unexpected extra fields: " + params.keySet());
    }
}
