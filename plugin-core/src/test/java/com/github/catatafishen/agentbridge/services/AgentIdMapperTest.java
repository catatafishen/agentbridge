package com.github.catatafishen.agentbridge.services;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link AgentIdMapper}.
 */
@DisplayName("AgentIdMapper")
class AgentIdMapperTest {

    @ParameterizedTest(name = "''{0}'' → ''{1}''")
    @DisplayName("toAgentId maps known display names to profile IDs")
    @CsvSource({
        "GitHub Copilot, copilot",
        "Copilot, copilot",
        "copilot-cli, copilot",
        "Claude Code, claude-cli",
        "Claude, claude-cli",
        "claude, claude-cli",
        "OpenCode, opencode",
        "opencode, opencode",
        "Junie, junie",
        "JetBrains Junie, junie",
        "Kiro, kiro",
        "Amazon Kiro, kiro",
        "Codex, codex",
        "OpenAI Codex, codex",
        "Mistral Vibe, vibe",
        "Vibe, vibe",
        "vibe-acp, vibe"
    })
    void toAgentId_mapsKnownDisplayNames(String displayName, String expectedId) {
        assertEquals(expectedId, AgentIdMapper.toAgentId(displayName));
    }

    @ParameterizedTest
    @DisplayName("toAgentId returns 'unknown' for null or empty input")
    @NullAndEmptySource
    void toAgentId_returnsUnknownForNullOrEmpty(String input) {
        assertEquals("unknown", AgentIdMapper.toAgentId(input));
    }

    @Test
    @DisplayName("toAgentId normalizes unknown names to lowercase with dashes")
    void toAgentId_normalizesUnknownNames() {
        assertEquals("my-custom-agent", AgentIdMapper.toAgentId("My Custom Agent"));
        assertEquals("agent-x-2", AgentIdMapper.toAgentId("Agent X 2"));
    }

    @Test
    @DisplayName("toAgentId is case-insensitive")
    void toAgentId_isCaseInsensitive() {
        assertEquals("copilot", AgentIdMapper.toAgentId("GITHUB COPILOT"));
        assertEquals("claude-cli", AgentIdMapper.toAgentId("CLAUDE CODE"));
        assertEquals("vibe", AgentIdMapper.toAgentId("MISTRAL VIBE"));
    }
}
