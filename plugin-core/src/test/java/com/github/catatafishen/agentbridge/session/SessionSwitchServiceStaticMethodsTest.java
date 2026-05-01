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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    // ── parseGitBranchFromHead ──────────────────────────────

    @Nested
    class ParseGitBranchFromHeadTest {

        @Test
        void symbolicRef_returnsBranchName() {
            assertEquals("main",
                SessionSwitchService.parseGitBranchFromHead("ref: refs/heads/main"));
        }

        @Test
        void symbolicRefWithNewline_returnsTrimmedBranch() {
            assertEquals("feature/foo",
                SessionSwitchService.parseGitBranchFromHead("ref: refs/heads/feature/foo\n"));
        }

        @Test
        void detachedHead_returnsUnknown() {
            assertEquals("unknown",
                SessionSwitchService.parseGitBranchFromHead("abc123def456789012345678901234567890abcd"));
        }

        @Test
        void nullContent_returnsUnknown() {
            assertEquals("unknown",
                SessionSwitchService.parseGitBranchFromHead(null));
        }

        @Test
        void emptyString_returnsUnknown() {
            assertEquals("unknown",
                SessionSwitchService.parseGitBranchFromHead(""));
        }

        @Test
        void blankString_returnsUnknown() {
            assertEquals("unknown",
                SessionSwitchService.parseGitBranchFromHead("   \t\n  "));
        }

        @Test
        void refsTagsNotABranch_returnsUnknown() {
            assertEquals("unknown",
                SessionSwitchService.parseGitBranchFromHead("ref: refs/tags/v1.0"));
        }
    }

    // ── buildWorkspaceYamlContent ──────────────────────────

    @Nested
    class BuildWorkspaceYamlContentTest {

        private static final String SESSION_ID = "sess-1234";
        private static final String BASE_PATH = "/home/user/project";
        private static final String BRANCH = "main";
        private static final String TIMESTAMP = "2025-01-15T10:30:00Z";

        @Test
        void containsAllFields() {
            String yaml = SessionSwitchService.buildWorkspaceYamlContent(
                SESSION_ID, BASE_PATH, BRANCH, TIMESTAMP);

            assertTrue(yaml.contains("id: " + SESSION_ID));
            assertTrue(yaml.contains("cwd: " + BASE_PATH));
            assertTrue(yaml.contains("git_root: " + BASE_PATH));
            assertTrue(yaml.contains("branch: " + BRANCH));
            assertTrue(yaml.contains("summary_count: 0"));
            assertTrue(yaml.contains("created_at: " + TIMESTAMP));
            assertTrue(yaml.contains("updated_at: " + TIMESTAMP));
        }

        @Test
        void eachFieldOnOwnLine() {
            String yaml = SessionSwitchService.buildWorkspaceYamlContent(
                SESSION_ID, BASE_PATH, BRANCH, TIMESTAMP);

            String[] lines = yaml.split("\n", -1);
            // 7 content lines + 1 trailing empty element from the final newline
            assertEquals(8, lines.length,
                "Expected 7 YAML lines plus trailing empty from final newline");
            assertTrue(lines[0].startsWith("id: "));
            assertTrue(lines[1].startsWith("cwd: "));
            assertTrue(lines[2].startsWith("git_root: "));
            assertTrue(lines[3].startsWith("branch: "));
            assertTrue(lines[4].startsWith("summary_count: "));
            assertTrue(lines[5].startsWith("created_at: "));
            assertTrue(lines[6].startsWith("updated_at: "));
        }

        @Test
        void endsWithTrailingNewline() {
            String yaml = SessionSwitchService.buildWorkspaceYamlContent(
                SESSION_ID, BASE_PATH, BRANCH, TIMESTAMP);

            assertTrue(yaml.endsWith("\n"), "YAML content should end with a newline");
        }

        @Test
        void valuesInterpolatedCorrectly() {
            String yaml = SessionSwitchService.buildWorkspaceYamlContent(
                "my-session", "/my/path", "develop", "2025-06-01T00:00:00Z");

            String expected = """
                id: my-session
                cwd: /my/path
                git_root: /my/path
                branch: develop
                summary_count: 0
                created_at: 2025-06-01T00:00:00Z
                updated_at: 2025-06-01T00:00:00Z
                """;

            assertEquals(expected, yaml);
        }
    }

    // ── buildAcpSessionJson ─────────────────────────────────

    @Nested
    class BuildAcpSessionJsonTest {

        @Test
        void containsSessionId() {
            String json = SessionSwitchService.buildAcpSessionJson("sess-42", "/my/project", 1_700_000_000_000L);
            assertTrue(json.contains("\"id\":\"sess-42\""), "JSON should contain session id: " + json);
        }

        @Test
        void containsWorkspacePaths() {
            String json = SessionSwitchService.buildAcpSessionJson("s1", "/my/path", 0L);
            assertTrue(json.contains("\"workspacePaths\":[\"/my/path\"]"),
                "JSON should contain workspacePaths array: " + json);
        }

        @Test
        void nullBasePath_workspacePathIsEmptyString() {
            String json = SessionSwitchService.buildAcpSessionJson("s1", null, 0L);
            assertTrue(json.contains("\"workspacePaths\":[\"\"]"),
                "Null basePath should produce empty string in workspacePaths: " + json);
        }

        @Test
        void containsTitle() {
            String json = SessionSwitchService.buildAcpSessionJson("s1", "/p", 0L);
            assertTrue(json.contains("\"title\":\"Imported from AgentBridge\""),
                "JSON should contain title: " + json);
        }

        @Test
        void containsTimestamps() {
            long epochMillis = 1_700_000_000_000L; // 2023-11-14T22:13:20Z
            String json = SessionSwitchService.buildAcpSessionJson("s1", "/p", epochMillis);
            String expectedTs = java.time.Instant.ofEpochMilli(epochMillis).toString();
            assertTrue(json.contains("\"createdAt\":\"" + expectedTs + "\""),
                "JSON should contain createdAt timestamp: " + json);
            assertTrue(json.contains("\"lastModifiedAt\":\"" + expectedTs + "\""),
                "JSON should contain lastModifiedAt timestamp: " + json);
        }

        @Test
        void timestampsMatchBetweenCreatedAndModified() {
            String json = SessionSwitchService.buildAcpSessionJson("s1", "/p", 12345L);
            // Both timestamps should be identical
            String ts = java.time.Instant.ofEpochMilli(12345L).toString();
            int createdIdx = json.indexOf("\"createdAt\":\"" + ts + "\"");
            int modifiedIdx = json.indexOf("\"lastModifiedAt\":\"" + ts + "\"");
            assertTrue(createdIdx >= 0, "createdAt timestamp missing");
            assertTrue(modifiedIdx >= 0, "lastModifiedAt timestamp missing");
        }

        @Test
        void containsSchemaVersion() {
            String json = SessionSwitchService.buildAcpSessionJson("s1", "/p", 0L);
            assertTrue(json.contains("\"schemaVersion\":1"),
                "JSON should contain schemaVersion 1: " + json);
        }

        @Test
        void isValidJson() {
            String json = SessionSwitchService.buildAcpSessionJson("s1", "/project", 1_000L);
            // Should parse without error
            com.google.gson.JsonObject parsed = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            assertEquals("s1", parsed.get("id").getAsString());
            assertEquals("/project", parsed.getAsJsonArray("workspacePaths").get(0).getAsString());
            assertEquals("Imported from AgentBridge", parsed.get("title").getAsString());
            assertEquals(1, parsed.get("schemaVersion").getAsInt());
        }

        @Test
        void epochZero_producesEpochTimestamp() {
            String json = SessionSwitchService.buildAcpSessionJson("s1", "/p", 0L);
            String zeroTs = java.time.Instant.EPOCH.toString();
            assertTrue(json.contains(zeroTs),
                "Epoch 0 should produce '" + zeroTs + "' timestamp: " + json);
        }
    }

    // ── classifyExportTarget ──────────────────────────────

    @Nested
    class ClassifyExportTargetTest {

        @Test
        void claudeCli_returnsClaude() {
            assertEquals("claude", SessionSwitchService.classifyExportTarget("claude-cli"));
        }

        @Test
        void codex_returnsCodex() {
            assertEquals("codex", SessionSwitchService.classifyExportTarget("codex"));
        }

        @Test
        void kiro_returnsKiro() {
            assertEquals("kiro", SessionSwitchService.classifyExportTarget("kiro"));
        }

        @Test
        void junie_returnsJunie() {
            assertEquals("junie", SessionSwitchService.classifyExportTarget("junie"));
        }

        @Test
        void opencode_returnsOpencode() {
            assertEquals("opencode", SessionSwitchService.classifyExportTarget("opencode"));
        }

        @Test
        void copilot_returnsCopilot() {
            assertEquals("copilot", SessionSwitchService.classifyExportTarget("copilot"));
        }

        @Test
        void copilotWithSuffix_returnsCopilot() {
            // Profile IDs like "copilot-custom" or "copilot-v2" should still classify as copilot
            assertEquals("copilot", SessionSwitchService.classifyExportTarget("copilot-custom"));
            assertEquals("copilot", SessionSwitchService.classifyExportTarget("copilot-v2"));
        }

        @Test
        void unknownProfileId_returnsGeneric() {
            assertEquals("generic", SessionSwitchService.classifyExportTarget("some-other-agent"));
        }

        @Test
        void emptyString_returnsGeneric() {
            assertEquals("generic", SessionSwitchService.classifyExportTarget(""));
        }

        @Test
        void caseMatters_claudeCliUpperCase_returnsGeneric() {
            // Profile matching should be case-sensitive
            assertEquals("generic", SessionSwitchService.classifyExportTarget("Claude-CLI"));
        }

        @Test
        void partialMatch_doesNotClassify() {
            // "claud" should not match "claude-cli"
            assertEquals("generic", SessionSwitchService.classifyExportTarget("claud"));
            // "code" should not match "codex"
            assertEquals("generic", SessionSwitchService.classifyExportTarget("code"));
        }
    }

    // ── copilotSessionDir ─────────────────────────────────

    @Nested
    class CopilotSessionDirTest {

        @Test
        void containsExpectedPathComponents() {
            Path result = SessionSwitchService.copilotSessionDir("abc-123");
            assertTrue(result.endsWith(Path.of(".copilot", "session-state", "abc-123")),
                "Path should end with .copilot/session-state/abc-123: " + result);
        }

        @Test
        void startsWithUserHome() {
            Path result = SessionSwitchService.copilotSessionDir("any-id");
            String userHome = System.getProperty("user.home", "");
            assertTrue(result.startsWith(userHome),
                "Path should start with user.home (" + userHome + "): " + result);
        }

        @Test
        void lastComponentIsSessionId() {
            Path result = SessionSwitchService.copilotSessionDir("my-session-uuid");
            assertEquals("my-session-uuid", result.getFileName().toString(),
                "Last path component should be the session ID");
        }

        @Test
        void differentSessionIds_produceDifferentPaths() {
            Path dir1 = SessionSwitchService.copilotSessionDir("id-1");
            Path dir2 = SessionSwitchService.copilotSessionDir("id-2");
            assertNotEquals(dir2, dir1,
                "Different session IDs should produce different paths");
            assertEquals(dir1.getParent(), dir2.getParent(),
                "Parent directories should be the same");
        }

        @Test
        void uuidSessionId_preservedAsIs() {
            String uuid = "550e8400-e29b-41d4-a716-446655440000";
            Path result = SessionSwitchService.copilotSessionDir(uuid);
            assertEquals(uuid, result.getFileName().toString(),
                "UUID session ID should be preserved verbatim");
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
