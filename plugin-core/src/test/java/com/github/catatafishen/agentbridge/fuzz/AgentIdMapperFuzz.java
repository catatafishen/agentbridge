package com.github.catatafishen.agentbridge.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.github.catatafishen.agentbridge.services.AgentIdMapper;

/**
 * Jazzer fuzz target for {@link AgentIdMapper}.
 *
 * <p>Tests that {@link AgentIdMapper#toAgentId(String)} handles arbitrary agent display names
 * without throwing uncaught exceptions, producing null, or entering infinite loops.
 * This is called with agent names extracted from session files and AI responses —
 * both untrusted sources.
 *
 * <p>Also serves to register the repository with the OpenSSF Scorecard Fuzzing check,
 * which detects the {@code com.code_intelligence.jazzer.api.FuzzedDataProvider} import.
 *
 * <p>To run: {@code java -jar jazzer.jar --cp=<test-classpath>
 * --target_class=com.github.catatafishen.agentbridge.fuzz.AgentIdMapperFuzz}
 */
public class AgentIdMapperFuzz {

    public static void fuzzerTestOneInput(FuzzedDataProvider data) {
        String agentName = data.consumeRemainingAsString();
        // toAgentId is @NotNull — only check for unexpected empty results.
        String result = AgentIdMapper.toAgentId(agentName);
        if (result.isEmpty()) {
            throw new AssertionError("toAgentId returned empty for: " + agentName);
        }
    }
}
