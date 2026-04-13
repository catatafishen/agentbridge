package com.github.catatafishen.agentbridge.session;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for pure static methods in {@link SessionSwitchService}.
 * Uses reflection since the methods are private.
 */
class SessionSwitchServiceStaticMethodsTest {

    // ── claudeProjectDir ───────────────────────────────────

    @Test
    void claudeProjectDir_normalPath_containsExpectedComponents() throws Exception {
        Path result = invokeClaudeProjectDir("/home/user/projects/myapp");
        assertTrue(result.endsWith(Path.of(".claude", "projects", "-home-user-projects-myapp")),
            "Expected path ending with .claude/projects/-home-user-projects-myapp but was: " + result);
    }

    @Test
    void claudeProjectDir_nullBasePath_usesEmptyDirName() throws Exception {
        Path result = invokeClaudeProjectDir(null);
        // null basePath → projectPath="" → dirName="" → Path.of ignores empty trailing component
        String lastComponent = result.getFileName().toString();
        assertEquals("projects", lastComponent,
            "With null basePath, empty dirName is ignored; last component should be 'projects'");
    }

    @Test
    void claudeProjectDir_multipleSegments_allSlashesReplaced() throws Exception {
        Path result = invokeClaudeProjectDir("/a/b/c/d");
        assertTrue(result.endsWith(Path.of(".claude", "projects", "-a-b-c-d")),
            "All forward slashes should be replaced by dashes: " + result);
    }

    @Test
    void claudeProjectDir_rootPath_dirNameIsDash() throws Exception {
        Path result = invokeClaudeProjectDir("/");
        assertTrue(result.endsWith(Path.of(".claude", "projects", "-")),
            "Root path '/' should produce dirName '-': " + result);
    }

    @Test
    void claudeProjectDir_emptyString_sameAsNull() throws Exception {
        Path result = invokeClaudeProjectDir("");
        String lastComponent = result.getFileName().toString();
        assertEquals("projects", lastComponent,
            "Empty string basePath should behave like null (empty dirName ignored)");
    }

    @Test
    void claudeProjectDir_trailingSlash_preservedAsDash() throws Exception {
        Path result = invokeClaudeProjectDir("/home/user/");
        // "/home/user/" → "-home-user-"
        assertEquals("-home-user-", result.getFileName().toString(),
            "Trailing slash should produce trailing dash in dirName");
    }

    @Test
    void claudeProjectDir_startsWithUserHome() throws Exception {
        Path result = invokeClaudeProjectDir("/any/path");
        String userHome = System.getProperty("user.home", "");
        assertTrue(result.startsWith(userHome),
            "Path should start with user.home (" + userHome + "): " + result);
    }

    @Test
    void claudeProjectDir_consecutiveSlashes_producesDoubleDashes() throws Exception {
        Path result = invokeClaudeProjectDir("/home//user");
        assertEquals("-home--user", result.getFileName().toString(),
            "Consecutive slashes should produce consecutive dashes");
    }

    @Test
    void claudeProjectDir_relativePath_noDashPrefix() throws Exception {
        Path result = invokeClaudeProjectDir("relative/path");
        assertEquals("relative-path", result.getFileName().toString(),
            "Relative path without leading slash should not have dash prefix");
    }

    // ── readAndConsumeClaudeResumeIdFile ──────────────────

    @Nested
    class ReadAndConsumeClaudeResumeIdFileTest {

        private static final String RESUME_FILE = "claude-resume-id.txt";

        private Path resumeFile(Path tempDir) {
            return tempDir.resolve(".agent-work/sessions/" + RESUME_FILE);
        }

        private void writeResumeFile(Path tempDir, String content) throws IOException {
            Path file = resumeFile(tempDir);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        }

        @Test
        void fileExists_returnsContentAndDeletesFile(@TempDir Path tempDir) throws IOException {
            writeResumeFile(tempDir, "sess-abc-123");

            String result = SessionSwitchService.readAndConsumeClaudeResumeIdFile(tempDir.toString());

            assertEquals("sess-abc-123", result);
            assertFalse(Files.exists(resumeFile(tempDir)),
                "Resume file should be deleted after consumption");
        }

        @Test
        void fileDoesNotExist_returnsNull(@TempDir Path tempDir) {
            String result = SessionSwitchService.readAndConsumeClaudeResumeIdFile(tempDir.toString());

            assertNull(result, "Should return null when resume file does not exist");
        }

        @Test
        void nullBasePath_returnsNull() throws IOException {
            // null basePath → sessionsDir returns File(".agent-work/sessions") (relative)
            // Clean up any residual file left by other tests using the same relative path
            Path relativeResumeFile = Path.of(".agent-work/sessions", RESUME_FILE);
            Files.deleteIfExists(relativeResumeFile);

            String result = SessionSwitchService.readAndConsumeClaudeResumeIdFile(null);

            assertNull(result, "Should return null for null basePath (file won't exist)");
        }

