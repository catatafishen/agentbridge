package com.github.catatafishen.agentbridge.settings;

import com.github.catatafishen.agentbridge.services.AgentProfileManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * {@link AgentBinaryResolver} for ACP-protocol clients (Copilot, Kiro, Junie, OpenCode, Codex).
 *
 * <p>Reads the user's custom binary path from {@link AgentProfileManager#loadBinaryPath(String)},
 * which persists it in the agent's {@code AgentProfile} (stored in {@code ideAgentProfiles.xml}).
 */
public class AcpClientBinaryResolver extends AgentBinaryResolver {

    private final String agentId;
    private final String binaryName;
    private final String[] alternates;

    /**
     * @param agentId    the agent identifier (e.g. {@code "copilot"}) — used to look up the
     *                   custom binary path in the agent profile
     * @param binaryName the primary binary name for auto-detection (e.g. {@code "copilot"})
     * @param alternates additional names to try when the primary is not found
     */
    public AcpClientBinaryResolver(@NotNull String agentId,
                                   @NotNull String binaryName,
                                   @NotNull String... alternates) {
        this.agentId = agentId;
        this.binaryName = binaryName;
        this.alternates = alternates;
    }

    @Override
    @Nullable
    protected String customBinaryPath() {
        return AgentProfileManager.getInstance().loadBinaryPath(agentId);
    }

    @Override
    @NotNull
    protected String primaryBinaryName() {
        return binaryName;
    }

    @Override
    @NotNull
    protected String[] alternateNames() {
        return alternates;
    }
}
