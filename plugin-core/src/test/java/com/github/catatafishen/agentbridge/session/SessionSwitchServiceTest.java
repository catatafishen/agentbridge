package com.github.catatafishen.agentbridge.session;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link SessionSwitchService} static helper methods.
 * Covers format builders, parsers, and classifiers that don't require IntelliJ services.
 */
class SessionSwitchServiceTest {

    @Nested
    class ParseGitBranchFromHead {

        @Test
        void normalBranch() {
            assertEquals("main", SessionSwitchService.parseGitBranchFromHead("ref: refs/heads/main"));
        }

        @Test
        void featureBranch() {
            assertEquals("feat/new-feature",
                SessionSwitchService.parseGitBranchFromHead("ref: refs/heads/feat/new-feature"));
        }

        @Test
        void withTrailingNewline() {
            assertEquals("develop",
                SessionSwitchService.parseGitBranchFromHead("ref: refs/heads/develop\n"));
        }

        @Test
        void detachedHead_returnsUnknown() {
            // A detached HEAD contains a bare commit SHA, not a ref
            assertEquals("unknown",
                SessionSwitchService.parseGitBranchFromHead("abc123def456"));
        }

        @Test
        void null_returnsUnknown() {
            assertEquals("unknown", SessionSwitchService.parseGitBranchFromHead(null));
        }

        @Test
        void blank_returnsUnknown() {
            assertEquals("unknown", SessionSwitchService.parseGitBranchFromHead("   "));
        }

        @Test
        void empty_returnsUnknown() {
            assertEquals("unknown", SessionSwitchService.parseGitBranchFromHead(""));
        }
    }

    @Nested
    class BuildWorkspaceYamlContent {

        @Test
        void containsAllFields() {
            String yaml = SessionSwitchService.buildWorkspaceYamlContent(
                "sess-123", "/home/user/project", "main", "2024-01-15T10:30:00Z");

            assertTrue(yaml.contains("id: sess-123"));
            assertTrue(yaml.contains("cwd: /home/user/project"));
            assertTrue(yaml.contains("git_root: /home/user/project"));
            assertTrue(yaml.contains("branch: main"));
            assertTrue(yaml.contains("created_at: 2024-01-15T10:30:00Z"));
            assertTrue(yaml.contains("updated_at: 2024-01-15T10:30:00Z"));
            assertTrue(yaml.contains("summary_count: 0"));
        }

        @Test
        void endsWithNewline() {
            String yaml = SessionSwitchService.buildWorkspaceYamlContent(
                "s1", "/path", "dev", "2024-01-01T00:00:00Z");
            assertTrue(yaml.endsWith("\n"));
        }
    }

    @Nested
    class BuildAcpSessionJson {

        @Test
        void validJson() {
            String json = SessionSwitchService.buildAcpSessionJson("sess-1", "/proj", 1700000000000L);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

            assertEquals("sess-1", obj.get("id").getAsString());
            assertTrue(obj.has("workspacePaths"));
            assertEquals("/proj", obj.getAsJsonArray("workspacePaths").get(0).getAsString());
            assertEquals("Imported from AgentBridge", obj.get("title").getAsString());
            assertEquals(1, obj.get("schemaVersion").getAsInt());
            assertTrue(obj.has("createdAt"));
            assertTrue(obj.has("lastModifiedAt"));
        }

        @Test
        void nullBasePathUsesEmptyString() {
            String json = SessionSwitchService.buildAcpSessionJson("s2", null, 0L);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            assertEquals("", obj.getAsJsonArray("workspacePaths").get(0).getAsString());
        }

        @Test
        void timestampsAreIso8601() {
            String json = SessionSwitchService.buildAcpSessionJson("s3", "/p", 1700000000000L);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            String ts = obj.get("createdAt").getAsString();
            // ISO 8601 format: contains 'T' and ends with 'Z' or offset
            assertTrue(ts.contains("T"));
        }
    }

    @Nested
    class ClassifyExportTarget {

        @Test
        void claude() {
            assertEquals("claude", SessionSwitchService.classifyExportTarget("claude-cli"));
        }

        @Test
        void codex() {
            assertEquals("codex", SessionSwitchService.classifyExportTarget("codex"));
        }

        @Test
        void kiro() {
            assertEquals("kiro", SessionSwitchService.classifyExportTarget("kiro"));
        }

        @Test
        void junie() {
            assertEquals("junie", SessionSwitchService.classifyExportTarget("junie"));
        }

        @Test
        void opencode() {
            assertEquals("opencode", SessionSwitchService.classifyExportTarget("opencode"));
        }

        @Test
        void copilot() {
            assertEquals("copilot", SessionSwitchService.classifyExportTarget("copilot-cli"));
        }

        @Test
        void copilotVariant() {
            assertEquals("copilot", SessionSwitchService.classifyExportTarget("copilot-custom"));
        }

        @Test
        void unknown() {
            assertEquals("generic", SessionSwitchService.classifyExportTarget("some-other-agent"));
        }
    }

    @Nested
    class CopilotSessionDir {

        @Test
        void constructsCorrectPath() {
            Path path = SessionSwitchService.copilotSessionDir("abc-123");
            String pathStr = path.toString();
            assertTrue(pathStr.contains(".copilot"));
            assertTrue(pathStr.contains("session-state"));
            assertTrue(pathStr.endsWith("abc-123"));
        }

        @Test
        void differentSessionIdsDifferentPaths() {
            Path p1 = SessionSwitchService.copilotSessionDir("id-1");
            Path p2 = SessionSwitchService.copilotSessionDir("id-2");
            assertNotEquals(p1, p2);
        }
    }
}
