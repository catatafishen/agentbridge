package com.github.catatafishen.agentbridge.services.hooks;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link HookHashRegistry} — hash computation, file status, and property parsing.
 */
class HookHashRegistryTest {

    @Nested
    class ComputeStringHash {
        @Test
        void deterministicForSameInput() {
            String hash1 = HookHashRegistry.computeStringHash("hello world");
            String hash2 = HookHashRegistry.computeStringHash("hello world");
            assertEquals(hash1, hash2);
        }

        @Test
        void differentForDifferentInput() {
            String hash1 = HookHashRegistry.computeStringHash("hello");
            String hash2 = HookHashRegistry.computeStringHash("world");
            assertNotEquals(hash1, hash2);
        }

        @Test
        void isLowercaseHex() {
            String hash = HookHashRegistry.computeStringHash("test");
            assertTrue(hash.matches("[0-9a-f]+"), "Hash should be lowercase hex: " + hash);
        }

        @Test
        void is64CharsLong() {
            String hash = HookHashRegistry.computeStringHash("test");
            assertEquals(64, hash.length(), "SHA-256 hex should be 64 chars");
        }
    }

    @Nested
    class ComputeHash {
        @Test
        void matchesStringHash() {
            String content = "test content";
            InputStream in = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
            String streamHash = HookHashRegistry.computeHash(in);
            String stringHash = HookHashRegistry.computeStringHash(content);
            assertEquals(stringHash, streamHash);
        }

        @Test
        void emptyStreamHasHash() {
            InputStream in = new ByteArrayInputStream(new byte[0]);
            String hash = HookHashRegistry.computeHash(in);
            assertNotNull(hash);
            assertEquals(64, hash.length());
        }
    }

    @Nested
    class ComputeFileHash {
        @Test
        void returnsHashForExistingFile(@TempDir Path tempDir) throws IOException {
            Path file = tempDir.resolve("test.txt");
            Files.writeString(file, "file content");
            String hash = HookHashRegistry.computeFileHash(file);
            assertNotNull(hash);
            assertEquals(HookHashRegistry.computeStringHash("file content"), hash);
        }

        @Test
        void returnsNullForMissingFile(@TempDir Path tempDir) {
            assertNull(HookHashRegistry.computeFileHash(tempDir.resolve("nonexistent.txt")));
        }

        @Test
        void returnsNullForDirectory(@TempDir Path tempDir) {
            assertNull(HookHashRegistry.computeFileHash(tempDir));
        }
    }

    @Nested
    class IsOfficialHash {
        @Test
        void matchesCurrentBundledHash() {
            Map<String, String> bundled = Map.of("hook.sh", "abc123");
            assertTrue(HookHashRegistry.isOfficialHash("hook.sh", "abc123", bundled));
        }

        @Test
        void matchesHistoricalHash() {
            Map<String, String> bundled = Map.of(
                "hook.sh", "current",
                "hook.sh.history", "old1,old2,old3"
            );
            assertTrue(HookHashRegistry.isOfficialHash("hook.sh", "old2", bundled));
        }

        @Test
        void doesNotMatchUnknownHash() {
            Map<String, String> bundled = Map.of(
                "hook.sh", "current",
                "hook.sh.history", "old1,old2"
            );
            assertFalse(HookHashRegistry.isOfficialHash("hook.sh", "unknown", bundled));
        }

        @Test
        void noHistoryDoesNotMatch() {
            Map<String, String> bundled = Map.of("hook.sh", "current");
            assertFalse(HookHashRegistry.isOfficialHash("hook.sh", "other", bundled));
        }

        @Test
        void missingFileDoesNotMatch() {
            Map<String, String> bundled = Map.of("other.sh", "abc");
            assertFalse(HookHashRegistry.isOfficialHash("hook.sh", "abc", bundled));
        }

        @Test
        void handlesWhitespaceInHistory() {
            Map<String, String> bundled = Map.of(
                "hook.sh", "current",
                "hook.sh.history", " old1 , old2 "
            );
            assertTrue(HookHashRegistry.isOfficialHash("hook.sh", "old1", bundled));
        }
    }

    @Nested
    class SaveAndLoad {
        @Test
        void roundTrip(@TempDir Path tempDir) {
            Map<String, String> hashes = new HashMap<>();
            hashes.put("script1.sh", "hash1");
            hashes.put("script2.sh", "hash2");

            HookHashRegistry.save(tempDir, hashes);
            Map<String, String> loaded = HookHashRegistry.load(tempDir);

            assertEquals("hash1", loaded.get("script1.sh"));
            assertEquals("hash2", loaded.get("script2.sh"));
        }

