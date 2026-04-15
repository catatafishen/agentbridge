package com.github.catatafishen.agentbridge.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinaryDetectorTest {

    // ── parseVersion (private static) ───────────────────────────────────

    @Test
    void parseVersion_simpleVersion() throws Exception {
        assertEquals("0.22.0", invokeParseVersion("0.22.0"));
    }

    @Test
    void parseVersion_versionWithPrefix() throws Exception {
        assertEquals("copilot version 0.22.0", invokeParseVersion("copilot version 0.22.0"));
    }

    @Test
    void parseVersion_multiLineSkipsNoise() throws Exception {
        String output = """
            Welcome to Copilot CLI
            Loading configuration...
            0.22.0
            """;
        assertEquals("0.22.0", invokeParseVersion(output));
    }

    @Test
    void parseVersion_skipsWelcomeLine() throws Exception {
        String output = """
            Welcome to the CLI v2.0
            1.2.3
            """;
        assertEquals("1.2.3", invokeParseVersion(output));
    }

    @Test
    void parseVersion_skipsLoadingLine() throws Exception {
        String output = "loading modules...\n2.1.0";
        assertEquals("2.1.0", invokeParseVersion(output));
    }

    @Test
    void parseVersion_skipsInitializingLine() throws Exception {
        String output = "Initializing environment 1.0\n3.5.2-beta";
        assertEquals("3.5.2-beta", invokeParseVersion(output));
    }

    @Test
    void parseVersion_noVersionFound() throws Exception {
        assertNull(invokeParseVersion("no version here\njust text"));
    }

    @Test
    void parseVersion_emptyOutput() throws Exception {
        assertNull(invokeParseVersion(""));
    }

    @Test
    void parseVersion_blankLines() throws Exception {
        assertNull(invokeParseVersion("\n\n  \n"));
    }

    @Test
    void parseVersion_firstMatchReturned() throws Exception {
        String output = "1.0.0\n2.0.0";
        assertEquals("1.0.0", invokeParseVersion(output));
    }

    // ── scanDirs (private static) ───────────────────────────────────────

    @Test
    void scanDirs_findsExistingFile(@TempDir Path tempDir) throws Exception {
        Files.createFile(tempDir.resolve("copilot.exe"));
        String[] dirs = {tempDir.toString()};
        String result = invokeScanDirs(dirs, "copilot.exe");
        assertNotNull(result);
        assertTrue(result.endsWith("copilot.exe"));
    }

    @Test
    void scanDirs_returnsNullForMissingFile(@TempDir Path tempDir) throws Exception {
        String[] dirs = {tempDir.toString()};
        assertNull(invokeScanDirs(dirs, "nonexistent.exe"));
    }

    @Test
    void scanDirs_skipsEmptyDirEntries(@TempDir Path tempDir) throws Exception {
        Files.createFile(tempDir.resolve("copilot.exe"));
        String[] dirs = {"", "  ", tempDir.toString()};
        String result = invokeScanDirs(dirs, "copilot.exe");
        assertNotNull(result);
    }

    @Test
    void scanDirs_returnsFirstMatch(@TempDir Path tempDir) throws Exception {
        Path dir1 = tempDir.resolve("dir1");
        Path dir2 = tempDir.resolve("dir2");
        Files.createDirectories(dir1);
        Files.createDirectories(dir2);
        Files.createFile(dir1.resolve("tool.exe"));
        Files.createFile(dir2.resolve("tool.exe"));
        String[] dirs = {dir1.toString(), dir2.toString()};
        String result = invokeScanDirs(dirs, "tool.exe");
        assertNotNull(result);
        assertTrue(result.startsWith(dir1.toString()));
    }

    @Test
    void scanDirs_handlesNonAsciiPath(@TempDir Path tempDir) throws Exception {
        Path unicodeDir = tempDir.resolve("MajaFredströmWestergård");
        Files.createDirectories(unicodeDir);
        Files.createFile(unicodeDir.resolve("copilot.cmd"));
        String[] dirs = {unicodeDir.toString()};
        String result = invokeScanDirs(dirs, "copilot.cmd");
        assertNotNull(result, "Should find binary in path with non-ASCII characters");
        assertTrue(result.contains("MajaFredströmWestergård"));
    }

    // ── hasExtension (private static) ───────────────────────────────────

    @Test
    void hasExtension_matchesCaseInsensitive() throws Exception {
        String[] exts = {".EXE", ".CMD", ".BAT"};
        assertTrue(invokeHasExtension("copilot.exe", exts));
        assertTrue(invokeHasExtension("copilot.EXE", exts));
        assertTrue(invokeHasExtension("copilot.Cmd", exts));
    }

    @Test
    void hasExtension_returnsFalseForNoMatch() throws Exception {
        String[] exts = {".EXE", ".CMD"};
        assertFalse(invokeHasExtension("copilot", exts));
        assertFalse(invokeHasExtension("copilot.sh", exts));
    }

    // ── Reflection helpers ──────────────────────────────────────────────

    private static String invokeParseVersion(String output) throws Exception {
        Method m = BinaryDetector.class.getDeclaredMethod("parseVersion", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, output);
    }

    private static String invokeScanDirs(String[] dirs, String fileName) throws Exception {
        Method m = BinaryDetector.class.getDeclaredMethod("scanDirs", String[].class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, dirs, fileName);
    }

    private static boolean invokeHasExtension(String name, String[] extensions) throws Exception {
        Method m = BinaryDetector.class.getDeclaredMethod("hasExtension", String.class, String[].class);
        m.setAccessible(true);
        return (Boolean) m.invoke(null, name, extensions);
    }
}
