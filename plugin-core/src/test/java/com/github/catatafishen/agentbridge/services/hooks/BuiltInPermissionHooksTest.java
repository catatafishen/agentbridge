package com.github.catatafishen.agentbridge.services.hooks;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BuiltInPermissionHooks} — built-in command permission checks.
 */
class BuiltInPermissionHooksTest {

    @Nested
    class CheckRunCommand {
        @Test
        void nullCommandAllowed() {
            assertNull(BuiltInPermissionHooks.checkRunCommand(null));
        }

        @Test
        void blankCommandAllowed() {
            assertNull(BuiltInPermissionHooks.checkRunCommand("   "));
        }

        @Test
        void gitCommandDenied() {
            String result = BuiltInPermissionHooks.checkRunCommand("git status");
            assertNotNull(result);
            assertTrue(result.contains("git commands are not allowed"));
        }

        @Test
        void bareGitDenied() {
            assertNotNull(BuiltInPermissionHooks.checkRunCommand("git"));
        }

        @Test
        void chainedGitDenied() {
            assertNotNull(BuiltInPermissionHooks.checkRunCommand("echo hi && git push"));
            assertNotNull(BuiltInPermissionHooks.checkRunCommand("ls; git log"));
            assertNotNull(BuiltInPermissionHooks.checkRunCommand("grep x | git diff"));
        }

        @Test
        void catDenied() {
            String result = BuiltInPermissionHooks.checkRunCommand("cat file.txt");
            assertNotNull(result);
            assertTrue(result.contains("read_file"));
        }

        @Test
        void headTailLessMoreDenied() {
            assertNotNull(BuiltInPermissionHooks.checkRunCommand("head -n 10 file.txt"));
            assertNotNull(BuiltInPermissionHooks.checkRunCommand("tail -f log.txt"));
            assertNotNull(BuiltInPermissionHooks.checkRunCommand("less file.txt"));
            assertNotNull(BuiltInPermissionHooks.checkRunCommand("more file.txt"));
        }

        @Test
        void pipedCatDenied() {
            assertNotNull(BuiltInPermissionHooks.checkRunCommand("grep x | cat file.txt"));
            assertNotNull(BuiltInPermissionHooks.checkRunCommand("echo hi && cat file.txt"));
            assertNotNull(BuiltInPermissionHooks.checkRunCommand("ls; cat file.txt"));
        }

        @Test
        void sedDenied() {
            String result = BuiltInPermissionHooks.checkRunCommand("sed -i 's/foo/bar/' file.txt");
            assertNotNull(result);
            assertTrue(result.contains("edit_text"));
        }

        @Test
        void findDenied() {
            String result = BuiltInPermissionHooks.checkRunCommand("find . -name '*.java'");
            assertNotNull(result);
            assertTrue(result.contains("list_project_files"));
        }

        @Test
        void findWithTabDenied() {
            // find followed by tab
            assertNotNull(BuiltInPermissionHooks.checkRunCommand("find\t. -name x"));
        }

        @Test
        void gradleCompileOnlyDenied() {
            String result = BuiltInPermissionHooks.checkRunCommand("./gradlew compileJava");
            assertNotNull(result);
            assertTrue(result.contains("build_project"));
        }

        @Test
        void gradleCompileKotlinDenied() {
            assertNotNull(BuiltInPermissionHooks.checkRunCommand("gradle compileKotlin"));
        }

        @Test
        void gradleClassesDenied() {
            assertNotNull(BuiltInPermissionHooks.checkRunCommand("./gradlew :plugin-core:classes"));
        }

        @Test
        void gradleTestAllowed() {
            // "test" in command should NOT be denied (it's not compile-only)
            assertNull(BuiltInPermissionHooks.checkRunCommand("./gradlew test"));
        }

        @Test
        void gradleBuildAllowed() {
            assertNull(BuiltInPermissionHooks.checkRunCommand("./gradlew build"));
        }

        @Test
        void normalCommandAllowed() {
            assertNull(BuiltInPermissionHooks.checkRunCommand("echo hello"));
            assertNull(BuiltInPermissionHooks.checkRunCommand("npm install"));
            assertNull(BuiltInPermissionHooks.checkRunCommand("python3 script.py"));
        }

        @Test
        void caseInsensitive() {
            assertNotNull(BuiltInPermissionHooks.checkRunCommand("GIT status"));
            assertNotNull(BuiltInPermissionHooks.checkRunCommand("CAT file.txt"));
        }
    }

    @Nested
    class CheckRunInTerminal {
        @Test
        void nullCommandAllowed() {
            assertNull(BuiltInPermissionHooks.checkRunInTerminal(null));
        }

        @Test
        void blankCommandAllowed() {
            assertNull(BuiltInPermissionHooks.checkRunInTerminal("   "));
        }

        @Test
        void gitCommandDenied() {
            String result = BuiltInPermissionHooks.checkRunInTerminal("git commit -m test");
            assertNotNull(result);
            assertTrue(result.contains("run_in_terminal"));
        }

        @Test
        void sedDenied() {
            assertNotNull(BuiltInPermissionHooks.checkRunInTerminal("sed -i 's/a/b/' file"));
        }

        @Test
        void catAllowedInTerminal() {
            // cat is NOT denied in terminal (only in run_command)
            assertNull(BuiltInPermissionHooks.checkRunInTerminal("cat file.txt"));
        }

        @Test
        void findAllowedInTerminal() {
            assertNull(BuiltInPermissionHooks.checkRunInTerminal("find . -name x"));
        }

        @Test
        void normalCommandAllowed() {
            assertNull(BuiltInPermissionHooks.checkRunInTerminal("npm start"));
            assertNull(BuiltInPermissionHooks.checkRunInTerminal("docker compose up"));
        }
    }
}
