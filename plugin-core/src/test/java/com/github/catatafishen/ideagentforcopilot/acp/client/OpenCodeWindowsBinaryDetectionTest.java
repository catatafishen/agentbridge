package com.github.catatafishen.ideagentforcopilot.acp.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link OpenCodeClient#resolveWindowsOpenCodePath(String)}.
 */
class OpenCodeWindowsBinaryDetectionTest {

    @TempDir
    Path tempDir;

    @Test
    void returnsNullOnNonWindows() {
        String os = System.getProperty("os.name", "");
        if (os.toLowerCase().contains("win")) {
            return; // Skip on actual Windows — test the non-Windows path on Linux/macOS
        }
        assertNull(OpenCodeClient.resolveWindowsOpenCodePath(tempDir.toString()));
    }

    @Test
    void returnsNullForNullBasePath() {
        assertNull(OpenCodeClient.resolveWindowsOpenCodePath(null));
    }

    @Test
    void returnsNullForEmptyBasePath() {
        assertNull(OpenCodeClient.resolveWindowsOpenCodePath(""));
    }

    @Test
    void returnsNullWhenExeIsMissing() throws IOException {
        // Directory exists but opencode.exe is not present
        Path binDir = tempDir.resolve(Path.of(
            "node_modules", "opencode-ai", "node_modules", "opencode-windows-x64", "bin"));
        Files.createDirectories(binDir);
        // On Linux/macOS os.name won't contain "win" so this will return null for a different reason,
        // but the method still handles the missing-file case independently.
        assertNull(OpenCodeClient.resolveWindowsOpenCodePath(tempDir.toString()));
    }
}
