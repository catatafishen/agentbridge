package com.github.catatafishen.agentbridge.services;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link AgentIdMapper}.
 */
class AgentIdMapperTest {

    @Nested
    @DisplayName("toAgentId")
    class ToAgentId {

        @Test
        @DisplayName("returns 'unknown' for null")
        void nullAgent() {
            assertEquals("unknown", AgentIdMapper.toAgentId(null));
        }

        @Test
        @DisplayName("returns 'unknown' for empty string")
        void emptyAgent() {
            assertEquals("unknown", AgentIdMapper.toAgentId(""));
        }

        @Test
        @DisplayName("maps 'GitHub Copilot' → 'copilot'")
        void copilot() {
            assertEquals("copilot", AgentIdMapper.toAgentId("GitHub Copilot"));
        }

        @Test
        @DisplayName("maps 'Claude Code' → 'claude-cli'")
        void claude() {
            assertEquals("claude-cli", AgentIdMapper.toAgentId("Claude Code"));
        }

        @Test
        @DisplayName("maps 'OpenCode Agent' → 'opencode'")
        void opencode() {
            assertEquals("opencode", AgentIdMapper.toAgentId("OpenCode Agent"));
        }

        @Test
        @DisplayName("maps 'Junie' → 'junie'")
        void junie() {
            assertEquals("junie", AgentIdMapper.toAgentId("Junie"));
        }

        @Test
        @DisplayName("maps 'Kiro' → 'kiro'")
        void kiro() {
            assertEquals("kiro", AgentIdMapper.toAgentId("Kiro"));
        }

        @Test
        @DisplayName("maps 'codex-mini' → 'codex'")
        void codex() {
            assertEquals("codex", AgentIdMapper.toAgentId("codex-mini"));
        }

        @Test
        @DisplayName("normalizes unknown agent names to lowercase kebab-case")
        void unknownAgent() {
            assertEquals("my-custom-agent", AgentIdMapper.toAgentId("My Custom Agent"));
        }
    }

    @Test
    @DisplayName("private constructor enforces utility-class pattern")
    void constructorThrowsUtilityClassException() throws Exception {
        var constructor = AgentIdMapper.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        var ex = assertThrows(InvocationTargetException.class, constructor::newInstance);
        assertInstanceOf(IllegalStateException.class, ex.getCause());
    }
}
