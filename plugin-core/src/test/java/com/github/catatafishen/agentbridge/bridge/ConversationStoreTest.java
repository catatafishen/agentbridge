package com.github.catatafishen.agentbridge.bridge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ConversationStore")
class ConversationStoreTest {

    private final ConversationStore store = new ConversationStore();

    @Nested
    @DisplayName("conversationFile")
    class ConversationFile {

        @Test
        @DisplayName("returns correct path under .agent-work")
        void returnsCorrectPath(@TempDir Path tempDir) {
            File file = store.conversationFile(tempDir.toString());
            assertEquals(
                tempDir.resolve(".agent-work").resolve("conversation.json").toFile(),
                file
            );
        }

        @Test
        @DisplayName("creates .agent-work directory")
        void createsAgentWorkDir(@TempDir Path tempDir) {
            store.conversationFile(tempDir.toString());
            assertTrue(tempDir.resolve(".agent-work").toFile().isDirectory());
        }
    }

    @Nested
    @DisplayName("archivesDir")
    class ArchivesDir {

        @Test
        @DisplayName("returns correct path under .agent-work/conversations")
        void returnsCorrectPath(@TempDir Path tempDir) {
            File dir = store.archivesDir(tempDir.toString());
            assertEquals(
                tempDir.resolve(".agent-work").resolve("conversations").toFile(),
                dir
            );
        }
    }

    @Nested
    @DisplayName("loadJson")
    class LoadJson {

        @Test
        @DisplayName("returns null when no files exist")
        void noFilesExist(@TempDir Path tempDir) {
            assertNull(store.loadJson(tempDir.toString()));
        }

        @Test
        @DisplayName("returns content when primary file exists with valid size")
        void primaryFileValid(@TempDir Path tempDir) throws IOException {
            String content = "{\"turns\": []}";
            writePrimary(tempDir, content);
            assertEquals(content, store.loadJson(tempDir.toString()));
        }

        @Test
        @DisplayName("primary file too small falls back (returns null if no archive)")
        void primaryTooSmallNoArchive(@TempDir Path tempDir) throws IOException {
            writePrimary(tempDir, "tiny");
            assertNull(store.loadJson(tempDir.toString()));
        }

        @Test
        @DisplayName("primary too small, archive exists → returns archive content")
        void primaryTooSmallWithArchive(@TempDir Path tempDir) throws IOException {
            writePrimary(tempDir, "tiny");
            String archiveContent = "{\"archived\": true}";
            writeArchive(tempDir, "conversation-2024-01-01T12-00-00.json", archiveContent);
            assertEquals(archiveContent, store.loadJson(tempDir.toString()));
        }

        @Test
        @DisplayName("no primary, multiple archives → returns latest by name sort")
        void multipleArchivesReturnsLatest(@TempDir Path tempDir) throws IOException {
            String older = "{\"version\": 1}";
            String newer = "{\"version\": 2}";
            writeArchive(tempDir, "conversation-2024-01-01T10-00-00.json", older);
            writeArchive(tempDir, "conversation-2024-06-15T18-30-00.json", newer);
            assertEquals(newer, store.loadJson(tempDir.toString()));
        }

        @Test
        @DisplayName("no primary, archive too small → returns null")
        void archiveTooSmall(@TempDir Path tempDir) throws IOException {
            writeArchive(tempDir, "conversation-2024-01-01T12-00-00.json", "tiny");
            assertNull(store.loadJson(tempDir.toString()));
        }
    }

    @Nested
    @DisplayName("archive")
    class Archive {

        @Test
        @DisplayName("moves file to archive directory")
        void movesFileToArchive(@TempDir Path tempDir) throws IOException {
            String content = "{\"turns\": []}";
            writePrimary(tempDir, content);
            store.archive(tempDir.toString());
            File archivesDir = store.archivesDir(tempDir.toString());
            assertTrue(archivesDir.isDirectory());
            File[] files = archivesDir.listFiles();
            assertNotNull(files);
            assertEquals(1, files.length);
            String archived = Files.readString(files[0].toPath(), StandardCharsets.UTF_8);
            assertEquals(content, archived);
        }

        @Test
        @DisplayName("no-op when file doesn't exist")
        void noOpWhenMissing(@TempDir Path tempDir) {
            store.archive(tempDir.toString());
            File archivesDir = store.archivesDir(tempDir.toString());
            assertFalse(archivesDir.exists());
        }

        @Test
        @DisplayName("no-op when file too small")
        void noOpWhenTooSmall(@TempDir Path tempDir) throws IOException {
            writePrimary(tempDir, "tiny");
            store.archive(tempDir.toString());
            File archivesDir = store.archivesDir(tempDir.toString());
            assertFalse(archivesDir.exists());
        }

        @Test
        @DisplayName("creates archive directory if needed")
        void createsArchiveDir(@TempDir Path tempDir) throws IOException {
            writePrimary(tempDir, "{\"turns\": []}");
            assertFalse(store.archivesDir(tempDir.toString()).exists());
            store.archive(tempDir.toString());
            assertTrue(store.archivesDir(tempDir.toString()).isDirectory());
        }

        @Test
        @DisplayName("after archive, primary file is gone")
        void primaryGoneAfterArchive(@TempDir Path tempDir) throws IOException {
            writePrimary(tempDir, "{\"turns\": []}");
            store.archive(tempDir.toString());
            assertFalse(store.conversationFile(tempDir.toString()).exists());
        }
    }

    // ---- helpers ----

    private void writePrimary(Path tempDir, String content) throws IOException {
        Path agentWork = tempDir.resolve(".agent-work");
        Files.createDirectories(agentWork);
        Files.writeString(agentWork.resolve("conversation.json"), content, StandardCharsets.UTF_8);
    }

    private void writeArchive(Path tempDir, String filename, String content) throws IOException {
        Path archivesPath = tempDir.resolve(".agent-work").resolve("conversations");
        Files.createDirectories(archivesPath);
        Files.writeString(archivesPath.resolve(filename), content, StandardCharsets.UTF_8);
    }
}
