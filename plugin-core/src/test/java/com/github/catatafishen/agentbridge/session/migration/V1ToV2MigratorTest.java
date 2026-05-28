package com.github.catatafishen.agentbridge.session.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link V1ToV2Migrator}.
 *
 * <p>These tests target the package-private
 * {@link V1ToV2Migrator#migrateIfNeeded(String, File)} overload so the destination
 * sessions directory can be controlled directly. The production
 * {@link V1ToV2Migrator#migrateIfNeeded(com.intellij.openapi.project.Project)}
 * resolves the destination from
 * {@link com.github.catatafishen.agentbridge.settings.AgentBridgeStorageSettings} —
 * verified in fixture-backed integration tests.</p>
 */
class V1ToV2MigratorTest {

    @TempDir
    Path projectRoot;

    @TempDir
    Path storageRoot;

    private File sessionsDir() {
        return storageRoot.resolve("sessions").toFile();
    }

    @Test
    void doesNotMigrateWhenIndexAlreadyExists() throws IOException {
        File sessionsDir = sessionsDir();
        Files.createDirectories(sessionsDir.toPath());
        Path indexFile = sessionsDir.toPath().resolve("sessions-index.json");
        Files.writeString(indexFile, "[{\"id\":\"existing\"}]", StandardCharsets.UTF_8);

        V1ToV2Migrator.migrateIfNeeded(projectRoot.toString(), sessionsDir);

        assertEquals("[{\"id\":\"existing\"}]",
            Files.readString(indexFile, StandardCharsets.UTF_8),
            "index must not be overwritten when migration already ran");
    }

    @Test
    void doesNothingWhenNoV1DataExists() {
        V1ToV2Migrator.migrateIfNeeded(projectRoot.toString(), sessionsDir());

        Path indexFile = sessionsDir().toPath().resolve("sessions-index.json");
        assertFalse(indexFile.toFile().exists(), "no sentinel file should be created on fresh installs");
    }

    @Test
    void doesNothingWhenConversationJsonIsEmpty() throws IOException {
        writeConversationJson("   ");

        V1ToV2Migrator.migrateIfNeeded(projectRoot.toString(), sessionsDir());

        Path indexFile = sessionsDir().toPath().resolve("sessions-index.json");
        assertFalse(indexFile.toFile().exists(), "no sentinel file should be created for empty/tiny v1 files");
    }

    @Test
    void doesNothingWhenConversationJsonHasEmptyArray() throws IOException {
        writeConversationJson("[]");

        V1ToV2Migrator.migrateIfNeeded(projectRoot.toString(), sessionsDir());

        Path indexFile = sessionsDir().toPath().resolve("sessions-index.json");
        assertFalse(indexFile.toFile().exists(), "no sentinel file should be created for empty v1 array");
    }

    @Test
    void handlesNullBasePathWithoutException() {
        // Must not throw; with no basePath there is no v1 data to migrate.
        V1ToV2Migrator.migrateIfNeeded(null, sessionsDir());
        assertFalse(sessionsDir().toPath().resolve("sessions-index.json").toFile().exists(),
            "no index when there is no v1 data");
    }

    @Test
    void migratesSingleSessionFromV1Json() throws IOException {
        writeConversationJson(singleSessionV1Json());

        V1ToV2Migrator.migrateIfNeeded(projectRoot.toString(), sessionsDir());

        File sessionsDir = sessionsDir();
        Path indexFile = sessionsDir.toPath().resolve("sessions-index.json");
        assertTrue(indexFile.toFile().exists());

        String indexContent = Files.readString(indexFile, StandardCharsets.UTF_8);
        assertTrue(indexContent.contains("\"agent\":\"GitHub Copilot\""),
            "index must contain agent field");
        assertTrue(indexContent.contains("\"directory\":\"" + projectRoot + "\""),
            "index must contain directory field");

        File[] jsonlFiles = sessionsDir.listFiles((d, n) -> n.endsWith(".jsonl"));
        assertNotNull(jsonlFiles, "sessions directory must contain JSONL files");
        assertEquals(1, jsonlFiles.length, "one JSONL file per session");

        String jsonlContent = Files.readString(jsonlFiles[0].toPath(), StandardCharsets.UTF_8);
        assertTrue(jsonlContent.contains("\"prompt\""), "JSONL must contain serialized prompt entry");

        Path currentIdFile = sessionsDir.toPath().resolve(".current-session-id");
        assertTrue(currentIdFile.toFile().exists(), ".current-session-id must be written");
    }

    @Test
    void migratesTwoSessionsSplitBySeparator() throws IOException {
        writeConversationJson(twoSessionsV1Json());

        V1ToV2Migrator.migrateIfNeeded(projectRoot.toString(), sessionsDir());

        File sessionsDir = sessionsDir();
        File[] jsonlFiles = sessionsDir.listFiles((d, n) -> n.endsWith(".jsonl"));
        assertNotNull(jsonlFiles, "sessions directory must contain JSONL files");
        assertEquals(2, jsonlFiles.length, "two sessions must produce two JSONL files");

        String indexContent = Files.readString(sessionsDir.toPath().resolve("sessions-index.json"),
            StandardCharsets.UTF_8);
        assertTrue(indexContent.contains("jsonlPath"), "index must include jsonlPath entries");
    }

    @Test
    void fallsBackToMostRecentArchiveWhenPrimaryMissing() throws IOException {
        Path archivesDir = projectRoot.resolve(".agent-work/conversations");
        Files.createDirectories(archivesDir);
        Files.writeString(
            archivesDir.resolve("conversation-2024-01-01.json"),
            singleSessionV1Json(),
            StandardCharsets.UTF_8);

        V1ToV2Migrator.migrateIfNeeded(projectRoot.toString(), sessionsDir());

        Path indexFile = sessionsDir().toPath().resolve("sessions-index.json");
        assertTrue(indexFile.toFile().exists());
        String indexContent = Files.readString(indexFile, StandardCharsets.UTF_8);
        assertNotEquals("[]", indexContent, "archive must have been migrated, not treated as empty");
    }

    @Test
    void callingTwiceIsIdempotent() throws IOException {
        writeConversationJson(singleSessionV1Json());

        V1ToV2Migrator.migrateIfNeeded(projectRoot.toString(), sessionsDir());
        V1ToV2Migrator.migrateIfNeeded(projectRoot.toString(), sessionsDir());

        File[] jsonlFiles = sessionsDir().listFiles((d, n) -> n.endsWith(".jsonl"));
        assertNotNull(jsonlFiles, "sessions directory must contain JSONL files");
        assertEquals(1, jsonlFiles.length, "second migration call must not duplicate sessions");
    }

    private void writeConversationJson(String content) throws IOException {
        Path agentWork = projectRoot.resolve(".agent-work");
        Files.createDirectories(agentWork);
        Files.writeString(agentWork.resolve("conversation.json"), content, StandardCharsets.UTF_8);
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
