package com.github.catatafishen.agentbridge.services.hooks;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BuiltInSuccessHooks} — built-in success-hook annotations.
 */
class BuiltInSuccessHooksTest {

    @Nested
    class TerminalReprimand {
        @Test
        void nullCommandReturnsNull() {
            assertNull(BuiltInSuccessHooks.terminalReprimand(null, false));
        }

        @Test
        void blankCommandReturnsNull() {
            assertNull(BuiltInSuccessHooks.terminalReprimand("   ", false));
        }

        @Test
        void errorCommandReturnsNull() {
            assertNull(BuiltInSuccessHooks.terminalReprimand("grep foo", true));
        }

        @ParameterizedTest
        @ValueSource(strings = {"grep pattern file", "rg pattern", "ag pattern", "echo x | grep y", "echo | rg foo", "echo | ag bar"})
        void grepLikeCommandsGetNudge(String command) {
            String result = BuiltInSuccessHooks.terminalReprimand(command, false);
            assertNotNull(result, "Expected nudge for: " + command);
            assertTrue(result.contains("search_text"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"cat file.txt", "head -n 10 file", "tail -f log", "less file", "more file", "echo x | cat y"})
        void catLikeCommandsGetNudge(String command) {
            String result = BuiltInSuccessHooks.terminalReprimand(command, false);
            assertNotNull(result, "Expected nudge for: " + command);
            assertTrue(result.contains("read_file"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"find . -name '*.java'", "find."})
        void findCommandsGetNudge(String command) {
            String result = BuiltInSuccessHooks.terminalReprimand(command, false);
            assertNotNull(result, "Expected nudge for: " + command);
            assertTrue(result.contains("list_project_files"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"ls", "ls -la", "dir", "tree", "tree -L 2"})
        void lsLikeCommandsGetNudge(String command) {
            String result = BuiltInSuccessHooks.terminalReprimand(command, false);
            assertNotNull(result, "Expected nudge for: " + command);
            assertTrue(result.contains("list_project_files"));
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "npm test", "npm run test", "yarn test", "pnpm test",
            "pytest", "python -m pytest", "jest", "vitest", "mocha",
            "jasmine", "./gradlew test", "gradle test",
            "mvn test", "mvn verify", "go test"
        })
        void testRunnerCommandsGetNudge(String command) {
            String result = BuiltInSuccessHooks.terminalReprimand(command, false);
            assertNotNull(result, "Expected nudge for: " + command);
            assertTrue(result.contains("run_tests"));
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "./gradlew compileJava", "./gradlew classes",
            "gradle compileKotlin", "mvn compile"
        })
        void buildCommandsGetNudge(String command) {
            String result = BuiltInSuccessHooks.terminalReprimand(command, false);
            assertNotNull(result, "Expected nudge for: " + command);
            assertTrue(result.contains("build_project"));
        }

        @Test
        void normalCommandNoNudge() {
            assertNull(BuiltInSuccessHooks.terminalReprimand("docker compose up", false));
            assertNull(BuiltInSuccessHooks.terminalReprimand("npm install", false));
        }
    }

    @Nested
    class StaleNamingCheck {
        @Test
        void nullOutputReturnsNull() {
            assertNull(BuiltInSuccessHooks.staleNamingCheck(null, "content"));
        }

        @Test
        void nonWrittenOutputReturnsNull() {
            assertNull(BuiltInSuccessHooks.staleNamingCheck("Read file", "content"));
        }

        @Test
        void nullContentReturnsNull() {
            assertNull(BuiltInSuccessHooks.staleNamingCheck("Written: file.java", null));
        }

        @Test
        void blankContentReturnsNull() {
            assertNull(BuiltInSuccessHooks.staleNamingCheck("Written: file.java", "   "));
        }

        @Test
        void shortFileReturnsNull() {
            String content = "line1\nline2\nline3\n";
            assertNull(BuiltInSuccessHooks.staleNamingCheck("Written: file.java", content));
        }

        @Test
        void longFileReturnsWarning() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 150; i++) {
                sb.append("line ").append(i).append('\n');
            }
            String result = BuiltInSuccessHooks.staleNamingCheck("Written: file.java", sb.toString());
            assertNotNull(result);
            assertTrue(result.contains("Stale naming check"));
            assertTrue(result.contains("151")); // 150 newlines + 1 = 151 lines
        }

        @Test
        void exactly99LinesReturnsNull() {
            StringBuilder sb = new StringBuilder();
            // 98 newlines = 99 lines (countLines counts 1 + number of \n)
            for (int i = 0; i < 98; i++) {
                sb.append("x\n");
            }
            sb.append("last");
            assertNull(BuiltInSuccessHooks.staleNamingCheck("Written: file.java", sb.toString()));
        }

        @Test
        void exactly100LinesReturnsWarning() {
            StringBuilder sb = new StringBuilder();
            // 99 newlines = 100 lines
            for (int i = 0; i < 99; i++) {
                sb.append("x\n");
            }
            sb.append("last");
            String result = BuiltInSuccessHooks.staleNamingCheck("Written: file.java", sb.toString());
            assertNotNull(result);
        }
    }
}
