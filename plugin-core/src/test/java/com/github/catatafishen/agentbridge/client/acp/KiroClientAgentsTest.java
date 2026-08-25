package com.github.catatafishen.agentbridge.client.acp;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link KiroClient#buildSetModeParams(String, String)} — the pure builder for
 * the v3 {@code session/set_mode} request that applies agent (mode) selection.
 */
class KiroClientAgentsTest {

    @Test
    @DisplayName("uses standard ACP 'modeId' field, not the legacy 'mode' field")
    void usesModeIdField() {
        JsonObject params = KiroClient.buildSetModeParams("session-123", "intellij-task");

        assertTrue(params.has("modeId"), "expected 'modeId' key");
        assertFalse(params.has("mode"), "must not use the legacy 'mode' key");
        assertEquals("intellij-task", params.get("modeId").getAsString());
    }

    @Test
    @DisplayName("includes the session id")
    void includesSessionId() {
        JsonObject params = KiroClient.buildSetModeParams("session-abc", "kiro_planner");

        assertEquals("session-abc", params.get("sessionId").getAsString());
        assertEquals("kiro_planner", params.get("modeId").getAsString());
    }

    @Test
    @DisplayName("default agent slug is intellij-task")
    void defaultAgentSlugConstant() {
        assertEquals("intellij-task", KiroClient.DEFAULT_AGENT_SLUG);
    }
}
