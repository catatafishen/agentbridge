package com.github.catatafishen.agentbridge.psi.review;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates that {@code AgentEditSession.toAbsolutePath(String, String)} correctly
 * normalizes relative paths against a base path. This is the core logic used by
 * {@code ensureAbsolutePath} to guarantee all map keys are absolute, and by
 * {@code removeApprovedForCommit} to match git-relative paths against stored keys.
 * <p>
 * The root cause of the "new files stay after commit" bug was that tool-created files
 * were stored with relative keys while commit pruning resolved to absolute keys.
 */
class PathCanonicalizationTest {

    private static final String BASE = "/home/user/project";

    /**
     * Access the package-private static method directly for testing.
     */
    private static String resolve(String path, String basePath) {
        return AgentEditSession.toAbsolutePath(path, basePath);
    }

    @Test
    void absolutePath_returnedAsIs() {
        String abs = "/home/user/project/src/Foo.java";
        assertEquals(abs, resolve(abs, BASE));
    }

    @Test
    void relativePath_resolvedAgainstBase() {
        assertEquals("/home/user/project/src/Foo.java",
            resolve("src/Foo.java", BASE));
    }

    @Test
    void nestedRelativePath_resolvedCorrectly() {
        assertEquals("/home/user/project/plugin-core/src/main/java/Foo.kt",
            resolve("plugin-core/src/main/java/Foo.kt", BASE));
    }

    @Test
    void nullBasePath_relativePathReturnedAsIs() {
        assertEquals("src/Foo.java", resolve("src/Foo.java", null));
    }

    @Test
    void nullBasePath_absolutePathReturnedAsIs() {
        assertEquals("/src/Foo.java", resolve("/src/Foo.java", null));
    }

    @Test
    void commitPrune_matchesNormalizedNewFile() {
        // Simulates: tool registers "plugin-core/src/Foo.kt" (relative)
        // Git returns "plugin-core/src/Foo.kt" (relative) after commit
        // Both should resolve to the same absolute path
        String toolPath = "plugin-core/src/Foo.kt";
        String gitPath = "plugin-core/src/Foo.kt";

        String normalizedTool = resolve(toolPath, BASE);
        String normalizedGit = resolve(gitPath, BASE);

        assertEquals(normalizedTool, normalizedGit);
        assertTrue(normalizedTool.startsWith("/"), "Should be absolute");
    }

    @Test
    void vfsPathAlreadyAbsolute_matchesCommitPrune() {
        // VFS listener registers absolute path
        String vfsPath = "/home/user/project/src/Bar.java";
        // Git returns relative path
        String gitPath = "src/Bar.java";

        String normalizedVfs = resolve(vfsPath, BASE);
        String normalizedGit = resolve(gitPath, BASE);

        assertEquals(normalizedVfs, normalizedGit);
    }

    @Test
    void windowsStyleBackslash_notAutoConverted() {
        // On Unix, backslashes are literal characters in filenames (unlikely but possible).
        // toAbsolutePath doesn't do separator conversion — callers should normalize first.
        String path = "src\\Foo.java";
        String result = resolve(path, BASE);
        assertFalse(result.startsWith("/home/user/project/src/Foo.java"));
    }

    @Test
    void windowsDrivePath_returnedAsIs() {
        String abs = "C:/project/src/Foo.java";
        assertEquals(abs, resolve(abs, BASE));
    }

    @Test
    void windowsDrivePathWithBase_returnedAsIs() {
        String abs = "C:/project/src/Foo.java";
        String windowsBase = "C:/Users/dev/project";
        assertEquals(abs, resolve(abs, windowsBase));
    }

    @Test
    void windowsUncPath_returnedAsIs() {
        String abs = "//server/share/project/src/Foo.java";
        assertEquals(abs, resolve(abs, BASE));
    }
}
