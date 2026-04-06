package com.github.catatafishen.agentbridge.settings;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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

    /** Returns the user-configured binary path override, or {@code null} if not set. */
    @Nullable
    protected abstract String customBinaryPath();

    /** The primary binary name used for auto-detection (e.g. {@code "copilot"}, {@code "claude"}). */
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
     * Returns the custom override if set, otherwise searches the login-shell {@code PATH}.
     *
     * @return absolute path, or {@code null} if not found anywhere
     */
    @Nullable
    public String resolve() {
        String custom = customBinaryPath();
        if (custom != null && !custom.isEmpty()) return custom;

        String found = BinaryDetector.findBinaryPath(primaryBinaryName());
        if (found != null) return found;

        for (String alt : alternateNames()) {
            found = BinaryDetector.findBinaryPath(alt);
            if (found != null) return found;
        }
        return null;
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
            protected String customBinaryPath() { return fixedPath; }
            @Override
            protected @NotNull String primaryBinaryName() { return parent.primaryBinaryName(); }
            @Override
            protected @NotNull String[] alternateNames() { return parent.alternateNames(); }
        };
    }
}
