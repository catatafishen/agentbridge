package com.github.catatafishen.agentbridge.acp.client;

import com.github.catatafishen.agentbridge.agent.AbstractAgentClient.AgentMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectAgentScannerDirectoryScanTest {

    @TempDir
    Path tempDir;

    @Test
    void scanAgentDirectoriesPrefersEarlierDirsAndExcludesBuiltIns() throws Exception {
        Files.createDirectories(tempDir.resolve("custom-primary"));
        Files.createDirectories(tempDir.resolve("custom-secondary"));
        Files.writeString(
            tempDir.resolve("custom-primary/shared.md"),
            "---\nname: Primary\n---\n",
            StandardCharsets.UTF_8
        );
        Files.writeString(
            tempDir.resolve("custom-secondary/shared.md"),
            "---\nname: Secondary\n---\n",
            StandardCharsets.UTF_8
        );
        Files.writeString(
            tempDir.resolve("custom-secondary/extra.md"),
            "plain markdown agent",
            StandardCharsets.UTF_8
        );
        Files.writeString(
            tempDir.resolve("custom-secondary/build.md"),
            "---\nname: Build Shadow\n---\n",
            StandardCharsets.UTF_8
        );

        List<AgentMode> agents = ProjectAgentScanner.scanAgentDirectories(
            tempDir,
            Set.of("build"),
            "custom-primary",
            "custom-secondary"
        );

        assertEquals(List.of("shared", "extra"), agents.stream().map(AgentMode::slug).toList());
        assertEquals("Primary", agents.get(0).name());
        assertEquals("extra", agents.get(1).name());
    }
}
