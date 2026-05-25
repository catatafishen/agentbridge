package com.github.catatafishen.agentbridge.bridge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AgentConfig#sandboxConfigBindsForAgentId}.
 */
@DisplayName("AgentConfig.sandboxConfigBindsForAgentId")
class AgentConfigSandboxBindsTest {

    @TempDir
    Path homeDir;

    @Test
    @DisplayName("copilot: returns paths even when config dirs do not exist yet")
    void copilot_returnsPathsEvenIfNotCreatedYet() {
        // REGRESSION: previously existingSandboxConfigPaths() filtered out non-existent dirs,
        // so on first launch (no ~/.copilot yet) the config dir was never bind-mounted.
        // The CLI wrote tokens into the ephemeral tmpfs instead → auth loop on every launch.
        // Fix: return all expected paths unconditionally; BwrapSandbox pre-creates them.
        List<Path> binds = AgentConfig.sandboxConfigBindsForAgentId("copilot", homeDir);

        assertTrue(binds.stream().anyMatch(p -> p.endsWith(".copilot")),
            "~/.copilot must be included even when it does not exist");
        assertTrue(binds.stream().anyMatch(p -> p.endsWith(".config/github-copilot")),
            "~/.config/github-copilot must be included even when it does not exist");
    }

    @Test
    @DisplayName("copilot: returns both config paths when dirs exist")
    void copilot_returnsBothPathsWhenExisting() throws Exception {
        Path copilotDir = homeDir.resolve(".copilot");
        Files.createDirectories(copilotDir);
        Path githubCopilotDir = homeDir.resolve(".config/github-copilot");
        Files.createDirectories(githubCopilotDir);

        List<Path> binds = AgentConfig.sandboxConfigBindsForAgentId("copilot", homeDir);

        assertTrue(binds.contains(copilotDir), "~/.copilot must be included");
        assertTrue(binds.contains(githubCopilotDir), "~/.config/github-copilot must be included");
    }

    @Test
    @DisplayName("claude-cli: returns path even when dir does not exist")
    void claudeCli_returnsPathEvenIfNotCreatedYet() {
        List<Path> binds = AgentConfig.sandboxConfigBindsForAgentId("claude-cli", homeDir);
        assertTrue(binds.stream().anyMatch(p -> p.endsWith(".claude")),
            "~/.claude must be included even when it does not exist");
    }

    @Test
    @DisplayName("unknown agent: returns empty list")
    void unknownAgent_returnsEmptyList() {
        List<Path> binds = AgentConfig.sandboxConfigBindsForAgentId("unknown-agent", homeDir);
        assertTrue(binds.isEmpty(), "Unknown agents should return an empty list");
    }

    @Test
    @DisplayName("returned paths are under homeDir")
    void allPathsAreUnderHomeDir() {
        for (String agentId : List.of("copilot", "claude-cli", "codex", "kiro", "hermes", "opencode")) {
            List<Path> binds = AgentConfig.sandboxConfigBindsForAgentId(agentId, homeDir);
            for (Path p : binds) {
                assertTrue(p.startsWith(homeDir),
                    "All config bind paths must be under homeDir for agent: " + agentId + ", got: " + p);
            }
        }
    }

    @Test
    @DisplayName("no agent gets the root homeDir itself as a bind")
    void noAgentGetsHomeDirRoot() {
        for (String agentId : List.of("copilot", "claude-cli", "codex", "kiro", "hermes", "opencode")) {
            List<Path> binds = AgentConfig.sandboxConfigBindsForAgentId(agentId, homeDir);
            assertFalse(binds.contains(homeDir),
                "Home root must never be bind-mounted (would expose all of home): agent=" + agentId);
        }
    }
}
