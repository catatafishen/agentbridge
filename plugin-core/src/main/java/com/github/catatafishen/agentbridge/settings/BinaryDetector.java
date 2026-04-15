package com.github.catatafishen.agentbridge.settings;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Detects binaries using the user's full shell environment.
 * Works with any installation method (nvm, homebrew, system packages, etc.).
 */
public class BinaryDetector {
    private static final Logger LOG = Logger.getInstance(BinaryDetector.class);

    private static final String DEFAULT_PATHEXT =
        ".COM;.EXE;.BAT;.CMD;.VBS;.VBE;.JS;.JSE;.WSF;.WSH;.MSC";

    private BinaryDetector() {
    }

    /**
     * Detect if a binary exists and get its version, trying alternate names if primary fails.
     *
     * @param binaryName     Name of the binary (e.g., "copilot", "opencode")
     * @param alternateNames Alternate names to try if primary name not found
     * @return Version string like "v1.2.3", or null if not found
     */
    @Nullable
    public static String detectBinaryVersion(@NotNull String binaryName, @NotNull String[] alternateNames) {
        String version = tryDetectBinary(binaryName);
        if (version != null) return version;

        for (String altName : alternateNames) {
            version = tryDetectBinary(altName);
            if (version != null) return version;
        }

        return null;
    }

    @Nullable
    private static String tryDetectBinary(@NotNull String binaryName) {
        String os = System.getProperty("os.name", "").toLowerCase();
        List<String> cmd = os.contains("win")
            ? List.of("cmd.exe", "/c", binaryName + " --version")
            : List.of("sh", "-c", "command -v " + binaryName + " >/dev/null && " + binaryName + " --version");

        String output = runCommand(cmd, 5);
        if (output == null) return null;

        String version = parseVersion(output);
        if (version != null) {
            LOG.info("Detected " + binaryName + " version: " + version);
        }
        return version;
    }

    /**
     * Find the absolute path to a binary using the captured shell environment.
     *
     * <p>On Windows, scans the {@code PATH} directories directly using Java's {@link File}
     * API to avoid encoding issues. The {@code where.exe} approach fails for users whose PATH
     * contains non-ASCII characters (e.g. accented characters in the Windows username) because
     * the console output uses the OEM code page while Java reads it as UTF-8, mangling the path.
     *
     * @param binaryName Name of the binary to find
     * @return Absolute path, or null if not found
     */
    @Nullable
    public static String findBinaryPath(@NotNull String binaryName) {
        if (isWindows()) {
            return findBinaryOnWindowsPath(binaryName);
        }

        List<String> cmd = List.of("sh", "-c", "command -v " + binaryName);
        String output = runCommand(cmd, 3);
        if (output == null || output.isBlank()) return null;

        String path = output.trim().split("\n")[0].trim();
        logFound(binaryName, path);
        return path;
    }

    /**
     * Scans the Windows {@code PATH} directories for a binary, respecting {@code PATHEXT}.
     * Uses Java's {@link File} API directly — no subprocess, no encoding issues.
     */
    @Nullable
    private static String findBinaryOnWindowsPath(@NotNull String binaryName) {
        Map<String, String> env = ShellEnvironment.getEnvironment();
        String pathVar = env.getOrDefault("PATH", env.getOrDefault("Path", ""));
        String pathext = env.getOrDefault("PATHEXT", DEFAULT_PATHEXT);

        String[] dirs = pathVar.split(";");
        String[] extensions = pathext.split(";");

        // If the name already has a recognized extension, check it directly first
        if (hasExtension(binaryName, extensions)) {
            String found = scanDirs(dirs, binaryName);
            if (found != null) {
                logFound(binaryName, found);
                return found;
            }
        }

        // Search with each PATHEXT extension appended
        for (String ext : extensions) {
            String found = scanDirs(dirs, binaryName + ext);
            if (found != null) {
                logFound(binaryName, found);
                return found;
            }
        }

        LOG.debug("Binary '" + binaryName + "' not found on Windows PATH");
        return null;
    }

    /**
     * Scans a list of directories for a file with the given name.
     *
     * @return the absolute path if found, or {@code null}
     */
    @Nullable
    private static String scanDirs(@NotNull String[] dirs, @NotNull String fileName) {
        for (String dir : dirs) {
            String trimmed = dir.trim();
            if (trimmed.isEmpty()) continue;
            File f = new File(trimmed, fileName);
            if (f.isFile()) {
                return f.getAbsolutePath();
            }
        }
        return null;
    }

    private static boolean hasExtension(@NotNull String name, @NotNull String[] extensions) {
        String lower = name.toLowerCase();
        for (String ext : extensions) {
            if (lower.endsWith(ext.toLowerCase())) return true;
        }
        return false;
    }

    private static void logFound(@NotNull String binaryName, @NotNull String path) {
        LOG.info("Found " + binaryName + " at: " + path);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /**
     * Run a command with the captured shell environment and return stdout, or null on failure.
     */
    @Nullable
    private static String runCommand(@NotNull List<String> cmd, int timeoutSeconds) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().clear();
            pb.environment().putAll(ShellEnvironment.getEnvironment());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }

            return process.exitValue() == 0 ? output.toString() : null;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            LOG.debug("Command failed " + cmd + ": " + e.getMessage());
            return null;
        }
    }

    @Nullable
    private static String parseVersion(@NotNull String output) {
        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            String lower = trimmed.toLowerCase();
            boolean isNoise = lower.contains("welcome") || lower.contains("loading") || lower.contains("initializing");
            if (!isNoise && trimmed.matches(".*\\d+\\.\\d+.*")) {
                return trimmed;
            }
        }
        return null;
    }
}