        @Test
        void fileWithWhitespace_returnsTrimmedContent(@TempDir Path tempDir) throws IOException {
            writeResumeFile(tempDir, "  sess-trimmed  \n");

            String result = SessionSwitchService.readAndConsumeClaudeResumeIdFile(tempDir.toString());

            assertEquals("sess-trimmed", result,
                "Should return trimmed content");
        }

        @Test
        void afterConsuming_secondCallReturnsNull(@TempDir Path tempDir) throws IOException {
            writeResumeFile(tempDir, "sess-once");

            String first = SessionSwitchService.readAndConsumeClaudeResumeIdFile(tempDir.toString());
            assertEquals("sess-once", first);

            String second = SessionSwitchService.readAndConsumeClaudeResumeIdFile(tempDir.toString());
            assertNull(second,
                "Second call should return null because file was deleted by first call");
        }
    }

    // ── collectPlanFiles ───────────────────────────────────

    @Nested
    class CollectPlanFilesTest {

        @Test
        void emptyDirectory_returnsEmptyList(@TempDir Path tempDir) throws Exception {
            List<Path> result = new ArrayList<>();
            invokeCollectPlanFiles(tempDir, result);

            assertTrue(result.isEmpty(),
                "Empty directory should produce no plan files");
        }

        @Test
        void subdirWithPlanMd_collectsIt(@TempDir Path tempDir) throws Exception {
            Path session1 = tempDir.resolve("session-1");
            Files.createDirectories(session1);
            Files.writeString(session1.resolve("plan.md"), "# Plan A");

            Path session2 = tempDir.resolve("session-2");
            Files.createDirectories(session2);
            Files.writeString(session2.resolve("plan.md"), "# Plan B");

            List<Path> result = new ArrayList<>();
            invokeCollectPlanFiles(tempDir, result);

            assertEquals(2, result.size(), "Should find plan.md in both subdirectories");
            assertTrue(result.contains(session1.resolve("plan.md")));
            assertTrue(result.contains(session2.resolve("plan.md")));
        }

        @Test
        void nonExistingDirectory_doesNotThrow() throws Exception {
            Path nonExistent = Path.of("/tmp/non-existent-dir-" + System.nanoTime());
            List<Path> result = new ArrayList<>();

            assertDoesNotThrow(() -> invokeCollectPlanFiles(nonExistent, result),
                "Non-existing directory should not throw");
            assertTrue(result.isEmpty());
        }

        @Test
        void nestedSubdirectories_onlyCollectsDirectChildren(@TempDir Path tempDir) throws Exception {
            // collectPlanFiles only lists immediate children (Files.list, not walk)
            Path child = tempDir.resolve("child");
            Files.createDirectories(child);
            Files.writeString(child.resolve("plan.md"), "# Child plan");

            Path grandchild = tempDir.resolve("child/grandchild");
            Files.createDirectories(grandchild);
            Files.writeString(grandchild.resolve("plan.md"), "# Grandchild plan");

            List<Path> result = new ArrayList<>();
            invokeCollectPlanFiles(tempDir, result);

            assertEquals(1, result.size(),
                "Should only find plan.md in direct subdirectories, not nested ones");
            assertEquals(child.resolve("plan.md"), result.get(0));
        }

        @Test
        void subdirWithoutPlanMd_isSkipped(@TempDir Path tempDir) throws Exception {
            Path withPlan = tempDir.resolve("with-plan");
            Files.createDirectories(withPlan);
            Files.writeString(withPlan.resolve("plan.md"), "# Has plan");

            Path withoutPlan = tempDir.resolve("no-plan");
            Files.createDirectories(withoutPlan);
            Files.writeString(withoutPlan.resolve("other.txt"), "not a plan");

            List<Path> result = new ArrayList<>();
            invokeCollectPlanFiles(tempDir, result);

            assertEquals(1, result.size());
            assertEquals(withPlan.resolve("plan.md"), result.get(0));
        }
    }

    // ── writeClaudeResumeIdFile ────────────────────────────

    @Nested
    class WriteClaudeResumeIdFileTest {

        @Test
        void validBasePath_createsFileWithSessionId(@TempDir Path tempDir) throws Exception {
            invokeWriteClaudeResumeIdFile(tempDir.toString(), "sess-write-123");

            Path resumeFile = tempDir.resolve(".agent-work/sessions/claude-resume-id.txt");
            assertTrue(Files.exists(resumeFile), "Resume file should be created");
            assertEquals("sess-write-123",
                Files.readString(resumeFile, StandardCharsets.UTF_8));
        }

        @Test
        void nullBasePath_doesNotThrow() {
            // null basePath → creates file under relative ".agent-work/sessions"
            // should not throw even if that path is not writable or unexpected
            assertDoesNotThrow(
                () -> invokeWriteClaudeResumeIdFile(null, "sess-null-base"),
                "Null basePath should not throw");
        }

