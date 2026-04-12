package com.github.catatafishen.agentbridge.session;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

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

    // ── Reflection helper ──────────────────────────────────

    private static Path invokeClaudeProjectDir(String basePath) throws Exception {
        Method m = SessionSwitchService.class.getDeclaredMethod("claudeProjectDir", String.class);
        m.setAccessible(true);
        return (Path) m.invoke(null, basePath);
    }
}
