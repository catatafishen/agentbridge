package com.github.catatafishen.agentbridge.permissions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AbuseDetector")
class AbuseDetectorTest {

    private final AbuseDetector detector = new AbuseDetector();

    @Nested
    @DisplayName("git abuse detection")
    class GitAbuse {

        @ParameterizedTest
        @CsvSource({
                "run_command, '{\"command\": \"git status\"}'",
                "run_command, '{\"command\": \"git diff HEAD~1\"}'",
                "run_command, '{\"command\": \"git commit -m fix\"}'",
                "run_command, '{\"command\": \"git push origin main\"}'",
                "run_command, '{\"command\": \"git log --oneline\"}'",
                "run_in_terminal, '{\"command\": \"git stash pop\"}'",
                "run_command, '{\"command\": \"/usr/bin/git status\"}'",
        })
        @DisplayName("detects git commands via shell tools")
        void detectsGitCommands(String toolId, String args) {
            AbuseResult result = detector.check(toolId, args);
            assertNotNull(result, "Expected abuse for: " + args);
            assertEquals("git", result.category());
            assertTrue(result.reason().contains("git_status") || result.reason().contains("git tools"),
                    "Should suggest MCP alternatives");
        }
    }

    @Nested
    @DisplayName("file reading abuse detection")
    class FileReadAbuse {

        @ParameterizedTest
        @CsvSource({
                "run_command, '{\"command\": \"cat /etc/hosts\"}'",
                "run_command, '{\"command\": \"head -20 file.txt\"}'",
                "run_command, '{\"command\": \"tail -f log.txt\"}'",
                "run_command, '{\"command\": \"less README.md\"}'",
                "run_in_terminal, '{\"command\": \"more file.txt\"}'",
        })
        @DisplayName("detects file reading commands")
        void detectsFileReading(String toolId, String args) {
            AbuseResult result = detector.check(toolId, args);
            assertNotNull(result, "Expected abuse for: " + args);
            assertEquals("cat", result.category());
            assertTrue(result.reason().contains("read_file"));
        }
    }

    @Nested
    @DisplayName("search abuse detection")
    class SearchAbuse {

        @ParameterizedTest
        @CsvSource({
                "run_command, '{\"command\": \"grep -r TODO src/\"}'",
                "run_command, '{\"command\": \"rg pattern file.txt\"}'",
                "run_command, '{\"command\": \"ag search_term\"}'",
                "run_in_terminal, '{\"command\": \"ack pattern\"}'",
        })
        @DisplayName("detects search commands")
        void detectsSearchCommands(String toolId, String args) {
            AbuseResult result = detector.check(toolId, args);
            assertNotNull(result, "Expected abuse for: " + args);
            assertEquals("grep", result.category());
            assertTrue(result.reason().contains("search_text"));
        }
    }

    @Nested
    @DisplayName("edit abuse detection")
    class EditAbuse {

        @ParameterizedTest
        @CsvSource({
                "run_command, '{\"command\": \"sed -i s/foo/bar/ file.txt\"}'",
                "run_command, '{\"command\": \"awk ''{print $1}'' file.txt\"}'",
        })
        @DisplayName("detects sed/awk commands")
        void detectsEditCommands(String toolId, String args) {
            AbuseResult result = detector.check(toolId, args);
            assertNotNull(result, "Expected abuse for: " + args);
            assertEquals("sed", result.category());
            assertTrue(result.reason().contains("edit_text"));
        }
    }

    @Nested
    @DisplayName("file listing abuse detection")
    class ListAbuse {

        @ParameterizedTest
        @CsvSource({
                "run_command, '{\"command\": \"find . -name *.java\"}'",
                "run_command, '{\"command\": \"fd pattern\"}'",
                "run_command, '{\"command\": \"locate myfile\"}'",
        })
        @DisplayName("detects find commands")
        void detectsFindCommands(String toolId, String args) {
            AbuseResult result = detector.check(toolId, args);
            assertNotNull(result, "Expected abuse for: " + args);
            assertEquals("find", result.category());
            assertTrue(result.reason().contains("list_project_files"));
        }

        @ParameterizedTest
        @CsvSource({
                "run_command, '{\"command\": \"ls -la\"}'",
                "run_in_terminal, '{\"command\": \"dir /s\"}'",
        })
        @DisplayName("detects ls/dir commands")
        void detectsLsCommands(String toolId, String args) {
            AbuseResult result = detector.check(toolId, args);
            assertNotNull(result, "Expected abuse for: " + args);
            assertEquals("ls", result.category());
            assertTrue(result.reason().contains("list_project_files"));
        }
    }

    @Nested
    @DisplayName("non-abuse cases")
    class NonAbuse {

        @ParameterizedTest
        @ValueSource(strings = {
                "read_file",
                "edit_text",
                "git_status",
                "search_text",
                "list_project_files",
        })
        @DisplayName("ignores non-shell tools")
        void ignoresNonShellTools(String toolId) {
            AbuseResult result = detector.check(toolId, "{\"command\": \"git status\"}");
            assertNull(result, "Non-shell tools should not trigger abuse detection");
        }

        @ParameterizedTest
        @CsvSource({
                "run_command, '{\"command\": \"npm install\"}'",
                "run_command, '{\"command\": \"./gradlew build\"}'",
                "run_command, '{\"command\": \"python main.py\"}'",
                "run_command, '{\"command\": \"cargo build\"}'",
                "run_in_terminal, '{\"command\": \"docker compose up\"}'",
        })
        @DisplayName("allows legitimate shell commands")
        void allowsLegitimateCommands(String toolId, String args) {
            AbuseResult result = detector.check(toolId, args);
            assertNull(result, "Legitimate commands should not trigger abuse: " + args);
        }

        @Test
        @DisplayName("handles null arguments")
        void handlesNullArguments() {
            assertNull(detector.check("run_command", null));
        }

        @Test
        @DisplayName("handles empty arguments")
        void handlesEmptyArguments() {
            assertNull(detector.check("run_command", ""));
            assertNull(detector.check("run_command", "   "));
        }

        @Test
        @DisplayName("handles raw string arguments (no JSON)")
        void handlesRawStringArguments() {
            AbuseResult result = detector.check("run_command", "npm test");
            assertNull(result, "npm test should not be flagged");
        }
    }
}
