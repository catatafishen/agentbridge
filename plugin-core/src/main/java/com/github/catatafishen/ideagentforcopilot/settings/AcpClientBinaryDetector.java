package com.github.catatafishen.ideagentforcopilot.settings;

import com.github.catatafishen.ideagentforcopilot.services.AgentProfileManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Binary detector for ACP-based clients (Copilot, Junie, Kiro, OpenCode, Codex).
 * Reads the user-configured path from {@link AgentProfileManager#loadBinaryPath(String)}.
 */
public final class AcpClientBinaryDetector extends ClientBinaryDetector {

    private final String agentId;

    public AcpClientBinaryDetector(@NotNull String agentId) {
        this.agentId = agentId;
    }

    @Override
    @Nullable
    protected String getConfiguredPath() {
        return AgentProfileManager.getInstance().loadBinaryPath(agentId);
    }
}
