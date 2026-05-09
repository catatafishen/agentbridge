package com.github.catatafishen.agentbridge.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathSanitizerTest {

    @Test
    void keepsResolvedBinaryDirectory(@TempDir Path tempDir) {
        String binaryDir = tempDir.resolve("custom-cli-bin").toString();
        String path = "/some/random/dir" + sep() + binaryDir + sep() + "/another/dir";

        String result = PathSanitizer.sanitize(path, binaryDir);

        assertTrue(result.contains(binaryDir));
        assertFalse(result.contains("/some/random/dir"));
        assertFalse(result.contains("/another/dir"));
    }

    @Test
    void keepsEssentialSystemDirectories() {
        String path = "/usr/bin" + sep() + "/opt/homebrew/bin" + sep() + "/bin" + sep() + "/snap/bin";

        String result = PathSanitizer.sanitize(path, "/nonexistent");

        assertTrue(result.contains("/usr/bin"));
        assertTrue(result.contains("/bin"));
        assertFalse(result.contains("/opt/homebrew/bin"));
        assertFalse(result.contains("/snap/bin"));
    }

    @Test
    void keepsDirectoriesContainingNode(@TempDir Path tempDir) throws IOException {
        Path nodeDir = tempDir.resolve("nvm-node");
        Files.createDirectories(nodeDir);
        Files.createFile(nodeDir.resolve("node"));

        String path = nodeDir + sep() + "/some/other/dir";

        String result = PathSanitizer.sanitize(path, "/nonexistent");

        assertTrue(result.contains(nodeDir.toString()));
        assertFalse(result.contains("/some/other/dir"));
    }

    @Test
    void keepsDirectoriesContainingNpm(@TempDir Path tempDir) throws IOException {
        Path npmDir = tempDir.resolve("nvm-npm");
        Files.createDirectories(npmDir);
        Files.createFile(npmDir.resolve("npm"));

        String result = PathSanitizer.sanitize(npmDir.toString(), "/nonexistent");

        assertTrue(result.contains(npmDir.toString()));
    }

    @Test
    void stripsNonEssentialDirectories() {
        String path = "/usr/bin" + sep() + "/opt/homebrew/bin" + sep()
            + "/home/user/.cargo/bin" + sep() + "/snap/gh/current";

        String result = PathSanitizer.sanitize(path, "/nonexistent");

        assertTrue(result.contains("/usr/bin"));
        assertFalse(result.contains("/opt/homebrew/bin"));
        assertFalse(result.contains("/home/user/.cargo/bin"));
        assertFalse(result.contains("/snap/gh/current"));
    }

    @Test
    void handlesEmptyPath() {
        assertEquals("", PathSanitizer.sanitize("", "/nonexistent"));
    }

    @Test
    void shouldKeepReturnsTrueForBinaryDir() {
        assertTrue(PathSanitizer.shouldKeep("/my/binary/dir", "/my/binary/dir"));
    }

    @Test
    void shouldKeepReturnsTrueForSystemDirs() {
        assertTrue(PathSanitizer.shouldKeep("/usr/bin", "/nonexistent"));
        assertTrue(PathSanitizer.shouldKeep("/bin", "/nonexistent"));
        assertTrue(PathSanitizer.shouldKeep("/usr/sbin", "/nonexistent"));
        assertTrue(PathSanitizer.shouldKeep("/sbin", "/nonexistent"));
    }

    @Test
    void shouldKeepReturnsFalseForRandomDir() {
        assertFalse(PathSanitizer.shouldKeep("/opt/random/tool", "/nonexistent"));
    }

    private static String sep() {
        return File.pathSeparator;
    }
}
