package com.github.catatafishen.ideagentforcopilot.settings;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Captures and caches the user's full shell environment (including nvm, sdkman, etc.).
 * This environment is used for both binary detection and runtime execution.
 */
public class ShellEnvironment {
    private static final Logger LOG = Logger.getInstance(ShellEnvironment.class);
    private static volatile Map<String, String> cachedEnvironment = null;
    private static final Object LOCK = new Object();

    private ShellEnvironment() {
    }

    /**
     * Get the captured shell environment, capturing it on first call and caching thereafter.
     *
     * @return Map of environment variables, or empty map if capture fails
     */
    @NotNull
    public static Map<String, String> getEnvironment() {
        if (cachedEnvironment != null) {
            return cachedEnvironment;
        }

        synchronized (LOCK) {
            if (cachedEnvironment != null) {
                return cachedEnvironment;
            }
            cachedEnvironment = captureEnvironment();
            return cachedEnvironment;
        }
    }

    /**
     * Force a re-capture of the shell environment (e.g., after user installs a new tool).
     */
    public static void refresh() {
        synchronized (LOCK) {
            cachedEnvironment = null;
        }
    }

    @NotNull
    private static Map<String, String> captureEnvironment() {
        String os = System.getProperty("os.name", "").toLowerCase();

        if (os.contains("win")) {
            return captureWindowsEnvironment();
        } else {
            return captureUnixEnvironment();
        }
    }

    @NotNull
    private static Map<String, String> captureUnixEnvironment() {
        try {
            String userShell = System.getenv("SHELL");
            if (userShell == null || userShell.isBlank()) {
                userShell = "/bin/sh";
            }

            // Run as a login shell (-l) to pick up /etc/profile, ~/.bash_profile, etc.
            // Then ALSO explicitly source the interactive rc file (~/.bashrc / ~/.zshrc)
            // because on Linux, bash login shells do NOT source ~/.bashrc unless
            // ~/.bash_profile explicitly does so — and tools like nvm/sdkman live in ~/.bashrc.
            String home = System.getProperty("user.home");
            String rcFile = userShell.contains("zsh") ? home + "/.zshrc" : home + "/.bashrc";
            String command = "[ -f '" + rcFile + "' ] && . '" + rcFile + "' 2>/dev/null; env 2>/dev/null";

            ProcessBuilder pb = new ProcessBuilder(userShell, "-l", "-c", command);
            pb.redirectErrorStream(false);

            Process process = pb.start();
            Map<String, String> env = new HashMap<>();

            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int idx = line.indexOf('=');
                    if (idx > 0) {
                        env.put(line.substring(0, idx), line.substring(idx + 1));
                    }
                }
            }

            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                LOG.warn("Shell environment capture timed out");
            }

            if (env.isEmpty()) {
                LOG.warn("Failed to capture shell environment, using system environment");
                return System.getenv();
            }

            LOG.info("Captured shell environment with PATH: " + env.get("PATH"));
            return Collections.unmodifiableMap(env);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Shell environment capture interrupted");
            return System.getenv();
        } catch (Exception e) {
            LOG.warn("Failed to capture shell environment: " + e.getMessage(), e);
            return System.getenv();
        }
    }

    @NotNull
    private static Map<String, String> captureWindowsEnvironment() {
        // Just use system environment - HOME/USERPROFILE are set correctly on Windows
        LOG.info("Using system environment for Windows");
        return System.getenv();
    }

    /**
     * Get PATH from the captured environment, or system PATH if capture failed.
     */
    @NotNull
    public static String getPath() {
        Map<String, String> env = getEnvironment();
        String path = env.get("PATH");
        if (path == null || path.isEmpty()) {
            path = System.getenv("PATH");
        }
        return path != null ? path : "";
    }
}
