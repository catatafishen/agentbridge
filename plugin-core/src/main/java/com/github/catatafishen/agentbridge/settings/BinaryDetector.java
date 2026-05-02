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

    private static final String VERSION_FLAG = " --version";

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
            ? List.of("cmd.exe", "/c", binaryName + VERSION_FLAG)
            : List.of("sh", "-c", "command -v " + binaryName + " >/dev/null && " + binaryName + VERSION_FLAG);

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
     * Find ALL absolute paths to a binary across the user's PATH.
     * On Unix, scans PATH directories directly (equivalent to {@code which -a}).
     * On Windows, scans all PATH directories with PATHEXT extensions.
     *
     * @param binaryName Name of the binary to find
     * @return list of absolute paths (may be empty, never null)
     */
    @NotNull
    public static List<String> findAllBinaryPaths(@NotNull String binaryName) {
        Map<String, String> env = ShellEnvironment.getEnvironment();
        String pathVar;
        if (isWindows()) {
            pathVar = env.getOrDefault("PATH", env.getOrDefault("Path", ""));
        } else {
            pathVar = env.getOrDefault("PATH", "");
        }

        String separator = isWindows() ? ";" : ":";
        String[] dirs = pathVar.split(separator);
        List<String> results = new java.util.ArrayList<>();

        if (isWindows()) {
            String pathext = env.getOrDefault("PATHEXT", DEFAULT_PATHEXT);
            String[] extensions = pathext.split(";");

            if (hasExtension(binaryName, extensions)) {
                collectFromDirs(dirs, binaryName, results);
            }
            for (String ext : extensions) {
                collectFromDirs(dirs, binaryName + ext, results);
            }
        } else {
            collectFromDirs(dirs, binaryName, results);
        }

        if (results.size() > 1) {
            LOG.info("Found " + results.size() + " binaries for '" + binaryName + "': " + results);
        }
        return results;
    }

    /**
     * Gets the version string for a binary at the given absolute path.
     *
     * @param binaryPath absolute path to the binary
     * @return version string (e.g. "v1.0.40"), or null if version detection fails
     */
    @Nullable
    public static String getVersionForPath(@NotNull String binaryPath) {
        List<String> cmd = isWindows()
            ? List.of("cmd.exe", "/c", binaryPath + VERSION_FLAG)
            : List.of("sh", "-c", binaryPath + VERSION_FLAG);

        String output = runCommand(cmd, 5);
        if (output == null) return null;
        return parseVersion(output);
    }

    /**
     * Compares two version strings and returns the higher one. Handles common version
     * formats: {@code "v1.0.40"}, {@code "1.0.40"}, {@code "v1.0.40-1"}.
     *
     * @return positive if v1 > v2, negative if v1 < v2, zero if equal
     */
    public static int compareVersions(@Nullable String v1, @Nullable String v2) {
        int[] parts1 = parseVersionParts(v1);
        int[] parts2 = parseVersionParts(v2);

        int len = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < len; i++) {
            int p1 = i < parts1.length ? parts1[i] : 0;
            int p2 = i < parts2.length ? parts2[i] : 0;
            if (p1 != p2) return Integer.compare(p1, p2);
        }
        return 0;
    }

    private static int[] parseVersionParts(@Nullable String version) {
        if (version == null || version.isBlank()) return new int[0];

        // Strip leading non-digits (e.g. "v", "Copilot v")
        String cleaned = version.replaceAll("^\\D*", "");
        // Strip trailing non-numeric suffixes (e.g. "-1", "-beta")
        cleaned = cleaned.replaceAll("[^0-9.].*$", "");
        if (cleaned.isEmpty()) return new int[0];

        String[] segments = cleaned.split("\\.");
        int[] parts = new int[segments.length];
        for (int i = 0; i < segments.length; i++) {
            try {
                parts[i] = Integer.parseInt(segments[i]);
            } catch (NumberFormatException e) {
                parts[i] = 0;
            }
        }
        return parts;
    }

    /**
     * Collects all matching files from directories into the results list.
     * Skips duplicates (same canonical path).
     */
    private static void collectFromDirs(@NotNull String[] dirs, @NotNull String fileName,
                                        @NotNull List<String> results) {
        for (String dir : dirs) {
            String trimmed = dir.trim();
            if (trimmed.isEmpty()) continue;
            File f = new File(trimmed, fileName);
            if (f.isFile()) {
                String absPath = f.getAbsolutePath();
                if (!results.contains(absPath)) {
                    results.add(absPath);
                }
            }
        }
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
