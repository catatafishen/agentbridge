package com.github.catatafishen.agentbridge.client.acp;

import com.github.catatafishen.agentbridge.bridge.SessionOption;
import com.github.catatafishen.agentbridge.services.AutopilotPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenCodeAutopilotOptionTest {

    @Test
    void autopilotOptionOffersAutopilotAndSupervised() {
        SessionOption option = OpenCodeClient.autopilotSessionOption(false);
        assertEquals(AutopilotPolicy.OPTION_KEY, option.key());
        assertEquals("Autopilot", option.displayName());
        assertEquals(List.of(AutopilotPolicy.AUTOPILOT, AutopilotPolicy.SUPERVISED), option.values());
        assertEquals("Autopilot", option.labelFor(AutopilotPolicy.AUTOPILOT));
        assertEquals("Supervised", option.labelFor(AutopilotPolicy.SUPERVISED));
    }

    @Test
    void autopilotOptionShowsCurrentStateAsSelected() {
        assertEquals(AutopilotPolicy.AUTOPILOT,
            OpenCodeClient.autopilotSessionOption(true).effectiveValue(AutopilotPolicy.SUPERVISED));
        assertEquals(AutopilotPolicy.SUPERVISED,
            OpenCodeClient.autopilotSessionOption(false).effectiveValue(AutopilotPolicy.AUTOPILOT));
    }
}
