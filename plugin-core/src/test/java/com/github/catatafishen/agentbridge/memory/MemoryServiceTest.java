package com.github.catatafishen.agentbridge.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void migrateLegacyMemoryDirMovesProjectAgentWorkMemoryIntoStorageRoot() throws IOException {
        Path projectBase = tempDir.resolve("project");
        Path legacyMemoryDir = projectBase.resolve(".agent-work").resolve("memory");
        Files.createDirectories(legacyMemoryDir);
        Files.writeString(legacyMemoryDir.resolve("identity.txt"), "identity", StandardCharsets.UTF_8);

        Path newMemoryDir = tempDir.resolve("storage").resolve("projects").resolve("demo-123").resolve("memory");
        MemoryService.migrateLegacyMemoryDir(projectBase.toString(), newMemoryDir);

        assertFalse(Files.exists(legacyMemoryDir), "Legacy memory directory should be moved");
        assertEquals("identity",
            Files.readString(newMemoryDir.resolve("identity.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void migrateLegacyMemoryDirLeavesExistingNewLocationUntouched() throws IOException {
        Path projectBase = tempDir.resolve("project");
        Path legacyMemoryDir = projectBase.resolve(".agent-work").resolve("memory");
        Files.createDirectories(legacyMemoryDir);
        Files.writeString(legacyMemoryDir.resolve("identity.txt"), "legacy", StandardCharsets.UTF_8);

        Path newMemoryDir = tempDir.resolve("storage").resolve("projects").resolve("demo-123").resolve("memory");
        Files.createDirectories(newMemoryDir);
        Files.writeString(newMemoryDir.resolve("identity.txt"), "new", StandardCharsets.UTF_8);

        MemoryService.migrateLegacyMemoryDir(projectBase.toString(), newMemoryDir);

        assertTrue(Files.exists(legacyMemoryDir.resolve("identity.txt")),
            "Legacy directory should remain when the new location already exists");
        assertEquals("new",
            Files.readString(newMemoryDir.resolve("identity.txt"), StandardCharsets.UTF_8));
    }
}
