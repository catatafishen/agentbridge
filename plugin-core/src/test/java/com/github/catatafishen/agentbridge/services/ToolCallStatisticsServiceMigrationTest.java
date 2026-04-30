package com.github.catatafishen.agentbridge.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ToolCallStatisticsService#migrateLegacyDb(String, Path)} —
 * exercises the one-shot migration that moves
 * {@code {project}/.agentbridge/tool-stats.db} to the new user-configurable
 * storage location (issue #351).
 */
class ToolCallStatisticsServiceMigrationTest {

    @TempDir
    Path tempDir;

    @Test
    void movesLegacyDbToNewLocationWhenNewMissing() throws IOException {
        Path projectRoot = tempDir.resolve("project");
        Path legacyDir = projectRoot.resolve(".agentbridge");
        Files.createDirectories(legacyDir);
        Path legacyDb = legacyDir.resolve("tool-stats.db");
        byte[] payload = "fake-sqlite-bytes".getBytes();
        Files.write(legacyDb, payload);

        Path newDb = tempDir.resolve("new-loc").resolve("tool-stats.db");

        ToolCallStatisticsService.migrateLegacyDb(projectRoot.toString(), newDb);

        assertFalse(Files.exists(legacyDb), "legacy DB should be moved");
        assertTrue(Files.exists(newDb), "new DB should exist");
        assertArrayEquals(payload, Files.readAllBytes(newDb), "payload preserved");
        assertFalse(Files.exists(legacyDir), "empty legacy dir should be cleaned up");
    }

    @Test
    void doesNothingWhenNewDbAlreadyExists() throws IOException {
        Path projectRoot = tempDir.resolve("project");
        Path legacyDir = projectRoot.resolve(".agentbridge");
        Files.createDirectories(legacyDir);
        Path legacyDb = legacyDir.resolve("tool-stats.db");
        Files.write(legacyDb, "legacy".getBytes());

        Path newDb = tempDir.resolve("new-loc").resolve("tool-stats.db");
        Files.createDirectories(newDb.getParent());
        Files.write(newDb, "existing".getBytes());

        ToolCallStatisticsService.migrateLegacyDb(projectRoot.toString(), newDb);

        // Both files remain — migration did not touch them
        assertTrue(Files.exists(legacyDb));
        assertArrayEquals("legacy".getBytes(), Files.readAllBytes(legacyDb));
        assertArrayEquals("existing".getBytes(), Files.readAllBytes(newDb));
    }

    @Test
    void noOpWhenLegacyDbMissing() {
        Path projectRoot = tempDir.resolve("project");
        Path newDb = tempDir.resolve("new-loc").resolve("tool-stats.db");

        // Should not throw, should not create anything
        ToolCallStatisticsService.migrateLegacyDb(projectRoot.toString(), newDb);

        assertFalse(Files.exists(newDb));
    }

    @Test
    void migratesSidecarFilesAlongWithDb() throws IOException {
        Path projectRoot = tempDir.resolve("project");
        Path legacyDir = projectRoot.resolve(".agentbridge");
        Files.createDirectories(legacyDir);
        Files.write(legacyDir.resolve("tool-stats.db"), "db".getBytes());
        Files.write(legacyDir.resolve("tool-stats.db-journal"), "j".getBytes());
        Files.write(legacyDir.resolve("tool-stats.db-wal"), "w".getBytes());

        Path newDb = tempDir.resolve("new-loc").resolve("tool-stats.db");

        ToolCallStatisticsService.migrateLegacyDb(projectRoot.toString(), newDb);

        assertTrue(Files.exists(newDb));
        assertTrue(Files.exists(Path.of(newDb + "-journal")));
        assertTrue(Files.exists(Path.of(newDb + "-wal")));
    }
}
