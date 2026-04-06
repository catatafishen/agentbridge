package com.github.catatafishen.agentbridge.settings;

import com.github.catatafishen.agentbridge.services.AgentProfile;
import com.github.catatafishen.agentbridge.services.AgentProfileManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * {@link AgentBinaryResolver} for the Claude CLI client.
 *
 * <p>Reads the user's custom binary path from the {@link AgentProfile} managed by
 * {@link AgentProfileManager}, which persists it as part of the profile (distinct from
 * the {@code PropertiesComponent} used by ACP clients).
 */
public class ClaudeAgentBinaryResolver extends AgentBinaryResolver {

    @Override
    @Nullable
    protected String customBinaryPath() {
        AgentProfile profile = AgentProfileManager.getInstance()
            .getProfile(AgentProfileManager.CLAUDE_CLI_PROFILE_ID);
        if (profile == null) return null;
        String path = profile.getCustomBinaryPath();
        return path.isBlank() ? null : path;
    }

    @Override
    @NotNull
    protected String primaryBinaryName() {
        return "claude";
    }
}
