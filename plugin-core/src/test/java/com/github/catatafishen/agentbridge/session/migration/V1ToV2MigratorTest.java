package com.github.catatafishen.agentbridge.session.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link V1ToV2Migrator}.
 */
class V1ToV2MigratorTest {

    @TempDir
    Path projectRoot;

    // ── no-op when index already exists ──────────────────────────────────────

    @Test
    void doesNotMigrateWhenIndexAlreadyExists() throws IOException {
        Path sessionsDir = projectRoot.resolve(".agent-work/sessions");
        Files.createDirectories(sessionsDir);
        Path indexFile = sessionsDir.resolve("sessions-index.json");
        Files.writeString(indexFile, "[{\"id\":\"existing\"}]", StandardCharsets.UTF_8);

        V1ToV2Migrator.migrateIfNeeded(projectRoot.toString());

        assertEquals("[{\"id\":\"existing\"}]",
            Files.readString(indexFile, StandardCharsets.UTF_8),
            "index must not be overwritten when migration already ran");
    }

    // ── nothing to migrate (no conversation.json) ─────────────────────────────

    @Test
    void writesEmptyIndexWhenNoV1DataExists() {
        V1ToV2Migrator.migrateIfNeeded(projectRoot.toString());

        Path indexFile = projectRoot.resolve(".agent-work/sessions/sessions-index.json");
        assertTrue(indexFile.toFile().exists(), "empty index must be created");
        assertIndexEquals(indexFile);
    }

    @Test
    void writesEmptyIndexWhenConversationJsonIsEmpty() throws IOException {
        writeConversationJson("   ");

        V1ToV2Migrator.migrateIfNeeded(projectRoot.toString());

        assertIndexEquals(projectRoot.resolve(".agent-work/sessions/sessions-index.json"));
    }

    @Test
    void writesEmptyIndexWhenConversationJsonHasEmptyArray() throws IOException {
        writeConversationJson("[]");

        V1ToV2Migrator.migrateIfNeeded(projectRoot.toString());

        assertIndexEquals(projectRoot.resolve(".agent-work/sessions/sessions-index.json"));
    }

    // ── null basePath ─────────────────────────────────────────────────────────

    @Test
    void handlesNullBasePathWithoutException() {
        // Must not throw; may write an empty index relative to working directory.
        // We only verify it doesn't throw — the exact path is environment-dependent.
        V1ToV2Migrator.migrateIfNeeded(null);
        assertTrue(true); // assertion confirms no exception was thrown
    }

    // ── successful single-session migration ───────────────────────────────────

    @Test
    void migratesSingleSessionFromV1Json() throws IOException {
        writeConversationJson(singleSessionV1Json());

        V1ToV2Migrator.migrateIfNeeded(projectRoot.toString());

        Path sessionsDir = projectRoot.resolve(".agent-work/sessions");
        Path indexFile = sessionsDir.resolve("sessions-index.json");
        assertTrue(indexFile.toFile().exists());

        String indexContent = Files.readString(indexFile, StandardCharsets.UTF_8);
        assertTrue(indexContent.contains("\"agent\":\"GitHub Copilot\""),
            "index must contain agent field");
        assertTrue(indexContent.contains("\"directory\":\"" + projectRoot + "\""),
            "index must contain directory field");

        File[] jsonlFiles = sessionsDir.toFile().listFiles((d, n) -> n.endsWith(".jsonl"));
        assertNotNull(jsonlFiles, "sessions directory must contain JSONL files");
        assertEquals(1, jsonlFiles.length, "one JSONL file per session");

        String jsonlContent = Files.readString(jsonlFiles[0].toPath(), StandardCharsets.UTF_8);
        assertTrue(jsonlContent.contains("\"prompt\""), "JSONL must contain serialized prompt entry");

        Path currentIdFile = sessionsDir.resolve(".current-session-id");
        assertTrue(currentIdFile.toFile().exists(), ".current-session-id must be written");
    }

    // ── two-session migration with separator ─────────────────────────────────

    @Test
    void migratesTwoSessionsSplitBySeparator() throws IOException {
        writeConversationJson(twoSessionsV1Json());

        V1ToV2Migrator.migrateIfNeeded(projectRoot.toString());

        Path sessionsDir = projectRoot.resolve(".agent-work/sessions");
        File[] jsonlFiles = sessionsDir.toFile().listFiles((d, n) -> n.endsWith(".jsonl"));
        assertNotNull(jsonlFiles, "sessions directory must contain JSONL files");
        assertEquals(2, jsonlFiles.length, "two sessions must produce two JSONL files");

        String indexContent = Files.readString(sessionsDir.resolve("sessions-index.json"), StandardCharsets.UTF_8);
        assertTrue(indexContent.contains("jsonlPath"), "index must include jsonlPath entries");
    }

    // ── archive fallback ──────────────────────────────────────────────────────

    @Test
    void fallsBackToMostRecentArchiveWhenPrimaryMissing() throws IOException {
        Path archivesDir = projectRoot.resolve(".agent-work/conversations");
        Files.createDirectories(archivesDir);
        Files.writeString(
            archivesDir.resolve("conversation-2024-01-01.json"),
            singleSessionV1Json(),
            StandardCharsets.UTF_8);

        V1ToV2Migrator.migrateIfNeeded(projectRoot.toString());

        Path indexFile = projectRoot.resolve(".agent-work/sessions/sessions-index.json");
        assertTrue(indexFile.toFile().exists());
        String indexContent = Files.readString(indexFile, StandardCharsets.UTF_8);
        assertNotEquals("[]", indexContent, "archive must have been migrated, not treated as empty");
    }

    // ── idempotence ───────────────────────────────────────────────────────────

    @Test
    void callingTwiceIsIdempotent() throws IOException {
        writeConversationJson(singleSessionV1Json());

        V1ToV2Migrator.migrateIfNeeded(projectRoot.toString());
        V1ToV2Migrator.migrateIfNeeded(projectRoot.toString());

        Path sessionsDir = projectRoot.resolve(".agent-work/sessions");
        File[] jsonlFiles = sessionsDir.toFile().listFiles((d, n) -> n.endsWith(".jsonl"));
        assertNotNull(jsonlFiles, "sessions directory must contain JSONL files");
        assertEquals(1, jsonlFiles.length, "second migration call must not duplicate sessions");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void writeConversationJson(String content) throws IOException {
        Path agentWork = projectRoot.resolve(".agent-work");
        Files.createDirectories(agentWork);
        Files.writeString(agentWork.resolve("conversation.json"), content, StandardCharsets.UTF_8);
    }

    private static void assertIndexEquals(Path indexFile) {
        try {
            assertEquals("[]", Files.readString(indexFile, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String singleSessionV1Json() {
        return "[" +
            "{\"type\":\"prompt\",\"text\":\"hello\",\"ts\":\"2024-01-01\",\"id\":\"p1\"}," +
            "{\"type\":\"text\",\"raw\":\"world\",\"ts\":\"2024-01-01\",\"agent\":\"Copilot\"}" +
            "]";
    }

    private static String twoSessionsV1Json() {
        return "[" +
            "{\"type\":\"prompt\",\"text\":\"session1\",\"ts\":\"2024-01-01\",\"id\":\"p1\"}," +
            "{\"type\":\"separator\",\"timestamp\":\"2024-01-02\",\"agent\":\"Copilot\"}," +
            "{\"type\":\"prompt\",\"text\":\"session2\",\"ts\":\"2024-01-03\",\"id\":\"p2\"}" +
            "]";
    }
}
