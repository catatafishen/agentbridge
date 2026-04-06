package com.github.catatafishen.agentbridge.psi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link ToolUtils#detectCommandAbuseType(String)} to ensure
 * shell commands that should use dedicated IntelliJ tools are blocked.
 */
class ToolUtilsAbuseDetectionTest {

    // ── Git ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("git abuse detection")
    class GitAbuse {
        @ParameterizedTest
        @ValueSource(strings = {
            "git status",
            "git diff",
            "git commit -m 'msg'",
            "git log --oneline",
            "git push origin main",
            "git checkout -b feature",
            "GIT_AUTHOR_NAME=x git commit",
            "sudo git push",
            "env GIT_DIR=/tmp git status",
        })
        void blocksGitCommands(String cmd) {
            assertEquals("git", ToolUtils.detectCommandAbuseType(cmd));
        }
    }

    // ── Cat/Head/Tail ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("cat/head/tail abuse detection")
    class CatAbuse {
        @ParameterizedTest
        @ValueSource(strings = {
            "cat README.md",
            "head -n 10 file.txt",
            "tail -f log.txt",
            "less file.txt",
            "more file.txt",
        })
        void blocksCatCommands(String cmd) {
            assertEquals("cat", ToolUtils.detectCommandAbuseType(cmd));
        }
    }

    // ── Sed ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("sed abuse detection")
    class SedAbuse {
        @ParameterizedTest
        @ValueSource(strings = {
            "sed -i 's/old/new/' file.txt",
            "echo x | sed 's/a/b/'",
        })
        void blocksSedCommands(String cmd) {
            assertEquals("sed", ToolUtils.detectCommandAbuseType(cmd));
        }

        @Test
        void chainedCatAndSedDetectsCatFirst() {
            // cat is checked before sed — first match wins
            assertEquals("cat", ToolUtils.detectCommandAbuseType("cat file && sed -i 's/x/y/' other"));
        }
    }

    // ── Grep ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("grep/rg abuse detection")
    class GrepAbuse {
        @ParameterizedTest
        @ValueSource(strings = {
            "grep -r 'TODO' src/",
            "rg pattern",
            "echo x | grep match",
            "ls && grep -l 'foo' *.txt",
        })
        void blocksGrepCommands(String cmd) {
            assertEquals("grep", ToolUtils.detectCommandAbuseType(cmd));
        }
    }

    // ── Find ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("find abuse detection")
    class FindAbuse {
        @ParameterizedTest
        @ValueSource(strings = {
            "find . -name '*.java'",
            "find /tmp -type f",
            "find src -name '*.kt' -type f",
        })
        void blocksFindCommands(String cmd) {
            assertEquals("find", ToolUtils.detectCommandAbuseType(cmd));
        }
    }

    // ── Test commands ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("test command abuse detection")
    class TestAbuse {

        // Gradle
        @ParameterizedTest
        @ValueSource(strings = {
            "./gradlew test",
            "./gradlew :plugin-core:test",
            "gradle test",
            "./gradlew test --info",
            "./gradlew build",
            "./gradlew check",
            "gradle build",
            "gradle check",
            "./gradlew build --stacktrace",
            "./gradlew check -x lint",
        })
        void blocksGradleTestCommands(String cmd) {
            assertEquals("test", ToolUtils.detectCommandAbuseType(cmd),
                "Should block: " + cmd);
        }

        // Maven
        @ParameterizedTest
        @ValueSource(strings = {
            "mvn test",
            "mvn test -pl module",
            "mvn verify",
            "mvn package",
            "mvn install",
            "mvn deploy",
            "mvn verify -DskipITs=false",
        })
        void blocksMavenTestCommands(String cmd) {
            assertEquals("test", ToolUtils.detectCommandAbuseType(cmd),
                "Should block: " + cmd);
        }

        // npm/yarn/pnpm
        @ParameterizedTest
        @ValueSource(strings = {
            "npm test",
            "yarn test",
            "pnpm test",
            "npm run test",
            "yarn run test",
            "pnpm run test",
            "npm run test:unit",
        })
        void blocksNodeTestCommands(String cmd) {
            assertEquals("test", ToolUtils.detectCommandAbuseType(cmd),
                "Should block: " + cmd);
        }

        // npx/bunx wrappers
        @ParameterizedTest
        @ValueSource(strings = {
            "npx jest",
            "npx vitest",
            "npx mocha",
            "bunx jest",
            "bunx vitest",
            "npx jest --watch",
            "pnpx jest",
            "npx ava",
        })
        void blocksNpxBunxTestCommands(String cmd) {
            assertEquals("test", ToolUtils.detectCommandAbuseType(cmd),
                "Should block: " + cmd);
        }

        // Python
        @ParameterizedTest
        @ValueSource(strings = {
            "pytest tests/",
            "python -m pytest",
            "python3 -m pytest tests/",
        })
        void blocksPythonTestCommands(String cmd) {
            assertEquals("test", ToolUtils.detectCommandAbuseType(cmd),
                "Should block: " + cmd);
        }

        // Other ecosystems
        @ParameterizedTest
        @ValueSource(strings = {
            "go test ./...",
            "cargo test",
            "dotnet test",
            "dotnet test --filter Category=Unit",
        })
        void blocksOtherTestCommands(String cmd) {
            assertEquals("test", ToolUtils.detectCommandAbuseType(cmd),
                "Should block: " + cmd);
        }
    }

    // ── Gradle compile commands ──────────────────────────────────────────────

    @Nested
    @DisplayName("Gradle compile task abuse detection")
    class GradleCompileAbuse {
        @ParameterizedTest
        @ValueSource(strings = {
            "./gradlew compileKotlin",
            "./gradlew compileJava",
            "./gradlew :plugin-core:compileKotlin",
            "./gradlew :plugin-core:compileJava",
            "./gradlew :plugin-core:compileKotlin :plugin-core:compileJava",
            "./gradlew compileTestKotlin",
            "./gradlew compileTestJava",
            "./gradlew :module:compileJava --info",
            "gradle compileJava",
            "gradle compileKotlin",
        })
        void blocksGradleCompileCommands(String cmd) {
            assertEquals("compile", ToolUtils.detectCommandAbuseType(cmd),
                "Should block: " + cmd);
        }
    }

    // ── Allowed commands ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("allowed commands (no abuse detected)")
    class AllowedCommands {
        @ParameterizedTest
        @ValueSource(strings = {
            "ls -la",
            "echo hello",
            "mkdir -p build",
            "cp file1 file2",
            "rm -f temp.txt",
            "curl http://example.com",
            "java -jar app.jar",
            "docker build .",
            "docker compose up",
            "./gradlew clean",
            "./gradlew assemble",
            "mvn clean",
            "npm start",
            "npm run dev",
            "node server.js",
        })
        void allowsNonAbuseCommands(String cmd) {
            assertNull(ToolUtils.detectCommandAbuseType(cmd),
                "Should allow: " + cmd);
        }
    }

    // ── Edge cases ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("edge cases")
    class EdgeCases {
        @Test
        void handlesEmptyString() {
            assertNull(ToolUtils.detectCommandAbuseType(""));
        }

        @Test
        void handlesWhitespace() {
            assertNull(ToolUtils.detectCommandAbuseType("   "));
        }

        @Test
        void caseInsensitive() {
            assertEquals("git", ToolUtils.detectCommandAbuseType("GIT status"));
            assertEquals("test", ToolUtils.detectCommandAbuseType("NPX Jest"));
        }
    }
}
