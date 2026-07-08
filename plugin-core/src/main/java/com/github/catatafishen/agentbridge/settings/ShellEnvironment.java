package com.github.catatafishen.agentbridge.settings;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.util.SystemProperties;
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
        if (SystemInfo.isWindows) {
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

            // Run a login shell to pick up /etc/profile and ~/.bash_profile / ~/.profile.
            // Then ALSO source well-known version-manager init scripts directly, because:
            // - ~/.bashrc is NOT sourced by bash login shells on Linux (only by interactive shells)
            // - ~/.bashrc often has "case $- in *i*) ;; *) return ;;" which skips nvm/sdkman init
            //   even if sourced explicitly
            // - nvm.sh and sdkman-init.sh are designed to be sourced without interactive guards
            String home = SystemProperties.getUserHome();
            String command = buildEnvCaptureCommand(home);

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

    /**
     * Builds a shell command that sources well-known version manager init scripts
     * (nvm, sdkman, cargo, pyenv, etc.) before printing the environment.
     * These scripts are safe to source in non-interactive shells — unlike ~/.bashrc.
     */
    @NotNull
    private static String buildEnvCaptureCommand(@NotNull String home) {
        // Each line: source the script if it exists, silently ignore errors
        return "{ "
            + "[ -s '" + home + "/.nvm/nvm.sh' ] && . '" + home + "/.nvm/nvm.sh' 2>/dev/null; "
            + "[ -s '" + home + "/.sdkman/bin/sdkman-init.sh' ] && . '" + home + "/.sdkman/bin/sdkman-init.sh' 2>/dev/null; "
            + "[ -s '" + home + "/.cargo/env' ] && . '" + home + "/.cargo/env' 2>/dev/null; "
            + "[ -f '" + home + "/.pyenv/bin/pyenv' ] && export PATH='" + home + "/.pyenv/bin:$PATH' 2>/dev/null; "
            + "env 2>/dev/null; }";
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

    /**
     * Returns the shell configured in IntelliJ's Terminal settings for the given project.
     * Falls back to {@link #getShellPath()} if the IDE setting is unavailable or blank.
     *
     * <p><b>Return contract:</b> The returned value reflects the raw IDE Terminal "Shell path"
     * setting, which may be a <em>full command line</em> (executable + arguments), not strictly
     * an executable path. For example, a user may configure
     * {@code "C:\Program Files\Git\bin\bash.exe" --login}. Callers that build a
     * {@link com.intellij.execution.configurations.GeneralCommandLine} must tokenize the result
     * (e.g., via {@code ParametersListUtil.parse()}) before using it as an executable.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>IntelliJ's {@code TerminalProjectOptionsProvider.getShellPath()} (may include arguments)</li>
     *   <li>{@code $SHELL} environment variable (Unix only).</li>
     *   <li>{@code /bin/sh} on Unix; {@code "sh"} on Windows (resolved via {@code PATH}).</li>
     * </ol>
     *
     * @param project the current project
     * @return the configured shell command line string (never blank; may include arguments)
     */
    @NotNull
    public static String getShellPath(@NotNull Project project) {
        try {
            Class<?> cls = Class.forName("org.jetbrains.plugins.terminal.TerminalProjectOptionsProvider");
            Object settings = cls.getMethod("getInstance", Project.class).invoke(null, project);
            String path = (String) settings.getClass().getMethod("getShellPath").invoke(settings);
            if (path != null && !path.isBlank()) {
                return path;
            }
        } catch (Exception e) {
            LOG.info("Could not read IntelliJ terminal shell path via reflection: " + e.getMessage());
        }
        return getShellPath();
    }

    /**
     * Returns a fallback shell path without consulting any project settings.
     * On Unix returns {@code $SHELL} (or {@code /bin/sh} if unset).
     * On Windows returns {@code "sh"} (resolved via {@code PATH}, e.g. Git Bash).
     *
     * <p>No disk scanning is performed.
     *
     * @return the shell executable path (never blank)
     */
    @NotNull
    public static String getShellPath() {
        if (SystemInfo.isWindows) {
            return "sh";
        }
        String envShell = System.getenv("SHELL");
        return (envShell != null && !envShell.isBlank()) ? envShell : "/bin/sh";
    }
}