        @Test
        void loadFromMissingDirReturnsEmpty(@TempDir Path tempDir) {
            Map<String, String> loaded = HookHashRegistry.load(tempDir.resolve("nonexistent"));
            assertTrue(loaded.isEmpty());
        }

        @Test
        void saveCreatesFile(@TempDir Path tempDir) {
            HookHashRegistry.save(tempDir, Map.of("a", "b"));
            assertTrue(Files.exists(tempDir.resolve(HookHashRegistry.HASH_FILE)));
        }

        @Test
        void savedFileHasComment(@TempDir Path tempDir) throws IOException {
            HookHashRegistry.save(tempDir, Map.of("a", "b"));
            String content = Files.readString(tempDir.resolve(HookHashRegistry.HASH_FILE));
            assertTrue(content.startsWith("#"), "Should start with comment");
        }
    }

    @Nested
    class Exists {
        @Test
        void falseWhenNoFile(@TempDir Path tempDir) {
            assertFalse(HookHashRegistry.exists(tempDir));
        }

        @Test
        void trueWhenFileExists(@TempDir Path tempDir) {
            HookHashRegistry.save(tempDir, Map.of("a", "b"));
            assertTrue(HookHashRegistry.exists(tempDir));
        }
    }

    @Nested
    class ComputeFileStatus {
        @Test
        void upToDateWhenMatchesBundled(@TempDir Path tempDir) throws IOException {
            String content = "#!/bin/bash\necho hello\n";
            Path hookFile = tempDir.resolve("hook.sh");
            Files.writeString(hookFile, content);
            HookHashRegistry.save(tempDir, Map.of("hook.sh", HookHashRegistry.computeStringHash(content)));

            Map<String, String> bundled = Map.of("hook.sh", HookHashRegistry.computeStringHash(content));
            HookHashRegistry.FileStatus status = HookHashRegistry.computeFileStatus("hook.sh", tempDir,
                HookHashRegistry.load(tempDir), bundled);
            assertEquals(HookHashRegistry.FileStatus.UP_TO_DATE, status);
        }

        @Test
        void missingWhenFileAbsent(@TempDir Path tempDir) {
            HookHashRegistry.save(tempDir, Map.of());
            Map<String, String> bundled = Map.of("hook.sh", "somehash");
            HookHashRegistry.FileStatus status = HookHashRegistry.computeFileStatus("hook.sh", tempDir,
                HookHashRegistry.load(tempDir), bundled);
            assertEquals(HookHashRegistry.FileStatus.MISSING, status);
        }

        @Test
        void modifiedWhenHashDiffers(@TempDir Path tempDir) throws IOException {
            Path hookFile = tempDir.resolve("hook.sh");
            Files.writeString(hookFile, "modified content");
            HookHashRegistry.save(tempDir, Map.of("hook.sh", "original-hash"));

            Map<String, String> bundled = Map.of("hook.sh", "bundled-hash");
            HookHashRegistry.FileStatus status = HookHashRegistry.computeFileStatus("hook.sh", tempDir,
                HookHashRegistry.load(tempDir), bundled);
            assertEquals(HookHashRegistry.FileStatus.MODIFIED, status);
        }

        @Test
        void unknownWhenNoHashRegistry(@TempDir Path tempDir) throws IOException {
            // No hash file saved, but the file exists
            Path hookFile = tempDir.resolve("hook.sh");
            Files.writeString(hookFile, "content");
            Map<String, String> bundled = Map.of("hook.sh", "somehash");
            HookHashRegistry.FileStatus status = HookHashRegistry.computeFileStatus("hook.sh", tempDir,
                Map.of(), bundled);
            assertEquals(HookHashRegistry.FileStatus.UNKNOWN, status);
        }
    }

    @Nested
    class FileStatusEnum {
        @Test
        void upToDateCannotRevert() {
            assertFalse(HookHashRegistry.FileStatus.UP_TO_DATE.canRevert());
        }

        @Test
        void modifiedCanRevert() {
            assertTrue(HookHashRegistry.FileStatus.MODIFIED.canRevert());
        }

        @Test
        void missingCanRevert() {
            assertTrue(HookHashRegistry.FileStatus.MISSING.canRevert());
        }

        @Test
        void officialCanRevert() {
            assertTrue(HookHashRegistry.FileStatus.OFFICIAL.canRevert());
        }

        @Test
        void unknownCanRevert() {
            assertTrue(HookHashRegistry.FileStatus.UNKNOWN.canRevert());
        }

        @Test
        void labelsAreSet() {
            assertEquals("Up to date", HookHashRegistry.FileStatus.UP_TO_DATE.label);
            assertEquals("Modified", HookHashRegistry.FileStatus.MODIFIED.label);
            assertEquals("Missing", HookHashRegistry.FileStatus.MISSING.label);
        }
    }
}
