package com.github.catatafishen.agentbridge.agent.claude;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link BundledAgentDeployer}.
 */
class BundledAgentDeployerTest {

    @TempDir
    Path tempDir;

    // ── null / empty guards ───────────────────────────────────────────────────

    @Test
    void nullBasePathIsNoop() {
        // Must not throw; without an absolute agentsDirectory, null basePath → skip
        BundledAgentDeployer.ensureAgents(null, List.of("ide-explore.md"));
        assertTrue(true); // confirms no exception was thrown
    }

    @Test
    void emptyListIsNoop() throws IOException {
        BundledAgentDeployer.ensureAgents(tempDir.toString(), List.of());
        // No subdirectories should have been created
        try (var stream = Files.list(tempDir)) {
            assertEquals(0, stream.count(), "no files must be created for an empty list");
        }
    }

    // ── deploy creates file ───────────────────────────────────────────────────

    @Test
    void deployCreatesDirectoryAndFile() {
        BundledAgentDeployer.ensureAgents(tempDir.toString(), List.of("ide-explore.md"));

        Path deployed = tempDir.resolve(".github/agents/ide-explore.md");
        assertTrue(Files.isRegularFile(deployed), "agent file must be created");
    }

    @Test
    void deployedFileStartsWithSentinel() throws IOException {
        BundledAgentDeployer.ensureAgents(tempDir.toString(), List.of("ide-explore.md"));

        Path deployed = tempDir.resolve(".github/agents/ide-explore.md");
        String content = Files.readString(deployed, StandardCharsets.UTF_8);
        assertTrue(content.startsWith("<!-- Deployed by AgentBridge"),
            "deployed file must start with the sentinel comment");
    }

    @Test
    void deployedFileContainsBundledResourceContent() throws IOException {
        BundledAgentDeployer.ensureAgents(tempDir.toString(), List.of("ide-explore.md"));

        Path deployed = tempDir.resolve(".github/agents/ide-explore.md");
        String content = Files.readString(deployed, StandardCharsets.UTF_8);
        // The sentinel is followed by the actual resource content — file must be non-trivial
        assertTrue(content.length() > 100,
            "deployed file must contain bundled resource content beyond the sentinel");
    }

    // ── idempotence ───────────────────────────────────────────────────────────

    @Test
    void idempotentWhenSentinelPresent() throws IOException {
        BundledAgentDeployer.ensureAgents(tempDir.toString(), List.of("ide-explore.md"));

        Path deployed = tempDir.resolve(".github/agents/ide-explore.md");
        String firstContent = Files.readString(deployed, StandardCharsets.UTF_8);

        BundledAgentDeployer.ensureAgents(tempDir.toString(), List.of("ide-explore.md"));
        String secondContent = Files.readString(deployed, StandardCharsets.UTF_8);

        assertEquals(firstContent, secondContent, "second deploy must not modify the file");
    }

    // ── user-managed file (no sentinel) is not overwritten ────────────────────

    @Test
    void doesNotOverwriteUserManagedFile() throws IOException {
        Path agentsDir = tempDir.resolve(".github/agents");
        Files.createDirectories(agentsDir);
        Path existingFile = agentsDir.resolve("ide-explore.md");
        String userContent = "# My custom agent\nThis is user-managed.";
        Files.writeString(existingFile, userContent, StandardCharsets.UTF_8);

        BundledAgentDeployer.ensureAgents(tempDir.toString(), List.of("ide-explore.md"));

        assertEquals(userContent, Files.readString(existingFile, StandardCharsets.UTF_8),
            "user-managed file without sentinel must not be overwritten");
    }

    // ── missing classpath resource ────────────────────────────────────────────

    @Test
    void missingResourceDoesNotCreateFile() {
        BundledAgentDeployer.ensureAgents(tempDir.toString(), List.of("nonexistent-agent.md"));

        Path notCreated = tempDir.resolve(".github/agents/nonexistent-agent.md");
        assertFalse(Files.exists(notCreated), "no file must be created for a missing resource");
    }

    // ── absolute agentsDirectory bypasses basePath ────────────────────────────

    @Test
    void deployWithAbsoluteDirectoryIgnoresNullBasePath() {
        Path absoluteDir = tempDir.resolve("custom-agents");

        BundledAgentDeployer.ensureAgents(null, absoluteDir.toString(), List.of("ide-explore.md"));

        assertTrue(Files.isRegularFile(absoluteDir.resolve("ide-explore.md")),
            "file must be deployed to absolute directory even when basePath is null");
    }

    // ── multiple files ────────────────────────────────────────────────────────

    @Test
    void deploysMultipleFilesInOneBatch() {
        BundledAgentDeployer.ensureAgents(tempDir.toString(), List.of("ide-explore.md", "ide-task.md"));

        assertTrue(Files.isRegularFile(tempDir.resolve(".github/agents/ide-explore.md")),
            "ide-explore.md must be deployed");
        assertTrue(Files.isRegularFile(tempDir.resolve(".github/agents/ide-task.md")),
            "ide-task.md must be deployed");
    }
}
