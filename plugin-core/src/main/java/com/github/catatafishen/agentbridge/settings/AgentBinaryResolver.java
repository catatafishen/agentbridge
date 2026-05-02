package com.github.catatafishen.agentbridge.settings;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Strategy for locating an agent's binary.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>{@link #customBinaryPath()} — explicit user override from settings</li>
 *   <li>{@link BinaryDetector#findBinaryPath} for the primary name</li>
 *   <li>{@link BinaryDetector#findBinaryPath} for each alternate name in order</li>
 * </ol>
 *
 * <p>Subclasses implement {@link #customBinaryPath()} and {@link #primaryBinaryName()} to wire up
 * their settings storage. Use {@link AcpClientBinaryResolver} for ACP-protocol clients and
 * {@link ClaudeAgentBinaryResolver} for the Claude CLI client.
 */
public abstract class AgentBinaryResolver {

    private static final Logger LOG = Logger.getInstance(AgentBinaryResolver.class);

    /**
     * Returns the user-configured binary path override, or {@code null} if not set.
     */
    @Nullable
    protected abstract String customBinaryPath();

    /**
     * The primary binary name used for auto-detection (e.g. {@code "copilot"}, {@code "claude"}).
     */
    @NotNull
    protected abstract String primaryBinaryName();

    /**
     * Alternate names to try when the primary name is not found.
     * Defaults to an empty array; override to provide alternates (e.g. {@code "copilot-cli"}).
     */
    @NotNull
    protected String[] alternateNames() {
        return new String[0];
    }

    /**
     * Resolves the binary to an absolute path.
     * Returns the custom override if set. Otherwise, finds all matching binaries
     * in PATH (for the primary name and alternates) and picks the one with the
     * highest version. Falls back to the first-found path if version detection
     * fails for all candidates.
     *
     * @return absolute path, or {@code null} if not found anywhere
     */
    @Nullable
    public String resolve() {
        String custom = customBinaryPath();
        if (custom != null && !custom.isEmpty()) return custom;

        return findBestBinaryAcrossNames();
    }

    /**
     * Finds all matching binaries for primary and alternate names, then picks
     * the one with the highest version. If version detection fails for all
     * candidates, returns the first-found path.
     */
    @Nullable
    private String findBestBinaryAcrossNames() {
        List<String> allCandidates = new java.util.ArrayList<>(
            BinaryDetector.findAllBinaryPaths(primaryBinaryName()));
        for (String alt : alternateNames()) {
            allCandidates.addAll(BinaryDetector.findAllBinaryPaths(alt));
        }

        if (allCandidates.isEmpty()) return null;
        if (allCandidates.size() == 1) return allCandidates.getFirst();

        // Multiple candidates — pick the highest version
        String bestPath = null;
        String bestVersion = null;

        for (String path : allCandidates) {
            String version = BinaryDetector.getVersionForPath(path);
            if (version == null) continue;

            if (bestVersion == null || BinaryDetector.compareVersions(version, bestVersion) > 0) {
                bestVersion = version;
                bestPath = path;
            }
        }

        if (bestPath != null) {
            LOG.info("Selected " + bestPath + " (version: " + bestVersion
                + ") from " + allCandidates.size() + " candidates");
            return bestPath;
        }

        // Version detection failed for all — fall back to first-found
        return allCandidates.getFirst();
    }

    /**
     * Detects the installed version string.
     * Uses the custom override path when set, falling back to auto-detection by name.
     *
     * @return version string (e.g. {@code "v1.2.3"}), or {@code null} if not found
     */
    @Nullable
    public String detectVersion() {
        String custom = customBinaryPath();
        String primary = (custom != null && !custom.isEmpty()) ? custom : primaryBinaryName();
        return BinaryDetector.detectBinaryVersion(primary, alternateNames());
    }

    /**
     * Returns a resolver that always uses {@code fixedPath} as the custom override,
     * regardless of what settings say. Useful in settings UI to check a path the
     * user has typed but not yet saved.
     */
    @NotNull
    public AgentBinaryResolver withCustomPath(@NotNull String fixedPath) {
        AgentBinaryResolver parent = this;
        return new AgentBinaryResolver() {
            @Override
            @NotNull
            protected String customBinaryPath() {
                return fixedPath;
            }

            @Override
            protected @NotNull String primaryBinaryName() {
                return parent.primaryBinaryName();
            }

            @Override
            protected @NotNull String[] alternateNames() {
                return parent.alternateNames();
            }
        };
    }
}
