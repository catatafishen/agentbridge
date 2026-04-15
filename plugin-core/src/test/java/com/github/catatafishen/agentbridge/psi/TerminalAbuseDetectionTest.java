package com.github.catatafishen.agentbridge.psi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ToolUtils#detectTerminalAbuseType(String)} (hard blocks)
 * and {@link ToolUtils#getTerminalReprimand(String)} (soft warnings).
 */
class TerminalAbuseDetectionTest {

    @Nested
    @DisplayName("hard blocks — commands that cause IDE desync")
    class HardBlocks {

        @ParameterizedTest
        @ValueSource(strings = {"git status", "git diff HEAD", "git push origin main", "git"})
        void blocksGitCommands(String command) {
            assertEquals("git", ToolUtils.detectTerminalAbuseType(command));
        }

        @ParameterizedTest
        @ValueSource(strings = {"sed -i 's/foo/bar/' file.txt", "sed 's/old/new/g' input.txt"})
        void blocksSedCommands(String command) {
            assertEquals("sed", ToolUtils.detectTerminalAbuseType(command));
        }
    }

    @Nested
    @DisplayName("soft reprimands — commands with better MCP alternatives")
    class SoftReprimands {

        @ParameterizedTest
        @ValueSource(strings = {
            "grep -r 'pattern' src/",
            "rg 'pattern' .",
            "ag 'pattern'",
            "echo foo | grep bar",
            "cat file | rg pattern",
        })
        void reprimandsGrepCommands(String command) {
            assertNull(ToolUtils.detectTerminalAbuseType(command));
            String reprimand = ToolUtils.getTerminalReprimand(command);
            assertNotNull(reprimand, "Expected reprimand for: " + command);
            assertTrue(reprimand.contains("search_text"), "Should suggest search_text for: " + command);
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "cat README.md",
            "head -n 10 file.txt",
            "tail log.txt",
            "less config.yaml",
            "more data.csv",
        })
        void reprimandsCatCommands(String command) {
            assertNull(ToolUtils.detectTerminalAbuseType(command));
            String reprimand = ToolUtils.getTerminalReprimand(command);
            assertNotNull(reprimand, "Expected reprimand for: " + command);
            assertTrue(reprimand.contains("read_file"), "Should suggest read_file for: " + command);
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "find . -name '*.java'",
            "find /tmp -type f",
            "find ./src -name 'Test*'",
        })
        void reprimandsFindCommands(String command) {
            assertNull(ToolUtils.detectTerminalAbuseType(command));
            String reprimand = ToolUtils.getTerminalReprimand(command);
            assertNotNull(reprimand, "Expected reprimand for: " + command);
            assertTrue(reprimand.contains("list_project_files"), "Should suggest list_project_files for: " + command);
        }

        @ParameterizedTest
        @ValueSource(strings = {"ls", "ls -la", "dir", "tree", "tree src/"})
        void reprimandsLsCommands(String command) {
            assertNull(ToolUtils.detectTerminalAbuseType(command));
            String reprimand = ToolUtils.getTerminalReprimand(command);
            assertNotNull(reprimand, "Expected reprimand for: " + command);
            assertTrue(reprimand.contains("list_project_files") || reprimand.contains("list_directory_tree"),
                "Should suggest list tool for: " + command);
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "npm test",
            "pytest -v tests/",
            "cargo test",
            "./gradlew test",
            "jest --watch",
            "npm run test",
        })
        void reprimandsTestCommands(String command) {
            assertNull(ToolUtils.detectTerminalAbuseType(command));
            String reprimand = ToolUtils.getTerminalReprimand(command);
            assertNotNull(reprimand, "Expected reprimand for: " + command);
            assertTrue(reprimand.contains("run_tests"), "Should suggest run_tests for: " + command);
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "./gradlew build",
            "./gradlew compileJava",
            "gradle check",
            "mvn package",
            "mvn install",
        })
        void reprimandsBuildCommands(String command) {
            assertNull(ToolUtils.detectTerminalAbuseType(command));
            String reprimand = ToolUtils.getTerminalReprimand(command);
            assertNotNull(reprimand, "Expected reprimand for: " + command);
            assertTrue(reprimand.contains("build_project"), "Should suggest build_project for: " + command);
        }
    }

    @Nested
    @DisplayName("allowed commands — no block, no reprimand")
    class AllowedCommands {

        @ParameterizedTest
        @ValueSource(strings = {
            "npm start",
            "npm run dev",
            "python manage.py runserver",
            "echo hello",
            "mkdir new_dir",
            "docker compose up",
            "ssh user@host",
            "curl https://example.com",
        })
        void allowsWithoutReprimand(String command) {
            assertNull(ToolUtils.detectTerminalAbuseType(command), "Should not hard-block: " + command);
            assertNull(ToolUtils.getTerminalReprimand(command), "Should not reprimand: " + command);
        }
    }
}