        @Test
        void overwritesExistingFile(@TempDir Path tempDir) throws Exception {
            invokeWriteClaudeResumeIdFile(tempDir.toString(), "first-id");
            invokeWriteClaudeResumeIdFile(tempDir.toString(), "second-id");

            Path resumeFile = tempDir.resolve(".agent-work/sessions/claude-resume-id.txt");
            assertEquals("second-id",
                Files.readString(resumeFile, StandardCharsets.UTF_8),
                "Second write should overwrite first");
        }

        @Test
        void createsParentDirectories(@TempDir Path tempDir) throws Exception {
            Path sessionsDir = tempDir.resolve(".agent-work/sessions");
            assertFalse(Files.exists(sessionsDir), "Precondition: sessions dir should not exist");

            invokeWriteClaudeResumeIdFile(tempDir.toString(), "sess-mkdirs");

            assertTrue(Files.isDirectory(sessionsDir),
                "Parent directories should be created");
        }
    }

    // ── copyPlanFromV2Store ────────────────────────────────

    @Nested
    class CopyPlanFromV2StoreTest {

        @Test
        void v2PlanExists_copiesToTarget(@TempDir Path tempDir) throws Exception {
            // Set up v2 store: basePath/.agent-work/sessions/plan.md
            Path v2Sessions = tempDir.resolve(".agent-work/sessions");
            Files.createDirectories(v2Sessions);
            Files.writeString(v2Sessions.resolve("plan.md"), "# V2 Plan content");

            Path targetDir = tempDir.resolve("target-agent-dir");
            Files.createDirectories(targetDir);

            invokeCopyPlanFromV2Store(tempDir.toString(), targetDir);

            Path copiedPlan = targetDir.resolve("plan.md");
            assertTrue(Files.exists(copiedPlan), "plan.md should be copied to target");
            assertEquals("# V2 Plan content",
                Files.readString(copiedPlan, StandardCharsets.UTF_8));
        }

        @Test
        void v2PlanMissing_noError(@TempDir Path tempDir) throws Exception {
            // No plan.md in v2 store
            Path targetDir = tempDir.resolve("target-agent-dir");
            Files.createDirectories(targetDir);

            assertDoesNotThrow(
                () -> invokeCopyPlanFromV2Store(tempDir.toString(), targetDir),
                "Missing v2 plan.md should not throw");
            assertFalse(Files.exists(targetDir.resolve("plan.md")),
                "No plan.md should be created when source doesn't exist");
        }

        @Test
        void targetDirectoryDoesNotExist_catchesIOException(@TempDir Path tempDir) throws Exception {
            // copyPlanFromV2Store does NOT create target dir — Files.copy will throw
            // IOException which is caught and logged
            Path v2Sessions = tempDir.resolve(".agent-work/sessions");
            Files.createDirectories(v2Sessions);
            Files.writeString(v2Sessions.resolve("plan.md"), "# Plan");

            Path nonExistentTarget = tempDir.resolve("no-such-dir");
            // Does NOT exist → Files.copy throws → caught by the method

            assertDoesNotThrow(
                () -> invokeCopyPlanFromV2Store(tempDir.toString(), nonExistentTarget),
                "Should catch IOException when target directory does not exist");
        }

        @Test
        void overwritesExistingPlanInTarget(@TempDir Path tempDir) throws Exception {
            Path v2Sessions = tempDir.resolve(".agent-work/sessions");
            Files.createDirectories(v2Sessions);
            Files.writeString(v2Sessions.resolve("plan.md"), "# Updated plan");

            Path targetDir = tempDir.resolve("target");
            Files.createDirectories(targetDir);
            Files.writeString(targetDir.resolve("plan.md"), "# Old plan");

            invokeCopyPlanFromV2Store(tempDir.toString(), targetDir);

            assertEquals("# Updated plan",
                Files.readString(targetDir.resolve("plan.md"), StandardCharsets.UTF_8),
                "Existing plan.md in target should be overwritten");
        }
    }

    // ── Reflection helpers ─────────────────────────────────

    private static Path invokeClaudeProjectDir(String basePath) throws Exception {
        Method m = SessionSwitchService.class.getDeclaredMethod("claudeProjectDir", String.class);
        m.setAccessible(true);
        return (Path) m.invoke(null, basePath);
    }

    private static void invokeCollectPlanFiles(Path dir, List<Path> result) throws Exception {
        Method m = SessionSwitchService.class.getDeclaredMethod("collectPlanFiles", Path.class, List.class);
        m.setAccessible(true);
        m.invoke(null, dir, result);
    }

    private static void invokeWriteClaudeResumeIdFile(String basePath, String sessionId) throws Exception {
        Method m = SessionSwitchService.class.getDeclaredMethod(
            "writeClaudeResumeIdFile", String.class, String.class);
        m.setAccessible(true);
        m.invoke(null, basePath, sessionId);
    }

    private static void invokeCopyPlanFromV2Store(String basePath, Path targetDir) throws Exception {
        Method m = SessionSwitchService.class.getDeclaredMethod(
            "copyPlanFromV2Store", String.class, Path.class);
        m.setAccessible(true);
        m.invoke(null, basePath, targetDir);
    }
}
