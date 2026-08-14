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

    // ── Grep path extraction ─────────────────────────────────────────────────

    @Nested
    @DisplayName("grep path extraction")
    class GrepPathExtraction {
        @Test
        void extractsSinglePath() {
            assertEquals(
                java.util.List.of("file.log"),
                ToolUtils.extractGrepPaths("grep ERROR file.log"));
        }

        @Test
        void extractsMultiplePaths() {
            assertEquals(
                java.util.List.of("a.log", "b.log"),
                ToolUtils.extractGrepPaths("grep TODO a.log b.log"));
        }

        @Test
        void skipsSingleArgFlags() {
            assertEquals(
                java.util.List.of("notes.md"),
                ToolUtils.extractGrepPaths("grep -i -n PATTERN notes.md"));
        }

        @Test
        void skipsTwoArgFlags() {
            assertEquals(
                java.util.List.of("file.txt"),
                ToolUtils.extractGrepPaths("grep -A 3 -B 2 -e foo file.txt"));
        }

        @Test
        void respectsQuotedPattern() {
            assertEquals(
                java.util.List.of("ci.log"),
                ToolUtils.extractGrepPaths("grep \"some thing\" ci.log"));
        }

        @Test
        void emptyWhenNoPaths() {
            assertEquals(java.util.List.of(), ToolUtils.extractGrepPaths("grep -r PATTERN"));
        }

        @Test
        void emptyWhenGlob() {
            assertEquals(java.util.List.of(), ToolUtils.extractGrepPaths("grep PATTERN *.java"));
        }

        @Test
        void emptyWhenNotGrep() {
            assertEquals(java.util.List.of(), ToolUtils.extractGrepPaths("cat file.txt"));
        }

        @Test
        void handlesRipgrep() {
            assertEquals(
                java.util.List.of("logs/build.log"),
                ToolUtils.extractGrepPaths("rg --no-heading TODO logs/build.log"));
        }

        @Test
        void handlesCommandPrefix() {
            assertEquals(
                java.util.List.of("file.log"),
                ToolUtils.extractGrepPaths("sudo grep PATTERN file.log"));
        }
    }

    // ── Shell tokenizer ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("shell tokenizer")
    class ShellTokenizer {
        @Test
        void splitsOnWhitespace() {
            assertEquals(
                java.util.List.of("a", "b", "c"),
                ToolUtils.tokenizeShellCommand("a  b\tc"));
        }

        @Test
        void preservesDoubleQuoted() {
            assertEquals(
                java.util.List.of("grep", "hello world", "f"),
                ToolUtils.tokenizeShellCommand("grep \"hello world\" f"));
        }

        @Test
        void preservesSingleQuoted() {
            assertEquals(
                java.util.List.of("grep", "a b", "f"),
                ToolUtils.tokenizeShellCommand("grep 'a b' f"));
        }

        @Test
        void handlesEmpty() {
            assertEquals(java.util.List.of(), ToolUtils.tokenizeShellCommand(""));
        }
    }

    // ── Heredoc body stripping ───────────────────────────────────────────────

    @Nested
    @DisplayName("heredoc body stripping")
    class HeredocBodyStripping {

        @Test
        @DisplayName("gh pr create with heredoc body mentioning ./gradlew test is NOT blocked as test")
        void ghPrCreateWithGradlewTestInBodyIsNotBlocked() {
            String cmd = "gh pr create --title 'My PR' --body <<'EOF'\n"
                + "This PR improves performance. To verify, run ./gradlew test\n"
                + "EOF";
            assertNull(ToolUtils.detectCommandAbuseType(cmd));
        }

        @Test
        @DisplayName("gh pr create with heredoc body mentioning npm test is NOT blocked as test")
        void ghPrCreateWithNpmTestInBodyIsNotBlocked() {
            String cmd = "gh pr create --title 'My PR' --body <<EOF\n"
                + "Run npm test before merging\n"
                + "EOF";
            assertNull(ToolUtils.detectCommandAbuseType(cmd));
        }

        @Test
        @DisplayName("actual ./gradlew test command without heredoc IS still blocked")
        void gradlewTestCommandIsStillBlocked() {
            assertEquals("test", ToolUtils.detectCommandAbuseType("./gradlew test"));
        }

        @Test
        @DisplayName("stripHeredocBodies removes body but keeps delimiters")
        void stripHeredocBodiesKeepsDelimiters() {
            String cmd = "gh pr create --body <<'EOF'\nRun ./gradlew test first\nEOF";
            String stripped = ToolUtils.stripHeredocBodies(cmd);
            // Body removed; opening <<EOF and closing EOF remain
            org.junit.jupiter.api.Assertions.assertTrue(stripped.contains("<<EOF"), "opener should remain");
            org.junit.jupiter.api.Assertions.assertTrue(stripped.contains("\nEOF"), "closer should remain");
            org.junit.jupiter.api.Assertions.assertFalse(stripped.contains("./gradlew test"), "body should be stripped");
        }

        @Test
        @DisplayName("stripHeredocBodies handles <<- form")
        void stripHeredocBodiesHandlesDashForm() {
            String cmd = "cat <<-BLOCK\n\tsome content with jest mocha\nBLOCK";
            String stripped = ToolUtils.stripHeredocBodies(cmd);
            org.junit.jupiter.api.Assertions.assertFalse(stripped.contains("jest"), "body should be stripped");
        }

        @Test
        @DisplayName("command with no heredoc is returned unchanged")
        void noHeredocReturnedUnchanged() {
            String cmd = "./gradlew test --info";
            assertEquals(cmd, ToolUtils.stripHeredocBodies(cmd));
        }
    }
}
