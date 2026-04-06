package com.github.catatafishen.agentbridge.settings;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.List;

/**
 * Base class for per-client binary detection.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>User-configured override from {@link #getConfiguredPath()}</li>
 *   <li>Auto-detection via the captured login-shell environment ({@link BinaryDetector}),
 *       which includes generic known directories like {@code /opt/homebrew/bin},
 *       {@code /usr/local/bin}, etc.</li>
 *   <li>Binary-specific additional paths from {@link #additionalSearchPaths()} —
 *       subclasses override this to list locations that are unique to their binary
 *       (e.g. snap packages, package-manager-specific install prefixes, Windows
 *       program-files paths).</li>
 * </ol>
 */
public abstract class ClientBinaryDetector {

    /**
     * Return the user-configured binary path override, or {@code null} if none is set.
     */
    @Nullable
    protected abstract String getConfiguredPath();

    /**
     * Fully-qualified paths to check as a last resort, specific to this binary.
     * Only paths that are truly unique to this binary should be listed here —
     * generic directories like {@code /usr/local/bin} are already handled by
     * {@link BinaryDetector#findBinaryPath(String)}.
     *
     * <p>Default implementation returns an empty list.
     */
    @NotNull
    protected List<String> additionalSearchPaths() {
        return List.of();
    }

    /**
     * Resolve the binary: returns the configured override if set, otherwise
     * auto-detects using the captured shell environment and any
     * {@link #additionalSearchPaths()}.
     *
     * @param primaryName    Primary binary name (e.g. {@code "copilot"})
     * @param alternateNames Alternate names to try when primary is not found
     * @return Absolute path or name found, or {@code null} if not found
     */
    @Nullable
    public final String resolve(@NotNull String primaryName, @NotNull String... alternateNames) {
        String configured = getConfiguredPath();
        if (configured != null) {
            return configured;
        }
        // Phase 1: shell-environment-aware PATH search + generic known dirs
        String found = BinaryDetector.findBinaryPath(primaryName);
        if (found != null) return found;
        for (String alt : alternateNames) {
            found = BinaryDetector.findBinaryPath(alt);
            if (found != null) return found;
        }
        // Phase 2: binary-specific additional paths (snap, linuxbrew, Windows program-files, etc.)
        for (String path : additionalSearchPaths()) {
            if (new File(path).canExecute()) {
                return path;
            }
        }
        return null;
    }
}
